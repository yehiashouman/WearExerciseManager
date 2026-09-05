package com.yehiashouman.wearexercisemanager.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.yehiashouman.wearexercisemanager.shared.WearDataPaths

/**
 * Receives the phone acknowledgements that flip a session to
 * [com.yehiashouman.wearexercisemanager.shared.SyncStatus.DELIVERED]. This is the only place that
 * may mark a session as delivered.
 */
class PhoneAckListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val coordinator = PhoneTransferCoordinator.getInstance(applicationContext)
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path.orEmpty()
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            if (!path.startsWith(WearDataPaths.SESSION_ACK_PREFIX)) return@forEach
            val sessionId = DataMapItem.fromDataItem(event.dataItem).dataMap
                .getString(WearDataPaths.KEY_SESSION_ID)
                ?: path.removePrefix(WearDataPaths.SESSION_ACK_PREFIX)
            if (sessionId.isBlank()) {
                Log.w(TAG, "Acknowledgement at $path had no session id")
                return@forEach
            }
            Log.i(TAG, "Phone acknowledgement received for session $sessionId")
            coordinator.acknowledge(sessionId)
        }
    }

    private companion object {
        const val TAG = "PhoneAckListener"
    }
}
