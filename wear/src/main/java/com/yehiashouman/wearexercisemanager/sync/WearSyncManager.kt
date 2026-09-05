package com.yehiashouman.wearexercisemanager.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
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

    /**
     * Writes the session payload for the phone.
     *
     * A `true` result only means the Data Layer accepted the payload while a phone is connected;
     * the session is confirmed exclusively by the phone acknowledgement handled in
     * [PhoneTransferCoordinator].
     */
    suspend fun sendSession(session: WorkoutSession, samsungHealthSync: Boolean): Boolean = runCatching {
        val path = "${WearDataPaths.SESSION_PREFIX}${session.id}"
        // A failed query is "unknown", which must not be confused with "no phone connected".
        val nodeCount = runCatching { nodeClient.connectedNodes.await().size }
            .onFailure { Log.w(TAG, "Could not query connected nodes", it) }
            .getOrNull()
        Log.i(TAG, "Attempting phone transfer of session ${session.id} to ${nodeCount ?: "unknown"} connected node(s)")
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString(WearDataPaths.KEY_SESSION_JSON, json.encodeToString(session))
            dataMap.putLong(WearDataPaths.KEY_UPDATED_AT, System.currentTimeMillis())
            // The phone owns the Samsung Health integration, so the watch preference travels with
            // the payload instead of being duplicated in a second store.
            dataMap.putBoolean(WearDataPaths.KEY_SAMSUNG_HEALTH_SYNC, samsungHealthSync)
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

    /** Removes the payload of a session the phone has confirmed, keeping the Data Layer small. */
    suspend fun deleteSession(sessionId: String) {
        val uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .authority("*")
            .path("${WearDataPaths.SESSION_PREFIX}$sessionId")
            .build()
        runCatching { client.deleteDataItems(uri).await() }
            .onFailure { Log.w(TAG, "Could not delete payload of session $sessionId", it) }
    }

    /**
     * Acknowledgements already present on the Data Layer. Used to reconcile sessions whose
     * acknowledgement arrived while the watch app was not running.
     */
    suspend fun acknowledgedSessionIds(): List<String> = runCatching {
        // The wildcard authority is required because the acknowledgements come from the phone node.
        val uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .authority("*")
            .path(WearDataPaths.SESSION_ACK_PREFIX)
            .build()
        val buffer = client.getDataItems(uri, DataClient.FILTER_PREFIX).await()
        try {
            buffer.mapNotNull { item ->
                DataMapItem.fromDataItem(item).dataMap.getString(WearDataPaths.KEY_SESSION_ID)
                    ?: item.uri.path?.removePrefix(WearDataPaths.SESSION_ACK_PREFIX)?.takeIf { it.isNotBlank() }
            }
        } finally {
            buffer.release()
        }
    }.onFailure { Log.w(TAG, "Could not read phone acknowledgements", it) }
        .getOrDefault(emptyList())

    private companion object {
        const val TAG = "WearSyncManager"
    }
}
