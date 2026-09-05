package com.yehiashouman.wearexercisemanager.workout

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yehiashouman.wearexercisemanager.MainActivity
import com.yehiashouman.wearexercisemanager.data.AppRepository
import com.yehiashouman.wearexercisemanager.health.HeartRateMonitor
import com.yehiashouman.wearexercisemanager.shared.*
import com.yehiashouman.wearexercisemanager.sync.WearSyncManager
import com.yehiashouman.wearexercisemanager.voice.VoiceCoach
import com.yehiashouman.wearexercisemanager.voice.VoiceCommandListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkoutService : Service() {
    companion object {
        const val ACTION_START = "workout.start"
        const val ACTION_PAUSE = "workout.pause"
        const val ACTION_RESUME = "workout.resume"
        const val ACTION_NEXT = "workout.next"
        const val ACTION_SKIP = "workout.skip"
        const val ACTION_REPEAT = "workout.repeat"
        const val ACTION_STOP = "workout.stop"
        const val EXTRA_PRESET_ID = "preset_id"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "WorkoutService"
        private const val MAX_PENDING_RETRIES = 5

        private val _state = MutableStateFlow(WorkoutUiState())
        val state: StateFlow<WorkoutUiState> = _state.asStateFlow()

        /** Clears the post-workout summary once the user has acknowledged it. */
        fun acknowledgeSummary() {
            if (!_state.value.running) _state.value = WorkoutUiState()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transferMutex = Mutex()
    private val transfersInFlight = mutableSetOf<String>()
    private lateinit var repo: AppRepository
    private lateinit var voice: VoiceCoach
    private lateinit var commands: VoiceCommandListener
    private lateinit var hr: HeartRateMonitor
    private lateinit var sync: WearSyncManager

    private var plan: List<PlannedSet> = emptyList()
    private var preset: WorkoutPreset? = null
    private var currentIndex = 0
    private var stageJob: Job? = null
    private var sessionStart = 0L
    private var intervalStart = 0L
    private var paused = false
    private var pausedStageRemaining = 0
    private var intervalOpen = false
    private val intervals = mutableListOf<ExerciseInterval>()
    private val heartRates = mutableListOf<HeartRateSample>()

    override fun onCreate() {
        super.onCreate()
        repo = AppRepository.getInstance(applicationContext)
        voice = VoiceCoach(applicationContext)
        commands = VoiceCommandListener(
            applicationContext,
            onCommand = ::handleVoiceCommand,
            onEnabledChanged = { active -> _state.value = _state.value.copy(listening = active) }
        )
        hr = HeartRateMonitor(applicationContext) { bpm ->
            heartRates += HeartRateSample(System.currentTimeMillis(), bpm, paused)
            _state.value = _state.value.copy(heartRate = bpm)
        }
        sync = WearSyncManager(applicationContext)
        createNotificationChannel()
        retryPendingPhoneTransfers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // The service is started with startForegroundService(), so a notification must be
                // posted immediately, even if the workout cannot be built.
                if (!startForegroundCompat()) return START_NOT_STICKY
                val presetId = intent.getStringExtra(EXTRA_PRESET_ID)
                if (presetId != null) startWorkout(presetId)
                if (!_state.value.running) stopWorkoutService()
            }
            ACTION_PAUSE -> pauseWorkout()
            ACTION_RESUME -> resumeWorkout()
            ACTION_NEXT -> advanceOne()
            ACTION_SKIP -> skipExercise()
            ACTION_REPEAT -> repeatCurrent()
            ACTION_STOP -> finishWorkout(SessionStatus.STOPPED)
        }
        return START_NOT_STICKY
    }

    private fun startWorkout(presetId: String) {
        if (_state.value.running) return
        val store = repo.store.value
        val found = store.presets.firstOrNull { it.id == presetId } ?: return
        val built = WorkoutPlanBuilder.build(found, store.exercises)
        if (built.isEmpty()) return

        preset = found
        plan = built
        currentIndex = 0
        intervals.clear()
        heartRates.clear()
        sessionStart = System.currentTimeMillis()
        paused = false
        voice.applySettings(store.settings)
        if (store.settings.recordHeartRate) hr.start()
        _state.value = WorkoutUiState(running = true, presetName = found.name, totalSteps = plan.size)
        startCurrentSet()
    }

    private fun startCurrentSet() {
        if (currentIndex !in plan.indices) {
            finishWorkout(SessionStatus.COMPLETED)
            return
        }
        stageJob?.cancel()
        paused = false
        val item = plan[currentIndex]
        val settings = repo.store.value.settings
        intervalStart = System.currentTimeMillis()
        intervalOpen = true
        _state.value = _state.value.copy(
            running = true,
            paused = false,
            stage = WorkoutStage.EXERCISE,
            exerciseName = item.exercise.name,
            setLabel = item.set.label,
            reps = item.set.reps,
            cycle = item.cycle,
            totalCycles = preset?.cycles ?: 1,
            remainingSeconds = item.set.durationSeconds,
            currentStep = currentIndex + 1,
            listening = settings.voiceCommands && commands.isAvailable
        )
        voice.speak(buildString {
            append(item.exercise.name).append(". ").append(item.set.label).append(".")
            if (item.set.reps > 0) append(" ${item.set.reps} reps.")
        }, true)
        // Voice commands stay active during the exercise itself, not only during rest/transition.
        activateCommands()
        runTimer(item.set.durationSeconds, WorkoutStage.EXERCISE) { completeCurrentInterval(); startRest() }
    }

    private fun startRest() {
        val item = plan.getOrNull(currentIndex) ?: return
        val rest = item.set.restSeconds.coerceAtLeast(0)
        if (rest == 0) startTransition() else {
            _state.value = _state.value.copy(stage = WorkoutStage.REST, remainingSeconds = rest)
            voice.speak("Rest.")
            activateCommands()
            runTimer(rest, WorkoutStage.REST) { startTransition() }
        }
    }

    private fun startTransition() {
        if (currentIndex >= plan.lastIndex) {
            finishWorkout(SessionStatus.COMPLETED)
            return
        }
        val seconds = repo.store.value.settings.transitionSeconds.coerceAtLeast(0)
        if (seconds == 0) { currentIndex++; startCurrentSet(); return }
        val next = plan[currentIndex + 1]
        val sameExercise = plan[currentIndex].exercise.id == next.exercise.id
        val phrase = if (sameExercise) "Next set in" else "Next exercise in"
        _state.value = _state.value.copy(stage = WorkoutStage.TRANSITION, remainingSeconds = seconds)
        voice.speak("$phrase $seconds")
        activateCommands()
        runTimer(seconds, WorkoutStage.TRANSITION, speakEverySecond = true) {
            currentIndex++
            startCurrentSet()
        }
    }

    private fun runTimer(seconds: Int, stage: WorkoutStage, speakEverySecond: Boolean = false, onDone: () -> Unit) {
        stageJob?.cancel()
        stageJob = scope.launch {
            var remaining = seconds
            while (remaining > 0) {
                _state.value = _state.value.copy(stage = stage, remainingSeconds = remaining)
                val settings = repo.store.value.settings
                val shouldSpeak = settings.spokenCountdown && (speakEverySecond || remaining <= settings.countdownStartSeconds)
                if (shouldSpeak && !(stage == WorkoutStage.TRANSITION && remaining == seconds)) voice.speak(remaining.toString())
                delay(1000)
                if (!paused) remaining--
                pausedStageRemaining = remaining
            }
            _state.value = _state.value.copy(remainingSeconds = 0)
            onDone()
        }
    }

    /** Records the interval that is currently in progress. Safe to call more than once. */
    private fun completeCurrentInterval() {
        if (!intervalOpen) return
        val item = plan.getOrNull(currentIndex) ?: return
        intervalOpen = false
        val end = System.currentTimeMillis()
        val duration = ((end - intervalStart) / 1000L).toInt().coerceAtLeast(0)
        intervals += ExerciseInterval(
            exerciseId = item.exercise.id,
            exerciseName = item.exercise.name,
            typeKey = item.exercise.typeKey,
            setId = item.set.id,
            setLabel = item.set.label,
            cycle = item.cycle,
            startEpochMs = intervalStart,
            endEpochMs = end,
            activeDurationSeconds = duration
        )
    }

    private fun advanceOne() {
        if (!_state.value.running) return
        completeCurrentInterval()
        stageJob?.cancel()
        currentIndex++
        startCurrentSet()
    }

    private fun skipExercise() {
        if (!_state.value.running) return
        val currentExerciseId = plan.getOrNull(currentIndex)?.exercise?.id ?: return
        completeCurrentInterval()
        stageJob?.cancel()
        val nextIndex = (currentIndex + 1 until plan.size).firstOrNull { plan[it].exercise.id != currentExerciseId }
        if (nextIndex == null) finishWorkout(SessionStatus.COMPLETED) else {
            currentIndex = nextIndex
            startCurrentSet()
        }
    }

    private fun repeatCurrent() {
        if (!_state.value.running) return
        stageJob?.cancel()
        startCurrentSet()
    }

    private fun pauseWorkout() {
        if (!_state.value.running || paused) return
        paused = true
        _state.value = _state.value.copy(paused = true, stage = WorkoutStage.PAUSED)
        voice.speak("Paused.", true)
        commands.start()
    }

    private fun resumeWorkout() {
        if (!_state.value.running || !paused) return
        paused = false
        _state.value = _state.value.copy(paused = false)
        voice.speak("Resuming.", true)
        // Timer coroutine remains alive and continues from the exact remaining second.
    }

    private fun activateCommands() {
        if (!repo.store.value.settings.voiceCommands) return
        commands.start()
        _state.value = _state.value.copy(listening = commands.isAvailable)
    }

    private fun handleVoiceCommand(command: String) {
        when (command) {
            "next" -> advanceOne()
            "skip" -> skipExercise()
            "pause" -> pauseWorkout()
            "resume" -> resumeWorkout()
            "repeat" -> repeatCurrent()
            "stop" -> finishWorkout(SessionStatus.STOPPED)
        }
        // Recognition restarts itself, so no touch interaction is needed to keep listening.
    }

    private fun finishWorkout(status: SessionStatus) {
        if (!_state.value.running) return
        stageJob?.cancel()
        // Make sure the final interval is recorded exactly once before the session is stored.
        completeCurrentInterval()
        commands.stop()
        hr.stop()
        val p = preset ?: return
        val end = System.currentTimeMillis()
        val session = WorkoutSession(
            presetId = p.id,
            presetName = p.name,
            startedAtEpochMs = sessionStart,
            endedAtEpochMs = end,
            status = status,
            intervals = intervals.toList(),
            heartRates = heartRates.toList(),
            syncStatus = SyncStatus.PENDING_PHONE_TRANSFER
        )
        repo.addSession(session)
        Log.i(TAG, "Workout ${status.name.lowercase()} - session ${session.id} saved locally with ${session.intervals.size} intervals")
        val averageHeartRate = heartRates.filterNot { it.paused }.map { it.bpm }.average().takeIf { !it.isNaN() }
        _state.value = WorkoutUiState(
            completed = status == SessionStatus.COMPLETED,
            stopped = status == SessionStatus.STOPPED,
            presetName = p.name,
            summaryDurationSeconds = ((end - sessionStart) / 1000L).toInt().coerceAtLeast(0),
            summaryIntervals = session.intervals.size,
            summaryAverageHeartRate = averageHeartRate
        )
        scope.launch {
            // Speaking is awaited so the engine is not shut down mid-utterance, but never blocks
            // persistence or the phone transfer.
            if (status == SessionStatus.COMPLETED) voice.speakAndAwait("Workout complete.", true)
            transferToPhone(session)
            stopWorkoutService()
        }
    }

    /**
     * Watch-to-phone transfer is independent of Samsung Health synchronization, which happens on
     * the phone. A failed transfer keeps the session locally so it can be retried later.
     */
    private suspend fun transferToPhone(session: WorkoutSession) {
        // Transfers are serialized so a retry and a just-finished workout cannot send the same
        // session twice or race each other's markSynced() writes.
        if (!transfersInFlight.add(session.id)) return
        try {
            transferMutex.withLock {
                Log.i(TAG, "Attempting phone transfer for session ${session.id}")
                val delivered = sync.sendSession(session)
                repo.markSynced(session.id, if (delivered) SyncStatus.PHONE_RECEIVED else SyncStatus.PENDING_PHONE_TRANSFER)
                val message = if (delivered) "Phone transfer succeeded for session ${session.id}"
                    else "Phone transfer failed for session ${session.id}; kept locally as pending"
                Log.i(TAG, message)
            }
        } finally {
            transfersInFlight -= session.id
        }
    }

    private fun retryPendingPhoneTransfers() {
        scope.launch {
            val pending = repo.store.value.history
                .filter { it.syncStatus == SyncStatus.PENDING || it.syncStatus == SyncStatus.PENDING_PHONE_TRANSFER }
                .sortedByDescending { it.endedAtEpochMs }
                .take(MAX_PENDING_RETRIES)
            if (pending.isEmpty()) return@launch
            Log.i(TAG, "Retrying phone transfer for ${pending.size} pending session(s)")
            pending.forEach { transferToPhone(it) }
        }
    }

    private fun startForegroundCompat(): Boolean {
        val types = if (Build.VERSION.SDK_INT >= 34) allowedForegroundServiceTypes() else 0
        if (Build.VERSION.SDK_INT >= 34 && types == 0) {
            // Without BODY_SENSORS or RECORD_AUDIO the declared service types cannot be used.
            Log.w(TAG, "Not starting workout: no granted foreground service permission")
            stopWorkoutService()
            return false
        }
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Missing runtime permissions or background start restrictions must not crash the app.
            Log.e(TAG, "startForeground failed", e)
            stopWorkoutService()
            false
        }
    }

    /**
     * On Android 14+ a foreground service type may only be used when its backing runtime
     * permission has been granted, otherwise startForeground() throws a SecurityException.
     */
    private fun allowedForegroundServiceTypes(): Int {
        var types = 0
        if (hasPermission(android.Manifest.permission.BODY_SENSORS)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        if (hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun stopWorkoutService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "workout")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Exercise Manager")
            .setContentText("Workout in progress")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("workout", "Active workout", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        stageJob?.cancel()
        hr.stop()
        commands.destroy()
        voice.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

enum class WorkoutStage { IDLE, EXERCISE, REST, TRANSITION, PAUSED }

data class WorkoutUiState(
    val running: Boolean = false,
    val completed: Boolean = false,
    val stopped: Boolean = false,
    val paused: Boolean = false,
    val stage: WorkoutStage = WorkoutStage.IDLE,
    val presetName: String = "",
    val exerciseName: String = "",
    val setLabel: String = "",
    val reps: Int = 0,
    val remainingSeconds: Int = 0,
    val cycle: Int = 1,
    val totalCycles: Int = 1,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val heartRate: Double? = null,
    val listening: Boolean = false,
    val summaryDurationSeconds: Int = 0,
    val summaryIntervals: Int = 0,
    val summaryAverageHeartRate: Double? = null
)
