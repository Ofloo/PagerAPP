package io.ofloo.pagerapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.poll.PollService

private val TABS = listOf("Pages", "Rules", "Settings", "Setup")

@Composable
fun PagerAppRoot(onServiceChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    var uuid by remember { mutableStateOf(store.uuid) }
    var tab by remember { mutableIntStateOf(0) }

    if (uuid == null) {
        SetupScreen(
            store = store,
            onReady = { newUuid ->
                uuid = newUuid
                onServiceChanged(true)
                tab = 0
            },
        )
        return
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> PagesScreen(store)
                1 -> RulesScreen(store)
                2 -> SettingsScreen(store, onServiceChanged)
                else -> SetupScreen(store, onReady = { uuid = it }, showGenerate = false)
            }
        }
    }
}

@Composable
private fun PagesScreen(store: Store) {
    var revision by remember { mutableIntStateOf(0) }
    val history = remember(revision) { store.history() }

    if (history.isEmpty()) {
        EmptyHint("No pages yet. They appear here as they arrive.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(history, key = { it.id }) { entry ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            entry.jobId ?: entry.project ?: "page",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(statusLabel(entry.status), color = statusColor(entry.status))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(entry.message ?: "(no message)")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append("status ${entry.status}")
                            if (!entry.ackUuid.isNullOrBlank()) append(" · ACK available")
                            if (!entry.delivered) append(" · not notified")
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun statusLabel(status: Int): String = when (status) {
    0 -> "0 Success"
    1 -> "1 Warning"
    2 -> "2 Failed"
    3 -> "3 Critical"
    4 -> "4 In Progress"
    5 -> "5 Queued"
    6 -> "6 Acknowledged"
    else -> status.toString()
}

@Composable
private fun statusColor(status: Int) = when (status) {
    2, 3 -> MaterialTheme.colorScheme.error
    6 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
