package io.ofloo.pagerapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.AppLinks
import io.ofloo.pagerapp.ThemeMode
import io.ofloo.pagerapp.notify.Notifications
import io.ofloo.pagerapp.shared.WatchMode

@Composable
fun SettingsScreen(
    store: Store,
    onServiceChanged: (Boolean) -> Unit,
    onThemeChanged: (ThemeMode) -> Unit,
) {
    var bypass by remember { mutableStateOf(store.bypassMode) }
    var silence by remember { mutableStateOf(store.silenceMode) }
    var poll by remember { mutableStateOf(store.pollSeconds.toString()) }
    var server by remember { mutableStateOf(store.serverUrl) }
    var config by remember { mutableStateOf(store.config) }
    var theme by remember { mutableStateOf(store.themeMode) }
    var reliable by remember { mutableStateOf(store.reliableDelivery) }
    var showAbout by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

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
                Text("Appearance", fontWeight = FontWeight.Bold)
                Picker(
                    label = "theme",
                    value = theme,
                    options = ThemeMode.entries,
                    display = {
                        when (it) {
                            ThemeMode.SYSTEM -> "follow system"
                            ThemeMode.LIGHT -> "light"
                            ThemeMode.DARK -> "dark"
                        }
                    },
                    onSelect = {
                        theme = it
                        store.themeMode = it
                        onThemeChanged(it)
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
                Text("Delivery", fontWeight = FontWeight.Bold)
                ToggleRow(
                    label = "Foreground service (fast: 30s–5min)",
                    checked = reliable,
                    onCheckedChange = {
                        reliable = it
                        store.reliableDelivery = it
                        onServiceChanged(true)   // re-apply the schedule
                    },
                )
                if (!reliable) {
                    Text(
                        "Background mode: no notification, but Android's minimum is 15 minutes " +
                            "between checks and Doze can delay it further.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                OutlinedTextField(
                    value = poll,
                    onValueChange = { v ->
                        poll = v.filter(Char::isDigit)
                        poll.toIntOrNull()?.let {
                            store.pollSeconds = it
                            onServiceChanged(true)
                        }
                    },
                    label = { Text("interval (seconds, 30–300)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (reliable) {
                    Text(
                        "A foreground service must show a notification. Ours is silent and has no " +
                            "status-bar icon — it only appears when you pull down the shade.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_SERVICE),
                            )
                        }
                    }) { Text("Hide its notification") }
                }
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mailbox", fontWeight = FontWeight.Bold)
                Text(
                    "Where your systems (CI, alerts, pager.sh) send pages. Keep this to yourself — " +
                        "contacts never see it; each chat has its own channel.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    store.uuid ?: "(creating…)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !store.uuid.isNullOrBlank(),
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("PagerAPP mailbox", store.uuid))
                            status = "Mailbox UUID copied"
                        },
                    ) { Text("Copy UUID") }
                }
                OutlinedTextField(
                    value = restore,
                    onValueChange = { restore = it },
                    label = { Text("restore: paste a mailbox UUID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = restore.isNotBlank(),
                    onClick = {
                        val id = restore.trim().substringAfterLast('/')
                        store.uuid = id
                        restore = ""
                        onServiceChanged(true)
                        status = "Mailbox restored"
                    },
                ) { Text("Restore") }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("About", fontWeight = FontWeight.Bold)
                Text(
                    "PagerAPP keeps nothing on a server and logs nothing. It only talks to the " +
                        "PagerMCP server you set above.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = { showAbout = true }) { Text("About PagerAPP") }
                TextButton(onClick = { uriHandler.openUri(AppLinks.PRIVACY_POLICY) }) {
                    Text("Privacy policy")
                }
            }
        }
    }

    if (showAbout) {
        AboutDialog(store = store, onDismiss = { showAbout = false })
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
