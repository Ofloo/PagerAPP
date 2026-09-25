package io.ofloo.pagerapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.shared.WatchMode

@Composable
fun SettingsScreen(store: Store, onServiceChanged: (Boolean) -> Unit) {
    var bypass by remember { mutableStateOf(store.bypassMode) }
    var silence by remember { mutableStateOf(store.silenceMode) }
    var poll by remember { mutableStateOf(store.pollSeconds.toString()) }
    var server by remember { mutableStateOf(store.serverUrl) }
    var config by remember { mutableStateOf(store.config) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Bypass / Silence: mutually exclusive quick toggles.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("On-call", fontWeight = FontWeight.Bold)
                ToggleRow(
                    label = "Bypass (on-call: show every page)",
                    checked = bypass,
                    onCheckedChange = {
                        bypass = it
                        store.bypassMode = it
                        if (it) { silence = false; store.silenceMode = false }
                    },
                )
                ToggleRow(
                    label = "Silence (show nothing, history only)",
                    checked = silence,
                    onCheckedChange = {
                        silence = it
                        store.silenceMode = it
                        if (it) { bypass = false; store.bypassMode = false }
                        onServiceChanged(!it)
                    },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Active hours", fontWeight = FontWeight.Bold)
                Text(
                    "Statuses set to 'active hours' only notify inside this window.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                config.activeWindows.forEachIndexed { index, window ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = window.start.toString(),
                            onValueChange = { v ->
                                parseTime(v)?.let { t ->
                                    val w = config.activeWindows.toMutableList()
                                    w[index] = window.copy(start = t)
                                    config = config.copy(activeWindows = w)
                                    store.config = config
                                }
                            },
                            label = { Text("start") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = window.end.toString(),
                            onValueChange = { v ->
                                parseTime(v)?.let { t ->
                                    val w = config.activeWindows.toMutableList()
                                    w[index] = window.copy(end = t)
                                    config = config.copy(activeWindows = w)
                                    store.config = config
                                }
                            },
                            label = { Text("end") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text("Format HH:MM, device local time.", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Watch", fontWeight = FontWeight.Bold)
                Picker(
                    label = "mode",
                    value = config.watchMode,
                    options = WatchMode.entries,
                    display = {
                        when (it) {
                            WatchMode.OFF -> "off (phone only)"
                            WatchMode.PHONE_AND_WATCH -> "phone + watch"
                            WatchMode.WATCH_ONLY -> "watch only (silent)"
                        }
                    },
                    onSelect = {
                        config = config.copy(watchMode = it)
                        store.config = config
                    },
                )
                Text(
                    "Watch delivery uses the platform companion link, not a push backend.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Polling", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = poll,
                    onValueChange = { v ->
                        poll = v.filter(Char::isDigit)
                        poll.toIntOrNull()?.let { store.pollSeconds = it }
                    },
                    label = { Text("interval (seconds, 30–300)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Default 60s. Below 30s hurts battery; the value is clamped to 30–300.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Recommended: 60s. Sub-minute precision is not needed.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Server", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; store.serverUrl = it },
                    label = { Text("PagerMCP base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun parseTime(value: String): java.time.LocalTime? =
    runCatching { java.time.LocalTime.parse(value.trim()) }.getOrNull()
