package com.yehiashouman.wearexercisemanager.workout

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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

        private val _state = MutableStateFlow(WorkoutUiState())
        val state: StateFlow<WorkoutUiState> = _state.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
    private val intervals = mutableListOf<ExerciseInterval>()
    private val heartRates = mutableListOf<HeartRateSample>()

    override fun onCreate() {
        super.onCreate()
        repo = AppRepository(applicationContext)
        voice = VoiceCoach(applicationContext)
        commands = VoiceCommandListener(applicationContext, ::handleVoiceCommand)
        hr = HeartRateMonitor(applicationContext) { bpm ->
            heartRates += HeartRateSample(System.currentTimeMillis(), bpm, paused)
            _state.value = _state.value.copy(heartRate = bpm)
        }
        sync = WearSyncManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_PRESET_ID)?.let(::startWorkout)
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
        startForegroundCompat()
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
            listening = settings.alwaysListening
        )
        voice.speak(buildString {
            append(item.exercise.name).append(". ").append(item.set.label).append(".")
            if (item.set.reps > 0) append(" ${item.set.reps} reps.")
        }, true)
        if (settings.alwaysListening && settings.voiceCommands) commands.start()
        runTimer(item.set.durationSeconds, WorkoutStage.EXERCISE) { completeCurrentInterval(); startRest() }
    }

    private fun startRest() {
        val item = plan.getOrNull(currentIndex) ?: return
        val rest = item.set.restSeconds.coerceAtLeast(0)
        if (rest == 0) startTransition() else {
            _state.value = _state.value.copy(stage = WorkoutStage.REST, remainingSeconds = rest)
            voice.speak("Rest.")
            activateCommandsForPassiveStage()
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
        activateCommandsForPassiveStage()
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

    private fun completeCurrentInterval() {
        val item = plan.getOrNull(currentIndex) ?: return
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
        if (_state.value.stage == WorkoutStage.EXERCISE) completeCurrentInterval()
        stageJob?.cancel()
        currentIndex++
        startCurrentSet()
    }

    private fun skipExercise() {
        if (!_state.value.running) return
        val currentExerciseId = plan.getOrNull(currentIndex)?.exercise?.id ?: return
        if (_state.value.stage == WorkoutStage.EXERCISE) completeCurrentInterval()
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

    private fun activateCommandsForPassiveStage() {
        val settings = repo.store.value.settings
        if (settings.voiceCommands) {
            commands.start()
            _state.value = _state.value.copy(listening = true)
        }
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
        if (repo.store.value.settings.alwaysListening && _state.value.running) scope.launch { delay(800); commands.start() }
    }

    private fun finishWorkout(status: SessionStatus) {
        if (!_state.value.running) return
        stageJob?.cancel()
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
            heartRates = heartRates.toList()
        )
        repo.addSession(session)
        if (status == SessionStatus.COMPLETED) voice.speak("Workout complete.", true)
        _state.value = WorkoutUiState(completed = status == SessionStatus.COMPLETED, presetName = p.name)
        scope.launch {
            if (repo.store.value.settings.samsungHealthSync) {
                val ok = sync.sendSession(session)
                repo.markSynced(session.id, if (ok) SyncStatus.SYNCED else SyncStatus.FAILED)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(1001, notification)
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
    val listening: Boolean = false
)
