package com.yehiashouman.wearexercisemanager.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

/** Wearable Data Layer paths shared by the watch sender and the phone listener. */
object WearDataPaths {
    /** Must stay in sync with the mobile manifest's `android:pathPrefix`. */
    const val SESSION_PREFIX = "/workout-session/"

    /**
     * Phone -> watch acknowledgement written once a session has been persisted on the phone.
     * Must stay in sync with the wear manifest's `android:pathPrefix`.
     */
    const val SESSION_ACK_PREFIX = "/workout-session-ack/"

    /** Keys of the payload written on [SESSION_PREFIX] data items. */
    const val KEY_SESSION_JSON = "session_json"
    const val KEY_UPDATED_AT = "updated_at"

    /**
     * Mirrors [AppSettings.samsungHealthSync] so the phone knows whether the user wants the
     * session forwarded to Samsung Health.
     */
    const val KEY_SAMSUNG_HEALTH_SYNC = "samsung_health_sync"

    /** Keys of the payload written on [SESSION_ACK_PREFIX] data items. */
    const val KEY_SESSION_ID = "session_id"
    const val KEY_ACK_AT = "acknowledged_at"
}

@Serializable
enum class WorkoutStyle { SEQUENTIAL, CIRCUIT }

@Serializable
enum class SessionStatus { COMPLETED, STOPPED }

/**
 * Single transfer state model used by both applications.
 *
 * [PENDING]   the session exists locally but the phone has not confirmed it yet;
 * [SENDING]   a transfer attempt is currently running on the watch;
 * [DELIVERED] the phone explicitly confirmed that the session was received *and* persisted;
 * [FAILED]    the transfer attempt failed and has to be retried.
 */
@Serializable(with = SyncStatusSerializer::class)
enum class SyncStatus { PENDING, SENDING, DELIVERED, FAILED }

/**
 * Accepts the legacy status names that may still be present in previously persisted files so an
 * existing install keeps its history instead of failing to decode it.
 */
object SyncStatusSerializer : KSerializer<SyncStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SyncStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SyncStatus) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): SyncStatus = when (decoder.decodeString()) {
        "PENDING", "PENDING_PHONE_TRANSFER" -> SyncStatus.PENDING
        "SENDING" -> SyncStatus.SENDING
        "DELIVERED", "PHONE_RECEIVED", "SYNCED" -> SyncStatus.DELIVERED
        "FAILED" -> SyncStatus.FAILED
        // An unknown state is treated as not yet confirmed, so it is retried instead of lost.
        else -> SyncStatus.PENDING
    }
}

/** Watch wording: the watch is the sender, so it may report a delivery. */
fun SyncStatus.watchLabel(): String = when (this) {
    SyncStatus.PENDING -> "Pending"
    SyncStatus.SENDING -> "Sending"
    SyncStatus.DELIVERED -> "Delivered"
    SyncStatus.FAILED -> "Failed"
}

/** Phone wording: the phone is the receiver, so it never reports "Delivered". */
fun SyncStatus.phoneLabel(): String = when (this) {
    SyncStatus.DELIVERED -> "Received"
    SyncStatus.FAILED -> "Failed"
    SyncStatus.PENDING, SyncStatus.SENDING -> "Pending"
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

/**
 * Every field is persisted by the watch repository and read at the place documented below; see
 * SETTINGS_AUDIT.md for the full setting -> storage -> behaviour mapping.
 */
@Serializable
data class AppSettings(
    /** Master switch for spoken guidance (`VoiceCoach.speakInternal`). */
    val voiceAnnouncements: Boolean = true,
    /** Speaks the remaining seconds near the end of a stage (`WorkoutService.runTimer`). */
    val spokenCountdown: Boolean = true,
    /** Second from which the spoken countdown starts (`WorkoutService.runTimer`). */
    val countdownStartSeconds: Int = 10,
    /** Enables the speech recogniser during a workout (`WorkoutService.activateCommands`). */
    val voiceCommands: Boolean = true,
    /**
     * Keeps the recogniser active during the exercise stage as well. When disabled, commands are
     * only recognised during rest, transition and pause so the microphone is off while training.
     *
     * The default is `true` on purpose: the flag used to be unused and the service listened during
     * every stage, so existing installs (which never persisted the field because it matched the old
     * default) keep exactly the behaviour they had before this setting was wired up.
     */
    val alwaysListening: Boolean = true,
    /** Text-to-speech rate (`VoiceCoach.applySettings`). */
    val speechRate: Float = 1.0f,
    /** Text-to-speech voice (`VoiceCoach.applySettings`). */
    val selectedVoiceName: String? = null,
    /** Pre-filled set duration for newly created sets (`ExerciseEditor`). */
    val defaultSetDurationSeconds: Int = 30,
    /** Increment of the duration steppers (`ExerciseEditor`). */
    val durationStepSeconds: Int = 15,
    /** Pre-filled rest duration for newly created sets (`ExerciseEditor`). */
    val defaultRestSeconds: Int = 30,
    /** Countdown between two sets (`WorkoutService.startTransition`). */
    val transitionSeconds: Int = 5,
    /** Shows the live heart rate on the active workout screen (`ActiveWorkoutScreen`). */
    val showHeartRate: Boolean = true,
    /** Stores heart-rate samples in the session history (`WorkoutService.onHeartRate`). */
    val recordHeartRate: Boolean = true,
    /** Haptic feedback on every stage change (`WorkoutService.vibrate`). */
    val vibration: Boolean = true,
    /** Forwarded to the phone so it can decide whether to write to Samsung Health. */
    val samsungHealthSync: Boolean = true,
    /** Accent colour of the whole watch UI (`ExerciseManagerApp`). */
    val accentTheme: String = "system"
)
