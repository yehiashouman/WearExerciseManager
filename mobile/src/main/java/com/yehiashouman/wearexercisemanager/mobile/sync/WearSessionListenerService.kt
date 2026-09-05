package com.yehiashouman.wearexercisemanager.mobile.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.yehiashouman.wearexercisemanager.shared.WearDataPaths
import com.yehiashouman.wearexercisemanager.shared.WorkoutSession
import kotlinx.serialization.json.Json

class WearSessionListenerService : WearableListenerService() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val store = SessionStore.getInstance(applicationContext)
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path.orEmpty()
            Log.i(TAG, "DataEvent received (type=${event.type}) for path $path")
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            if (!path.startsWith(WearDataPaths.SESSION_PREFIX)) return@forEach
            val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("session_json")
            if (raw == null) {
                Log.w(TAG, "Data item at $path had no session payload")
                return@forEach
            }
            runCatching { json.decodeFromString<WorkoutSession>(raw) }
                .onFailure { Log.e(TAG, "Could not decode workout session from $path", it) }
                .onSuccess { session ->
                    Log.i(TAG, "WorkoutSession decoded (${session.intervals.size} intervals), storing ${session.id}")
                    runCatching { store.upsert(session) }
                        .onFailure { Log.e(TAG, "Could not store session ${session.id}", it) }
                        .onSuccess { Log.i(TAG, "Session ${session.id} stored") }
                }
        }
    }

    private companion object {
        const val TAG = "WearSessionListener"
    }
}

