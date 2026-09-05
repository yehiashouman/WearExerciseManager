package com.yehiashouman.wearexercisemanager.data

import android.content.Context
import com.yehiashouman.wearexercisemanager.shared.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class AppStore(
    val exercises: List<ExerciseDefinition> = emptyList(),
    val presets: List<WorkoutPreset> = emptyList(),
    val history: List<WorkoutSession> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val selectedPresetId: String? = null
)

class AppRepository private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: AppRepository? = null

        /** Single process-wide repository so the UI and [WorkoutService] observe the same state. */
        fun getInstance(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }

    private val file = File(context.filesDir, "exercise_manager.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val _store = MutableStateFlow(load())
    val store: StateFlow<AppStoreView> = MutableStateFlow(_store.value.toView())

    private val exposed = store as MutableStateFlow<AppStoreView>

    private fun load(): AppStore = runCatching {
        if (file.exists()) json.decodeFromString<AppStore>(file.readText()) else seed()
    }.getOrElse { seed() }

    private fun seed(): AppStore {
        val bicep = ExerciseDefinition(
            name = "Bicep Curl",
            typeKey = "bicep_curl",
            iconKey = "strength",
            sets = listOf(
                ExerciseSet(label = "10 kg", reps = 12),
                ExerciseSet(label = "12 kg", reps = 10),
                ExerciseSet(label = "13 kg", reps = 8)
            )
        )
        val jumping = ExerciseDefinition(
            name = "Jumping Jacks",
            typeKey = "jumping_jacks",
            iconKey = "cardio",
            sets = listOf(
                ExerciseSet(label = "Set 1", durationSeconds = 30),
                ExerciseSet(label = "Set 2", durationSeconds = 30)
            )
        )
        val preset = WorkoutPreset(
            name = "Sample Workout",
            exerciseIds = listOf(bicep.id, jumping.id),
            style = WorkoutStyle.CIRCUIT,
            cycles = 3
        )
        return AppStore(
            exercises = listOf(bicep, jumping),
            presets = listOf(preset),
            selectedPresetId = preset.id
        )
    }

    @Synchronized
    private fun persist(next: AppStore) {
        _store.value = next
        exposed.value = next.toView()
        file.writeText(json.encodeToString(next))
    }

    fun saveExercise(exercise: ExerciseDefinition) {
        val current = _store.value
        val updated = current.exercises.toMutableList()
        val index = updated.indexOfFirst { it.id == exercise.id }
        if (index >= 0) updated[index] = exercise else updated += exercise
        persist(current.copy(exercises = updated))
    }

    fun deleteExercise(id: String) {
        val current = _store.value
        persist(current.copy(
            exercises = current.exercises.filterNot { it.id == id },
            presets = current.presets.map { it.copy(exerciseIds = it.exerciseIds.filterNot { eid -> eid == id }) }
        ))
    }

    fun savePreset(preset: WorkoutPreset) {
        val current = _store.value
        val updated = current.presets.toMutableList()
        val index = updated.indexOfFirst { it.id == preset.id }
        if (index >= 0) updated[index] = preset else updated += preset
        persist(current.copy(presets = updated, selectedPresetId = current.selectedPresetId ?: preset.id))
    }

    fun deletePreset(id: String) {
        val current = _store.value
        val presets = current.presets.filterNot { it.id == id }
        persist(current.copy(presets = presets, selectedPresetId = if (current.selectedPresetId == id) presets.firstOrNull()?.id else current.selectedPresetId))
    }

    fun selectPreset(id: String) = persist(_store.value.copy(selectedPresetId = id))

    fun addSession(session: WorkoutSession) = persist(_store.value.copy(history = listOf(session) + _store.value.history))

    fun updateSettings(settings: AppSettings) = persist(_store.value.copy(settings = settings))

    fun clearHistory() = persist(_store.value.copy(history = emptyList()))

    /** Persists the transfer state of a session so it survives a process restart. */
    fun markTransferStatus(sessionId: String, status: SyncStatus) {
        val current = _store.value
        if (current.history.none { it.id == sessionId && it.syncStatus != status }) return
        persist(current.copy(history = current.history.map { if (it.id == sessionId) it.copy(syncStatus = status) else it }))
    }

    /** Current transfer state of a session, or `null` when the session is unknown. */
    fun sessionStatus(sessionId: String): SyncStatus? =
        _store.value.history.firstOrNull { it.id == sessionId }?.syncStatus
}

data class AppStoreView(
    val exercises: List<ExerciseDefinition>,
    val presets: List<WorkoutPreset>,
    val history: List<WorkoutSession>,
    val settings: AppSettings,
    val selectedPresetId: String?
) {
    val selectedPreset: WorkoutPreset? get() = presets.firstOrNull { it.id == selectedPresetId }
}

private fun AppStore.toView() = AppStoreView(exercises, presets, history, settings, selectedPresetId)
