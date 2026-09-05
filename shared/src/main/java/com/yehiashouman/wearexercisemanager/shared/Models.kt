package com.yehiashouman.wearexercisemanager.shared

import kotlinx.serialization.Serializable
import java.util.UUID

/** Wearable Data Layer paths shared by the watch sender and the phone listener. */
object WearDataPaths {
    /** Must stay in sync with the mobile manifest's `android:pathPrefix`. */
    const val SESSION_PREFIX = "/workout-session/"
}

@Serializable
enum class WorkoutStyle { SEQUENTIAL, CIRCUIT }

@Serializable
enum class SessionStatus { COMPLETED, STOPPED }

@Serializable
enum class SyncStatus { PENDING, PENDING_PHONE_TRANSFER, PHONE_RECEIVED, SYNCED, FAILED }

/** User facing description of a session transfer state, shared by the watch and phone UIs. */
fun SyncStatus.displayLabel(): String = when (this) {
    SyncStatus.PENDING, SyncStatus.PENDING_PHONE_TRANSFER -> "pending"
    SyncStatus.PHONE_RECEIVED -> "delivered to phone"
    SyncStatus.SYNCED -> "synced"
    SyncStatus.FAILED -> "failed"
}

@Serializable
data class ExerciseSet(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val reps: Int = 0,
    val durationSeconds: Int = 30,
    val restSeconds: Int = 30
)

@Serializable
data class ExerciseDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val typeKey: String = "custom",
    val iconKey: String = "custom",
    val sets: List<ExerciseSet> = listOf(ExerciseSet(label = "Set 1"))
)

@Serializable
data class WorkoutPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val exerciseIds: List<String>,
    val style: WorkoutStyle = WorkoutStyle.SEQUENTIAL,
    val cycles: Int = 1
)

@Serializable
data class HeartRateSample(
    val timestampEpochMs: Long,
    val bpm: Double,
    val paused: Boolean
)

@Serializable
data class ExerciseInterval(
    val exerciseId: String,
    val exerciseName: String,
    val typeKey: String,
    val setId: String,
    val setLabel: String,
    val cycle: Int,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val activeDurationSeconds: Int
)

@Serializable
data class WorkoutSession(
    val id: String = UUID.randomUUID().toString(),
    val presetId: String,
    val presetName: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val status: SessionStatus,
    val intervals: List<ExerciseInterval>,
    val heartRates: List<HeartRateSample>,
    val syncStatus: SyncStatus = SyncStatus.PENDING
)

@Serializable
data class AppSettings(
    val voiceAnnouncements: Boolean = true,
    val spokenCountdown: Boolean = true,
    val countdownStartSeconds: Int = 10,
    val voiceCommands: Boolean = true,
    val alwaysListening: Boolean = false,
    val speechRate: Float = 1.0f,
    val selectedVoiceName: String? = null,
    val defaultSetDurationSeconds: Int = 30,
    val durationStepSeconds: Int = 15,
    val defaultRestSeconds: Int = 30,
    val transitionSeconds: Int = 5,
    val showHeartRate: Boolean = true,
    val recordHeartRate: Boolean = true,
    val vibration: Boolean = true,
    val samsungHealthSync: Boolean = true,
    val accentTheme: String = "system"
)
