package com.yehiashouman.wearexercisemanager.sync

import android.content.Context
import android.util.Log
import com.yehiashouman.wearexercisemanager.data.AppRepository
import com.yehiashouman.wearexercisemanager.shared.SyncStatus
import com.yehiashouman.wearexercisemanager.shared.WorkoutSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the watch side of the workout transfer protocol.
 *
 * A session only becomes [SyncStatus.DELIVERED] when the phone acknowledges it on
 * `/workout-session-ack/<sessionId>`; a successful Data Layer write alone only means the transport
 * accepted the payload and therefore keeps the session [SyncStatus.PENDING] for a later retry.
 */
class PhoneTransferCoordinator private constructor(context: Context) {
    companion object {
        private const val TAG = "PhoneTransfer"
        private const val MAX_PENDING_RETRIES = 5
        private const val RETRY_BACKOFF_MS = 5_000L
        private const val MAX_RETRY_ROUNDS = 3

        @Volatile
        private var instance: PhoneTransferCoordinator? = null

        /** Single process-wide coordinator so the service, the UI and the ack listener agree. */
        fun getInstance(context: Context): PhoneTransferCoordinator =
            instance ?: synchronized(this) {
                instance ?: PhoneTransferCoordinator(context.applicationContext).also { instance = it }
            }
    }

    private val repo = AppRepository.getInstance(context)
    private val sync = WearSyncManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transferMutex = Mutex()
    private val retryMutex = Mutex()
    private val transfersInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Sends a session and records the resulting transfer state. Never marks it delivered. */
    suspend fun transfer(session: WorkoutSession) {
        // Transfers are serialized so a retry and a just-finished workout cannot send the same
        // session twice or race each other's status writes.
        if (!transfersInFlight.add(session.id)) return
        try {
            transferMutex.withLock {
                if (repo.sessionStatus(session.id) == SyncStatus.DELIVERED) return@withLock
                repo.markTransferStatus(session.id, SyncStatus.SENDING)
                Log.i(TAG, "Sending session ${session.id} to the phone")
                val accepted = sync.sendSession(session)
                // A fast acknowledgement may already have arrived while the payload was written,
                // and it must never be downgraded back to pending.
                if (repo.sessionStatus(session.id) == SyncStatus.DELIVERED) return@withLock
                // Otherwise the phone acknowledgement is still outstanding.
                repo.markTransferStatus(session.id, if (accepted) SyncStatus.PENDING else SyncStatus.FAILED)
                Log.i(
                    TAG,
                    if (accepted) "Session ${session.id} handed to the Data Layer; awaiting phone acknowledgement"
                    else "Session ${session.id} could not be handed to the phone; will retry"
                )
            }
        } finally {
            transfersInFlight -= session.id
        }
    }

    /** Applies a phone acknowledgement. Acknowledging an already delivered session is a no-op. */
    fun acknowledge(sessionId: String) {
        val current = repo.sessionStatus(sessionId)
        if (current == null) {
            Log.w(TAG, "Acknowledgement for unknown session $sessionId ignored")
            return
        }
        if (current == SyncStatus.DELIVERED) return
        repo.markTransferStatus(sessionId, SyncStatus.DELIVERED)
        Log.i(TAG, "Session $sessionId acknowledged by the phone")
    }

    /**
     * Reconciles with the acknowledgements already present on the Data Layer and retries the
     * sessions the phone has not confirmed yet. Bounded in both the number of sessions and rounds.
     */
    fun retryPendingTransfers() {
        scope.launch {
            // A retry round that is already running must not be duplicated by a second trigger.
            if (!retryMutex.tryLock()) return@launch
            try {
                sync.acknowledgedSessionIds().forEach { acknowledge(it) }
                for (round in 0 until MAX_RETRY_ROUNDS) {
                    val pending = pendingSessions()
                    if (pending.isEmpty()) break
                    if (round > 0) delay(RETRY_BACKOFF_MS * round)
                    Log.i(TAG, "Retrying phone transfer for ${pending.size} session(s), round ${round + 1}")
                    pending.forEach { transfer(it) }
                }
            } finally {
                retryMutex.unlock()
            }
        }
    }

    private fun pendingSessions(): List<WorkoutSession> = repo.store.value.history
        .filter { it.syncStatus != SyncStatus.DELIVERED }
        .sortedByDescending { it.endedAtEpochMs }
        .take(MAX_PENDING_RETRIES)
}
