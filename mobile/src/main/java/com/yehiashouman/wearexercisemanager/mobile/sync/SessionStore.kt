package com.yehiashouman.wearexercisemanager.mobile.sync

import android.content.Context
import android.util.Log
import com.yehiashouman.wearexercisemanager.shared.SyncStatus
import com.yehiashouman.wearexercisemanager.shared.WorkoutSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Process-wide store of the sessions received from the watch. The observable [sessions] flow lets
 * the UI update as soon as the Data Layer listener stores a new session.
 *
 * Every session held here has been received *and* persisted, so it is always stored with
 * [SyncStatus.DELIVERED], which the phone UI renders as "Received".
 */
class SessionStore private constructor(context: Context) {
    companion object {
        private const val TAG = "SessionStore"

        @Volatile
        private var instance: SessionStore? = null

        fun getInstance(context: Context): SessionStore =
            instance ?: synchronized(this) {
                instance ?: SessionStore(context.applicationContext).also { instance = it }
            }
    }

    private val file = File(context.filesDir, "received_sessions.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val _sessions = MutableStateFlow(reconcile(read()))
    val sessions: StateFlow<List<WorkoutSession>> = _sessions.asStateFlow()

    private fun read(): List<WorkoutSession> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString<List<WorkoutSession>>(file.readText())
    }.onFailure { Log.e(TAG, "Could not read stored sessions", it) }
        .getOrDefault(emptyList())

    /**
     * Older installs may still hold sessions carrying the watch-side pending state even though the
     * phone stored them, so they are corrected on load.
     */
    private fun reconcile(stored: List<WorkoutSession>): List<WorkoutSession> {
        val corrected = stored.map {
            if (it.syncStatus == SyncStatus.DELIVERED) it else it.copy(syncStatus = SyncStatus.DELIVERED)
        }
        if (corrected != stored) write(corrected)
        return corrected
    }

    fun all(): List<WorkoutSession> = _sessions.value

    /**
     * Inserts a newly received session or updates the existing one with the same workout id, so a
     * retried transfer can never create a duplicate history entry.
     */
    @Synchronized
    fun upsert(session: WorkoutSession) {
        val received = session.copy(syncStatus = SyncStatus.DELIVERED)
        val existing = _sessions.value.firstOrNull { it.id == received.id }
        val updated = if (existing == null) {
            _sessions.value + received
        } else {
            _sessions.value.map { if (it.id == received.id) received else it }
        }
        write(updated)
        _sessions.value = updated
        Log.i(TAG, if (existing == null) "Stored new session ${received.id}" else "Updated existing session ${received.id}")
    }

    private fun write(sessions: List<WorkoutSession>) {
        runCatching { file.writeText(json.encodeToString(sessions)) }
            .onFailure { Log.e(TAG, "Could not persist received sessions", it) }
    }
}
