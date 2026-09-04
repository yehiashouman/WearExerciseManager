package com.yehiashouman.wearexercisemanager.mobile.sync

import android.content.Context
import com.yehiashouman.wearexercisemanager.shared.WorkoutSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SessionStore(context: Context) {
    private val file = File(context.filesDir, "received_sessions.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun all(): List<WorkoutSession> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString<List<WorkoutSession>>(file.readText())
    }.getOrDefault(emptyList())

    fun upsert(session: WorkoutSession) {
        val list = all().filterNot { it.id == session.id }
        file.writeText(json.encodeToString(list + session))
    }
}
