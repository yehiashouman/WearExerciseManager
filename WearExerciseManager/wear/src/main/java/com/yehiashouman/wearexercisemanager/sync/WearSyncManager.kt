package com.yehiashouman.wearexercisemanager.sync

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.yehiashouman.wearexercisemanager.shared.WorkoutSession
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WearSyncManager(context: Context) {
    private val client = Wearable.getDataClient(context.applicationContext)
    private val json = Json { encodeDefaults = true }

    suspend fun sendSession(session: WorkoutSession): Boolean = runCatching {
        val request = PutDataMapRequest.create("/workout-session/${session.id}").apply {
            dataMap.putString("session_json", json.encodeToString(session))
            dataMap.putLong("updated_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        client.putDataItem(request).await()
        true
    }.getOrDefault(false)
}
