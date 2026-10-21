package io.ofloo.pagerapp.ui

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.sp
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.AppLinks
import io.ofloo.pagerapp.ThemeMode
import io.ofloo.pagerapp.notify.Notifications
import io.ofloo.pagerapp.net.MailboxStatus
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.net.ServerSettings
import io.ofloo.pagerapp.push.PushManager
import io.ofloo.pagerapp.shared.Pgp
import io.ofloo.pagerapp.shared.WatchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    var language by remember { mutableStateOf(store.appLanguage) }
    var reliable by remember { mutableStateOf(store.reliableDelivery) }
    var alarmPolling by remember { mutableStateOf(store.alarmPolling) }
    var showAbout by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf("") }
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var alternate by remember { mutableStateOf(store.alternateServer) }
    var checking by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<ServerSettings?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var keyMonths by remember { mutableStateOf(store.keyExpiryMonths) }
    var keyBits by remember { mutableStateOf(store.keyBits) }
    var keyRevision by remember { mutableIntStateOf(0) }
    var generating by remember { mutableStateOf(false) }
    var pushOn by remember { mutableStateOf(store.pushEnabled) }
    var fcmOn by remember { mutableStateOf(store.fcmEnabled) }
    var confirmReset by remember { mutableStateOf(false) }
    var pushStatus by remember { mutableStateOf<MailboxStatus?>(null) }
    var photoShare by remember { mutableStateOf(store.photoShareUrl) }
    var shareRetention by remember { mutableStateOf<Int?>(null) }
    var pageMailboxes by remember { mutableStateOf(store.pageMailboxes()) }
    var newPageName by remember { mutableStateOf("") }
    var generatingPage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = store.exportBackup()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("could not open the selected file")
                }
            }
            status = result.fold(
                onSuccess = { "Backup exported" },
                onFailure = { "Backup failed: ${it.message ?: "unknown error"}" },
            )
        }
    }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("could not open the selected file")
                }
            }
            result.fold(
                onSuccess = { pendingBackup = it },
                onFailure = { status = "Import failed: ${it.message ?: "unknown error"}" },
            )
        }
    }

    LaunchedEffect(Unit) {
        pushStatus = withContext(Dispatchers.IO) { fetchMailboxStatus(store) }
    }
    LaunchedEffect(store.photoShareUrl) {
        shareRetention = withContext(Dispatchers.IO) {
            PhotoShare.retentionDays(store.photoShareUrl)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Bypass / Silence: mutually exclusive quick toggles.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("On-call"), fontWeight = FontWeight.Bold)
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
                Text(tr("Appearance"), fontWeight = FontWeight.Bold)
                Picker(
                    label = tr("theme"),
                    value = theme,
                    options = ThemeMode.entries,
                    display = {
                        when (it) {
                            ThemeMode.SYSTEM -> tr("follow system")
                            ThemeMode.LIGHT -> tr("light")
                            ThemeMode.DARK -> tr("dark")
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
                Text(tr("Language"), fontWeight = FontWeight.Bold)
                Picker(
                    label = "language",
                    value = language,
                    options = listOf("system", "en", "nl", "fr", "de", "es"),
                    display = {
                        when (it) {
                            "system" -> "System"
                            "en" -> "English"
                            "nl" -> "Nederlands"
                            "fr" -> "Français"
                            "de" -> "Deutsch"
                            "es" -> "Español"
                            else -> it
                        }
                    },
                    onSelect = {
                        language = it
                        store.appLanguage = it
                        Translations.setLanguage(it)
                        (context as? android.app.Activity)?.recreate()
                    },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("General schedule"), fontWeight = FontWeight.Bold)
                Text(
                    "Levels set to 'General schedule' only notify inside these windows. A level " +
                        "can instead use its own hours under Rules.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WindowListEditor(
                    windows = config.activeWindows,
                    onChange = {
                        config = config.copy(activeWindows = it)
                        store.config = config
                    },
                    minWindows = 1,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Watch"), fontWeight = FontWeight.Bold)
                Picker(
                    label = tr("mode"),
                    value = config.watchMode,
                    options = WatchMode.entries,
                    display = {
                        when (it) {
                            WatchMode.OFF -> tr("off (phone only)")
                            WatchMode.PHONE_AND_WATCH -> tr("phone + watch")
                            WatchMode.WATCH_ONLY -> tr("watch only (silent)")
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
                Text(tr("Delivery"), fontWeight = FontWeight.Bold)
                ToggleRow(
                    label = "Foreground service (fast: 30s–5min)",
                    checked = reliable,
                    onCheckedChange = {
                        reliable = it
                        store.reliableDelivery = it
                        if (it) { alarmPolling = false; store.alarmPolling = false }
                        onServiceChanged(true)   // re-apply the schedule
                    },
                )
                ToggleRow(
                    label = "Wekker-polling (geen melding, ~1 min)",
                    checked = alarmPolling,
                    onCheckedChange = {
                        alarmPolling = it
                        store.alarmPolling = it
                        if (it) {
                            reliable = false
                            store.reliableDelivery = false
                            // Ask for exact alarms right away instead of leaving the user to find
                            // the setting themselves.
                            if (!io.ofloo.pagerapp.poll.AlarmPoll.exactAllowed(context)) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
                                    )
                                }
                            }
                        }
                        onServiceChanged(true)
                    },
                )
                if (alarmPolling) {
                    Text(
                        "De app wekt zichzelf elke " + poll + " s, haalt de postbussen op en slaapt weer. " +
                            "Geen melding in de statusbalk en niets via Google. In diepe slaap rekt " +
                            "Android dit op tot ongeveer negen minuten.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (io.ofloo.pagerapp.poll.AlarmPoll.exactAllowed(context)) {
                        Text(
                            "Machtiging voor exacte wekkers: toegestaan — de wekker loopt op je " +
                                "interval zolang je toestel wakker is.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        Text(
                            "Dit toestel staat exacte wekkers nog niet toe, dus de wekker kan " +
                                "uitlopen tot ongeveer negen minuten. Zet de machtiging " +
                                "\"Alarmen en herinneringen\" aan.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
                            )
                        }
                    }) { Text(tr("Wekker-machtiging openen")) }
                }
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
                    label = { Text(tr("interval (seconds, 30–300)")) },
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
                    }) { Text(tr("Hide its notification")) }
                }
            }
        }

        // Push: the server wakes this device the moment a page lands, so nothing has to wait for
        // the next poll. The page itself still travels the normal, encrypted path.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Push"), fontWeight = FontWeight.Bold)
                // If Android does not let us notify, every wake-up is wasted: the page arrives but
                // the user never learns about it. Say so, with a way to fix it.
                if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    Text(
                        "Meldingen staan uit voor PagerAPP, dus je krijgt niets te zien — ook al komen " +
                            "pagina's binnen. Zet ze aan bij de instellingen van je toestel.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }) { Text(tr("Meldingen aanzetten")) }
                }
                ToggleRow(
                    label = "Server push (SSE/UnifiedPush)",
                    checked = pushOn,
                    onCheckedChange = { on ->
                        pushOn = on
                        scope.launch {
                            pushStatus = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (on) {
                                        store.pushEnabled = true
                                        PushManager.requestRegistration(context)
                                        PushManager.renewIfNeeded(context, force = true)
                                    } else {
                                        PushManager.disable(context)
                                    }
                                    fetchMailboxStatus(store)
                                }.getOrNull()
                            }
                        }
                    },
                )
                // Optional route: Google wakes the app, which then fetches the page from PagerMCP.
                ToggleRow(
                    label = "Wake via Google (FCM)",
                    checked = fcmOn,
                    onCheckedChange = { on ->
                        fcmOn = on
                        scope.launch {
                            pushStatus = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (on) {
                                        store.fcmEnabled = true
                                        PushManager.reconcileFcm(context, force = true)
                                    } else {
                                        PushManager.disableFcm(context)
                                    }
                                    fetchMailboxStatus(store)
                                }.getOrNull()
                            }
                        }
                    },
                )
                if (!PushManager.fcmAvailable()) {
                    Text(
                        "Not configured in this build: without google-services.json there is no " +
                            "Firebase project to deliver through yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                val status = pushStatus
                Text(
                    when {
                        status == null -> "Reading the mailbox status…"
                        status.pushEnabled && status.subscriptions > 0 -> {
                            val expiry = status.daysUntilExpiry()
                            "Push is on: ${status.subscriptions} subscription(s)" +
                                (status.channels.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { " via $it" } ?: "") +
                                (expiry?.let { ", expires in $it day(s)" } ?: "")
                        }
                        pushOn -> "Server push is enabled; a distributor endpoint is still needed for " +
                            "the UnifiedPush route. FCM is configured separately below."
                        else -> "Push is off."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (status != null) {
                    Text(
                        "Queued: ${status.messages} · retention: ${status.messageTtlSeconds / 86_400} d " +
                            "(mailbox ${status.mailboxTtlSeconds / 86_400} d)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (store.uuid != null) {
                    // Which app can actually deliver a wake-up for us? Without a distributor there
                    // is no push at all, and that is invisible from the server's side.
                    val installed = remember(pushStatus, pushOn) { PushManager.distributors(context) }
                    Text(
                        if (installed.isEmpty()) {
                            "No distributor installed: push cannot work. Install (or update) the ntfy " +
                                "app so it can hold the connection for us; polling keeps working either way."
                        } else {
                            "Distributor: ${PushManager.chosenDistributor(context) ?: "(not chosen yet)"}" +
                                " · installed: ${installed.joinToString(", ")}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                pushStatus = withContext(Dispatchers.IO) {
                                    runCatching {
                                    PushManager.requestRegistration(context)
                                    PushManager.renewIfNeeded(context, force = true)
                                    if (store.fcmEnabled) PushManager.reconcileFcm(context, force = true)
                                    fetchMailboxStatus(store)
                                    }.getOrNull()
                                }
                            }
                        }) { Text(tr("Renew now")) }
                        TextButton(onClick = {
                            scope.launch {
                                pushStatus = withContext(Dispatchers.IO) {
                                    runCatching { fetchMailboxStatus(store) }.getOrNull()
                                }
                            }
                        }) { Text(tr("Refresh")) }
                    }
                    if (store.pushChannel.isNotBlank()) {
                        Text(
                            "Registered on channel \"${store.pushChannel}\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Photo sharing"), fontWeight = FontWeight.Bold)
                Text(
                    "Photos are GPG-encrypted and uploaded to this transfer.sh-compatible host. " +
                        "The encrypted link is sent in the chat; both devices should use the same " +
                        "host to download each other's photos.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                OutlinedTextField(
                    value = photoShare,
                    onValueChange = { photoShare = it },
                    label = { Text(tr("share host URL")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val url = photoShare.trim().trimEnd('/')
                        if (!url.startsWith("https://")) {
                            status = "Use an https:// URL for the share host"
                        } else {
                            store.photoShareUrl = url
                            photoShare = url
                            status = "Photo share host saved"
                        }
                    }) { Text(tr("Save")) }
                    TextButton(onClick = {
                        store.photoShareUrl = Store.DEFAULT_PHOTO_SHARE
                        photoShare = Store.DEFAULT_PHOTO_SHARE
                        status = "Photo share host reset"
                    }) { Text(tr("Default")) }
                }
                val retentionLabel = shareRetention?.let { days -> "$days day(s)" } ?: "reading…"
                Text(
                    "Retention: $retentionLabel · default ${Store.DEFAULT_PHOTO_SHARE}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Encryption"), fontWeight = FontWeight.Bold)
                val fingerprint = remember(keyRevision, showAbout) { store.ownFingerprint }
                if (fingerprint.isBlank()) {
                    Text(
                        "No key yet. Chats stay readable until a key is generated; system pages " +
                            "are never encrypted.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    Text(
                        "your fingerprint",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        fingerprint.chunked(4).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    val expires = store.ownKeyExpiresAt
                    if (expires > 0) {
                        Text(
                            "expires ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expires * 1000L))}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Picker(
                    label = tr("key lifetime"),
                    value = keyMonths,
                    options = (Pgp.MIN_EXPIRY_MONTHS..Pgp.MAX_EXPIRY_MONTHS).toList(),
                    display = { "$it months" },
                    onSelect = { keyMonths = it; store.keyExpiryMonths = it },
                )
                Picker(
                    label = tr("key size"),
                    value = keyBits,
                    options = listOf(2048, 3072, 4096, 8192),
                    display = { "$it bits" },
                    onSelect = { keyBits = it; store.keyBits = it },
                )
                Text(
                    "Bigger is not meaningfully stronger — 2048 is the OpenPGP norm — but generation " +
                        "gets much slower: 8192 can take minutes on a phone and the app makes two keys.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                fun doKey(action: () -> Pgp.KeyMaterial, what: String) {
                    generating = true
                    status = null
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { runCatching { action() } }
                        generating = false
                        outcome
                            .onSuccess {
                                keyRevision++
                                status = "$what ok (${it.fingerprint.take(16)}…)"
                            }
                            .onFailure {
                                Log.e("PagerAPP", "$what failed", it)
                                status = "$what failed: ${it.javaClass.simpleName}: ${it.message}"
                            }
                    }
                }

                Button(
                    enabled = !generating,
                    onClick = {
                        doKey(
                            { if (fingerprint.isBlank()) store.renewKeys() else store.extendOwnKeys() },
                            if (fingerprint.isBlank()) "Key" else "Extension",
                        )
                    },
                ) {
                    Text(
                        when {
                            generating -> "Working…"
                            fingerprint.isBlank() -> "Generate key"
                            else -> "Extend (keep fingerprint)"
                        },
                    )
                }
                if (fingerprint.isNotBlank()) {
                    TextButton(
                        enabled = !generating,
                        onClick = { doKey({ store.renewKeys() }, "New key") },
                    ) { Text(tr("New key (changes your fingerprint)")) }
                    Text(
                        "A new key means contacts must adopt it — the app announces it to every chat " +
                            "automatically, and can ask them to resend anything that arrived encrypted " +
                            "to the old one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    "The public key travels with every message, so a new one reaches your " +
                        "contacts automatically after a renewal.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Servers"), fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; store.serverUrl = it },
                    label = { Text(tr("PagerMCP base URL")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = alternate,
                    onValueChange = { alternate = it; store.alternateServer = it },
                    label = { Text(tr("alternate server (used when the first is unreachable)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = !checking, onClick = {
                    checking = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { PagerClient.settings(store.serverUrl) }
                        }
                        checking = false
                        found = result.getOrNull()
                        failure = result.exceptionOrNull()?.let {
                            "${it.javaClass.simpleName}: ${it.message}"
                        }
                    }
                }) { Text(if (checking) "Checking…" else "Test server") }
                found?.let { s ->
                    Text(
                        "${s.version} (build ${s.build}) on ${s.hostname}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        "push: ${if (s.subscriptionsEnabled) s.channels.joinToString(", ") else "off"}" +
                            " · lease ${s.leaseSeconds / 86400}d · warn ${s.warnSeconds / 86400}d",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        "sse: ${if (s.sseEnabled) "on, keepalive ${s.sseKeepaliveSeconds}s" else "off"}" +
                            " · wait ${s.waitTimeoutSeconds}s",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Mailbox"), fontWeight = FontWeight.Bold)
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
                            cm.setPrimaryClip(ClipData.newPlainText(tr("PagerAPP mailbox"), store.uuid))
                            status = "Mailbox UUID copied"
                        },
                    ) { Text(tr("Copy UUID")) }
                }
                OutlinedTextField(
                    value = restore,
                    onValueChange = { restore = it },
                    label = { Text(tr("restore: paste a mailbox UUID")) },
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
                ) { Text(tr("Restore")) }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Backup & restore"), fontWeight = FontWeight.Bold)
                Text(
                    "Exports settings, mailbox addresses, contacts, history and your PGP keys. " +
                        "The file contains your mailbox bearer UUID and private key — keep it private.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                            exportBackup.launch("PagerAPP-backup-$day.json")
                        },
                    ) { Text(tr("Export backup")) }
                    OutlinedButton(
                        onClick = { importBackup.launch(arrayOf("application/json", "text/plain")) },
                    ) { Text(tr("Import backup")) }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Page addresses"), fontWeight = FontWeight.Bold)
                Text(
                    "Generate a separate mailbox for each logging source (CI, a logger, ...). " +
                        "Give that UUID to the source; PagerAPP polls all of them and notifies the same way.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(tr("Main"), fontWeight = FontWeight.Bold)
                Text(
                    store.uuid ?: "(creating…)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Row {
                    TextButton(
                        enabled = !store.uuid.isNullOrBlank(),
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(tr("PagerAPP mailbox"), store.uuid))
                            status = "Main mailbox UUID copied"
                        },
                    ) { Text(tr("Copy")) }
                }
                pageMailboxes.forEach { address ->
                    Text(address.name, fontWeight = FontWeight.Bold)
                    Text(
                        address.uuid,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Row {
                        TextButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(tr("PagerAPP address"), address.uuid))
                            status = "${address.name} UUID copied"
                        }) { Text(tr("Copy")) }
                        TextButton(onClick = {
                            store.removePageMailbox(address.id)
                            pageMailboxes = store.pageMailboxes()
                            status = "${address.name} removed"
                        }) { Text(tr("Delete")) }
                    }
                }
                OutlinedTextField(
                    value = newPageName,
                    onValueChange = { newPageName = it },
                    label = { Text(tr("name (e.g. CI, logger)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !generatingPage,
                    onClick = {
                        generatingPage = true
                        scope.launch {
                            val created = withContext(Dispatchers.IO) {
                                runCatching { PagerClient.createMailbox(store.serverUrl) }.getOrNull()
                            }
                            if (created != null) {
                                store.addPageMailbox(
                                    newPageName.trim().ifBlank { "Page address ${pageMailboxes.size + 1}" },
                                    created,
                                    store.serverUrl,
                                )
                                pageMailboxes = store.pageMailboxes()
                                newPageName = ""
                                status = "Page address generated"
                                if (store.fcmEnabled) {
                                    withContext(Dispatchers.IO) {
                                        runCatching { PushManager.reconcileFcm(context, force = true) }
                                    }
                                }
                            } else {
                                status = "Could not create a mailbox"
                            }
                            generatingPage = false
                        }
                    },
                ) { Text(if (generatingPage) "Generating…" else "Generate address") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("About"), fontWeight = FontWeight.Bold)
                Text(
                    "PagerAPP keeps nothing on a server and logs nothing. It only talks to the " +
                        "PagerMCP server you set above.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = { showAbout = true }) { Text(tr("About PagerAPP")) }
                TextButton(onClick = { uriHandler.openUri(AppLinks.PRIVACY_POLICY) }) {
                    Text(tr("Privacy policy"))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Defaults"), fontWeight = FontWeight.Bold)
                Text(
                    "Puts every setting back: rules and active hours, theme, poll interval, delivery " +
                        "mode, on-call/silence, key size and lifetime, and the push settings — including " +
                        "the registrations on the server. Your mailbox, its server, your keys, contacts " +
                        "and history are left alone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = { confirmReset = true }) { Text(tr("Reset settings to defaults")) }
            }
        }
    }

    pendingBackup?.let { backup ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingBackup = null },
            title = { Text(tr("Restore this backup?")) },
            text = {
                Text(
                    "This overwrites the current settings, mailbox addresses, contacts, history " +
                        "and PGP keys on this device. Push registrations will be renewed for this " +
                        "device.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = store.importBackup(backup)
                    pendingBackup = null
                    result.fold(
                        onSuccess = {
                            bypass = store.bypassMode
                            silence = store.silenceMode
                            poll = store.pollSeconds.toString()
                            server = store.serverUrl
                            config = store.config
                            theme = store.themeMode
                            language = store.appLanguage
                            Translations.setLanguage(store.appLanguage)
                            reliable = store.reliableDelivery
                            alarmPolling = store.alarmPolling
                            alternate = store.alternateServer
                            keyMonths = store.keyExpiryMonths
                            keyBits = store.keyBits
                            pushOn = store.pushEnabled
                            fcmOn = store.fcmEnabled
                            pageMailboxes = store.pageMailboxes()
                            onThemeChanged(theme)
                            onServiceChanged(true)
                            status = "Backup restored"
                        },
                        onFailure = { status = "Restore failed: ${it.message ?: "invalid backup"}" },
                    )
                }) { Text(tr("Restore")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBackup = null }) { Text(tr("Cancel")) }
            },
        )
    }

    if (showAbout) {
        AboutDialog(store = store, onDismiss = { showAbout = false })
    }

    if (confirmReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(tr("Reset to defaults?")) },
            text = {
                Text(
                    "Rules, active hours, theme, poll interval, delivery mode, on-call and silence, " +
                        "key size and lifetime and the push settings go back to their factory values, " +
                        "and the old push registrations are removed from the server. Your mailbox, " +
                        "server, keys, contacts and history are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    store.resetSettings()
                    // The server cleanup runs on its own thread, not in a coroutine tied to this
                    // screen: the screen is recreated right after, which would cancel it before it
                    // could reach the server (that is exactly what went wrong the first time).
                    val app = context.applicationContext
                    Thread({ runCatching { PushManager.forget(app) } }, "pagerapp-reset").start()
                    onServiceChanged(true)
                    (context as? android.app.Activity)?.recreate()
                }) { Text(tr("Reset")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

/** The mailbox's server-side state, or null when it cannot be read right now. */
private fun fetchMailboxStatus(store: Store): MailboxStatus? {
    val uuid = store.uuid ?: return null
    return runCatching { PagerClient.mailboxStatus(store.serverUrl, uuid) }.getOrNull()
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
