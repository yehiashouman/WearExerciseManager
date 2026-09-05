package com.yehiashouman.wearexercisemanager.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yehiashouman.wearexercisemanager.mobile.sync.SessionStore
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val store = remember { SessionStore.getInstance(applicationContext) }
                val received by store.sessions.collectAsState()
                val sessions = remember(received) { received.sortedByDescending { it.startedAtEpochMs } }
                val fmt = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Exercise Manager Companion", style = MaterialTheme.typography.headlineSmall)
                    Text("Receives precise workout intervals from the Wear OS app and provides the Samsung Health synchronization boundary.")
                    if (sessions.isEmpty()) Text("No watch sessions received yet.")
                    sessions.take(10).forEach { session ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(session.presetName, style = MaterialTheme.typography.titleMedium)
                                Text(fmt.format(Date(session.startedAtEpochMs)))
                                Text("${session.intervals.size} exercise intervals")
                                Text("Watch transfer: ${session.syncStatus.name.lowercase().replace('_', ' ')}")
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Samsung Health exercise writes require the Samsung Health Data SDK AAR plus approved partner access. Add these after Samsung approval; the watch remains fully functional without them.")
                }
            }
        }
    }
}
