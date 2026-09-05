package com.yehiashouman.wearexercisemanager.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.yehiashouman.wearexercisemanager.shared.WearDataPaths
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
        val path = "${WearDataPaths.SESSION_PREFIX}${session.id}"
        // A failed query is "unknown", which must not be confused with "no phone connected".
        val nodeCount = runCatching { nodeClient.connectedNodes.await().size }
            .onFailure { Log.w(TAG, "Could not query connected nodes", it) }
            .getOrNull()
        Log.i(TAG, "Attempting phone transfer of session ${session.id} to ${nodeCount ?: "unknown"} connected node(s)")
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString("session_json", json.encodeToString(session))
            dataMap.putLong("updated_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        client.putDataItem(request).await()
        Log.i(TAG, "DataItem created at $path (${session.intervals.size} intervals)")
        // The data item is written locally even without a peer, so an empty node list means the
        // session has not reached the phone yet and must stay pending for a later retry.
        if (nodeCount == 0) {
            Log.w(TAG, "No connected phone node; session ${session.id} stays pending")
            false
        } else {
            true
        }
    }.onFailure { Log.e(TAG, "Phone transfer failed for session ${session.id}", it) }
        .getOrDefault(false)

    private companion object {
        const val TAG = "WearSyncManager"
    }
}

