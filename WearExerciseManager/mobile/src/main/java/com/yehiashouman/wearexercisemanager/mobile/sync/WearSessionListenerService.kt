package com.yehiashouman.wearexercisemanager.mobile.sync

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.yehiashouman.wearexercisemanager.shared.WorkoutSession
import kotlinx.serialization.json.Json

class WearSessionListenerService : WearableListenerService() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val store = SessionStore(applicationContext)
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            if (!event.dataItem.uri.path.orEmpty().startsWith("/workout-session/")) return@forEach
            val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("session_json") ?: return@forEach
            runCatching { json.decodeFromString<WorkoutSession>(raw) }.getOrNull()?.let(store::upsert)
        }
    }
}
