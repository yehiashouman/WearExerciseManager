package com.yehiashouman.wearexercisemanager.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.yehiashouman.wearexercisemanager.shared.WorkoutSession
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WearSyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val client = Wearable.getDataClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val json = Json { encodeDefaults = true }

    suspend fun sendSession(session: WorkoutSession): Boolean = runCatching {
        val path = "$SESSION_PATH_PREFIX${session.id}"
        val nodes = runCatching { nodeClient.connectedNodes.await() }.getOrDefault(emptyList())
        Log.i(TAG, "Attempting phone transfer of session ${session.id} to ${nodes.size} connected node(s)")
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString("session_json", json.encodeToString(session))
            dataMap.putLong("updated_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        client.putDataItem(request).await()
        Log.i(TAG, "DataItem created at $path (${session.intervals.size} intervals)")
        true
    }.onFailure { Log.e(TAG, "Phone transfer failed for session ${session.id}", it) }
        .getOrDefault(false)

    companion object {
        const val SESSION_PATH_PREFIX = "/workout-session/"
        private const val TAG = "WearSyncManager"
    }
}

