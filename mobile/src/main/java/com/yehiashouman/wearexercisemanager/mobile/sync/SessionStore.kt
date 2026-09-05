package com.yehiashouman.wearexercisemanager.mobile.sync

import android.content.Context
import android.util.Log
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
    private val _sessions = MutableStateFlow(read())
    val sessions: StateFlow<List<WorkoutSession>> = _sessions.asStateFlow()

    private fun read(): List<WorkoutSession> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString<List<WorkoutSession>>(file.readText())
    }.onFailure { Log.e(TAG, "Could not read stored sessions", it) }
        .getOrDefault(emptyList())

    fun all(): List<WorkoutSession> = _sessions.value

    @Synchronized
    fun upsert(session: WorkoutSession) {
        val updated = _sessions.value.filterNot { it.id == session.id } + session
        runCatching { file.writeText(json.encodeToString(updated)) }
            .onFailure { Log.e(TAG, "Could not persist session ${session.id}", it) }
        _sessions.value = updated
    }
}

