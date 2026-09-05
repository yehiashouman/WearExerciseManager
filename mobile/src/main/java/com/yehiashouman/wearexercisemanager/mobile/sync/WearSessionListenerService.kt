package com.yehiashouman.wearexercisemanager.mobile.sync

import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.yehiashouman.wearexercisemanager.shared.WearDataPaths
import com.yehiashouman.wearexercisemanager.shared.WorkoutSession
import com.google.android.gms.tasks.Tasks
import kotlinx.serialization.json.Json

/**
 * Receives workout sessions from the watch, persists them and acknowledges each of them so the
 * watch can move the session from pending to delivered.
 */
class WearSessionListenerService : WearableListenerService() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val store = SessionStore.getInstance(applicationContext)
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path.orEmpty()
            Log.i(TAG, "DataEvent received (type=${event.type}) for path $path")
            if (!path.startsWith(WearDataPaths.SESSION_PREFIX)) return@forEach
            if (event.type == DataEvent.TYPE_DELETED) {
                // The watch removes the payload after it applied the acknowledgement, so the
                // matching acknowledgement is no longer needed either.
                deleteAcknowledgement(path.removePrefix(WearDataPaths.SESSION_PREFIX))
                return@forEach
            }
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WearDataPaths.KEY_SESSION_JSON)
            if (raw == null) {
                Log.w(TAG, "Data item at $path had no session payload")
                return@forEach
            }
            runCatching { json.decodeFromString<WorkoutSession>(raw) }
                .onFailure { Log.e(TAG, "Could not decode workout session from $path", it) }
                .onSuccess { session ->
                    if (session.id.isBlank()) {
                        Log.w(TAG, "Session from $path had no workout id; ignoring payload")
                        return@onSuccess
                    }
                    Log.i(TAG, "WorkoutSession decoded (${session.intervals.size} intervals), storing ${session.id}")
                    // The acknowledgement is only sent once the session is persisted, and it is
                    // also sent for a repeated payload because the watch may not have seen the
                    // first acknowledgement.
                    runCatching { store.upsert(session) }
                        .onFailure { Log.e(TAG, "Could not store session ${session.id}", it) }
                        .onSuccess { acknowledge(session.id) }
                }
        }
    }

    private fun acknowledge(sessionId: String) {
        val request = PutDataMapRequest.create("${WearDataPaths.SESSION_ACK_PREFIX}$sessionId").apply {
            dataMap.putString(WearDataPaths.KEY_SESSION_ID, sessionId)
            dataMap.putLong(WearDataPaths.KEY_ACK_AT, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        runCatching { Tasks.await(Wearable.getDataClient(applicationContext).putDataItem(request)) }
            .onFailure { Log.e(TAG, "Could not acknowledge session $sessionId", it) }
            .onSuccess { Log.i(TAG, "Acknowledged session $sessionId to the watch") }
    }

    private fun deleteAcknowledgement(sessionId: String) {
        if (sessionId.isBlank()) return
        val uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .authority("*")
            .path("${WearDataPaths.SESSION_ACK_PREFIX}$sessionId")
            .build()
        runCatching { Tasks.await(Wearable.getDataClient(applicationContext).deleteDataItems(uri)) }
            .onFailure { Log.w(TAG, "Could not delete acknowledgement of session $sessionId", it) }
    }

    private companion object {
        const val TAG = "WearSessionListener"
    }
}
