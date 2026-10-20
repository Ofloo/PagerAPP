package io.ofloo.pagerapp.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ofloo.pagerapp.ThemeMode
import io.ofloo.pagerapp.data.HistoryEntry
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.notify.Notifier
import io.ofloo.pagerapp.poll.Poller
import io.ofloo.pagerapp.poll.PollService
import io.ofloo.pagerapp.shared.Contact
import io.ofloo.pagerapp.shared.ContactKind
import io.ofloo.pagerapp.shared.FileLink
import io.ofloo.pagerapp.shared.PhotoLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TABS = listOf("Pages", "Contacts", "Rules", "Settings")

@Composable
fun PagerAppRoot(
    onServiceChanged: (Boolean) -> Unit,
    onThemeChanged: (ThemeMode) -> Unit,
    pageTarget: PageTarget? = null,
    onPageConsumed: () -> Unit = {},
    shareTarget: SharePayload? = null,
    onShareConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    var uuid by remember { mutableStateOf(store.uuid) }
    var tab by remember { mutableIntStateOf(0) }
    var chat by remember { mutableStateOf<Contact?>(null) }
    var focusPageId by remember { mutableStateOf<String?>(null) }
    var chatShare by remember { mutableStateOf<SharePayload?>(null) }
    var sharePicker by remember { mutableStateOf<SharePayload?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }

    // A notification tap opens the chat it came from, or the page detail for a system page.
    LaunchedEffect(pageTarget) {
        val target = pageTarget ?: return@LaunchedEffect
        val contact = store.contacts().firstOrNull {
            it.kind == ContactKind.PERSON && it.senderId == target.from
        }
        if (contact != null) {
            chat = contact
        } else {
            tab = 0
            focusPageId = target.pageId
        }
        onPageConsumed()
    }

    // Content from Android's share sheet: ask which contact should receive it.
    LaunchedEffect(shareTarget) {
        val share = shareTarget ?: return@LaunchedEffect
        sharePicker = share
    }

    // The private mailbox exists once and is created silently on first launch; sharing happens
    // per contact, so there is nothing to "set up".
    LaunchedEffect(uuid) {
        if (uuid == null) {
            val created = withContext(Dispatchers.IO) {
                runCatching { PagerClient.createMailbox(store.serverUrl) }.getOrNull()
            }
            if (created != null) {
                store.uuid = created
                uuid = created
                onServiceChanged(true)
            } else {
                setupError = "Cannot reach ${store.serverUrl} yet. Pull down on Pages to retry."
            }
        }
    }

    if (uuid == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Setting up PagerAPP…")
            setupError?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }

    // Resume polling whenever the app is opened with a configured mailbox.
    LaunchedEffect(Unit) {
        if (!store.silenceMode) onServiceChanged(true)
    }

    // System back / gesture navigation: leave a secondary tab for Pages. Chat and its overlays
    // register their own handlers; at the root this lets Android background the app normally.
    BackHandler(enabled = tab != 0) { tab = 0 }

    // An open conversation takes the whole screen, above the tabs.
    sharePicker?.let { share ->
        val contacts = store.contacts()
        AlertDialog(
            onDismissRequest = {
                sharePicker = null
                onShareConsumed()
            },
            title = { Text("Share to…") },
            text = {
                if (contacts.isEmpty()) {
                    Text("Add a contact first, then share again.")
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        contacts.forEach { contact ->
                            TextButton(onClick = {
                                sharePicker = null
                                onShareConsumed()
                                chatShare = share
                                chat = contact
                            }) {
                                Text(contact.name.ifBlank { contact.uuid.take(8) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    sharePicker = null
                    onShareConsumed()
                }) { Text(if (contacts.isEmpty()) "Close" else "Cancel") }
            },
        )
    }

    chat?.let { contact ->
        ChatScreen(
            contact = contact,
            store = store,
            onBack = {
                chat = null
                chatShare = null
            },
            initialShare = chatShare,
            onShareConsumed = { chatShare = null },
        )
        return
    }

    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Edge-to-edge draws behind the system bars; keep the tab row clear of the status
                // bar and the content clear of the navigation bar, exactly once.
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                TABS.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> PagesScreen(
                    store,
                    onOpenChat = { chat = it },
                    focusPageId = focusPageId,
                    onFocusConsumed = { focusPageId = null },
                )
                1 -> ContactsScreen(store, onOpenChat = { chat = it })
                2 -> RulesScreen(store)
                else -> SettingsScreen(store, onServiceChanged, onThemeChanged)
            }
        }
    }
}

enum class PageMode { CHATS, PAGES, ARCHIVED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PagesScreen(
    store: Store,
    onOpenChat: (Contact) -> Unit,
    focusPageId: String? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<HistoryEntry?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(PageMode.CHATS) }

    // A notification tap asked for one specific page: show it in the Pages feed detail.
    LaunchedEffect(focusPageId) {
        val id = focusPageId ?: return@LaunchedEffect
        mode = PageMode.PAGES
        store.history().firstOrNull { it.id == id }?.let { selected = it }
        onFocusConsumed()
    }

    // Back from the Pages/Archived filter returns to Chats instead of exiting the app.
    BackHandler(enabled = mode != PageMode.CHATS) { mode = PageMode.CHATS }
    var refreshing by remember { mutableStateOf(false) }

    // Refresh live while the poll service records pages in the background. Listeners fire on the
    // thread that wrote the prefs (the poll worker), so hop to the main thread before touching state.
    DisposableEffect(store) {
        val main = Handler(Looper.getMainLooper())
        val listener = store.registerListener(Store.HISTORY_KEYS) { main.post { revision++ } }
        onDispose { store.unregisterListener(listener) }
    }
    val all = remember(revision) { store.history() }
    // Every identity that belongs to a conversation: those entries live in Chats, not in the raw
    // page feed — otherwise the feed fills up with chatter instead of the pages that need attention.
    val chatSenders = remember(revision) {
        store.contacts()
            .flatMap { listOf(it.senderId, it.mySenderId) }
            .filter { it.isNotBlank() }
            .toSet()
    }
    val visible = remember(revision, mode) {
        all.filter { entry ->
            entry.archived == (mode == PageMode.ARCHIVED) &&
                (mode == PageMode.ARCHIVED || entry.from == null || entry.from !in chatSenders)
        }
    }

    /** Fetch right now — pull-to-refresh, and once when the screen opens. */
    fun refreshNow() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Poller.pollOnce(context) } }
            refreshing = false
            revision++
        }
    }
    LaunchedEffect(Unit) { refreshNow() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = mode == PageMode.CHATS,
                onClick = { mode = PageMode.CHATS },
                label = { Text("Chats") },
            )
            FilterChip(
                selected = mode == PageMode.PAGES,
                onClick = { mode = PageMode.PAGES },
                label = { Text("Pages") },
            )
            FilterChip(
                selected = mode == PageMode.ARCHIVED,
                onClick = { mode = PageMode.ARCHIVED },
                label = { Text("Archived") },
            )
        }

        banner?.let { text ->
            Text(
                text,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
        }

        if (mode == PageMode.CHATS) {
            ChatsList(store = store, history = all, onOpenChat = onOpenChat)
        } else {
            val showArchived = mode == PageMode.ARCHIVED
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshNow() },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (visible.isEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(
                                if (showArchived) "Nothing archived yet. Swipe a page right to archive it."
                                else "No pages yet. Pull down to fetch.",
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(visible, key = { it.id }) { entry ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    when (value) {
                                        SwipeToDismissBoxValue.StartToEnd -> {
                                            store.setArchived(entry.id, !showArchived)
                                            banner = if (showArchived) "Restored" else "Archived"
                                            revision++
                                        }
                                        SwipeToDismissBoxValue.EndToStart -> {
                                            store.deleteEntry(entry.id)
                                            banner = "Deleted"
                                            revision++
                                        }
                                        else -> Unit
                                    }
                                    false
                                },
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    SwipeBackground(dismissState.dismissDirection, showArchived)
                                },
                            ) {
                                PageCard(entry) { selected = entry }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        PageDetailDialog(
            entry = entry,
            onDismiss = { selected = null },
            onAck = { note ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            Notifier.sendAck(
                                context = context,
                                pageId = entry.id,
                                jobId = entry.jobId,
                                ackUuid = entry.ackUuid,
                                replyUuid = entry.replyUuid,
                                ackToken = entry.ackToken,
                                note = note,
                                mailbox = entry.mailbox ?: store.uuid,
                                serverUrl = entry.serverUrl ?: store.serverUrl,
                            )
                        }.getOrDefault(false)
                    }
                    banner = if (ok) {
                        revision++
                        "Acknowledged ${entry.jobId ?: entry.id.take(8)}"
                    } else {
                        "ACK failed — the server is unreachable, or this page has no ACK target."
                    }
                    selected = null
                }
            },
        )
    }
}

/** One row in the conversation list: who, what was said last, when, and how much is unread. */
private data class ChatRow(
    val contact: Contact,
    val last: HistoryEntry,
    val unread: Int,
)

/**
 * The conversation list, WhatsApp-shaped: one row per contact with the last message, sorted by
 * how recent it is, and an unread count. Tapping opens the conversation.
 */
@Composable
private fun ChatsList(store: Store, history: List<HistoryEntry>, onOpenChat: (Contact) -> Unit) {
    val rows = remember(history) {
        store.contacts().mapNotNull { contact ->
            val mine = contact.mySenderId
            val theirs = contact.senderId
            val thread = history.filter { entry ->
                (theirs.isNotBlank() && entry.from == theirs) ||
                    (mine.isNotBlank() && entry.from == mine)
            }
            val last = thread.maxByOrNull { it.createdAt } ?: return@mapNotNull null
            val unread = thread.count { entry ->
                theirs.isNotBlank() && entry.from == theirs && !entry.acked && !entry.notice
            }
            ChatRow(contact, last, unread)
        }.sortedByDescending { it.last.createdAt }
    }

    if (rows.isEmpty()) {
        Text(
            "No conversations yet. Add a contact on the Contacts tab.",
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(rows, key = { it.contact.id }) { row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onOpenChat(row.contact) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            row.contact.name.ifBlank { "(unnamed)" } +
                                if (row.contact.kind == ContactKind.SYSTEM) " · system" else "",
                            fontWeight = FontWeight.Bold,
                        )
                        val preview = row.last.message?.let { message ->
                            when {
                                PhotoLink.parse(message) != null -> "Photo link"
                                FileLink.parse(message) != null -> "Attachment"
                                else -> message
                            }
                        } ?: "(no text)"
                        Text(
                            preview,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(shortTime(row.last.createdAt), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.unread > 0) {
                            Text(
                                row.unread.toString(),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fullDateTime(epochSeconds: Double): String =
    if (epochSeconds <= 0) "—"
    else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date((epochSeconds * 1000).toLong()))

private fun shortTime(epochSeconds: Double): String {
    if (epochSeconds <= 0) return ""
    val then = Date((epochSeconds * 1000).toLong())
    val now = Date()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(then) ==
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)
    val pattern = if (sameDay) "HH:mm" else "dd/MM"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(then)
}

@Composable
private fun PageCard(entry: HistoryEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    entry.jobId ?: entry.project ?: "page",
                    fontWeight = FontWeight.Bold,
                )
                Text(statusLabel(entry.status, entry.prio), color = statusColor(entry.status))
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.message ?: "(no message)")
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    if (entry.createdAt > 0) append("${shortTime(entry.createdAt)} · ")
                    append("status ${entry.status}")
                    if (entry.acked) append(" · acknowledged")
                    else if (entry.delivered) append(" · tap to ACK")
                    if (!entry.delivered) append(" · not notified")
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Coloured backdrop revealed while swiping a page: right = archive, left = delete. */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, archivedView: Boolean) {
    val archive = direction == SwipeToDismissBoxValue.StartToEnd
    val colour = if (archive) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.errorContainer
    val onColour = if (archive) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onErrorContainer
    val label = when {
        archive && archivedView -> "Restore"
        archive -> "Archive"
        else -> "Delete"
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
            .background(colour, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (archive) Arrangement.Start else Arrangement.End,
    ) {
        Icon(
            imageVector = if (archive) Icons.Filled.Archive else Icons.Filled.Delete,
            contentDescription = label,
            tint = onColour,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = onColour, fontWeight = FontWeight.Bold)
    }
}

/** Full page view with the ACK action and an optional note, PagerDuty-style. */
@Composable
private fun PageDetailDialog(
    entry: HistoryEntry,
    onDismiss: () -> Unit,
    onAck: (String?) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.jobId ?: entry.project ?: "page") },
        text = {
            Column {
                Text(statusLabel(entry.status, entry.prio), color = statusColor(entry.status), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(entry.message ?: "(no message)")
                Spacer(Modifier.height(10.dp))
                KeyValue("status", entry.status.toString())
                KeyValue("sent", fullDateTime(entry.createdAt))
                KeyValue("received", entry.receivedAt?.let(::fullDateTime) ?: "—")
                KeyValue("encrypted", if (entry.encrypted) {
                    "yes" + (entry.keyBits?.let { " · $it bits" } ?: "")
                } else {
                    "no"
                })
                KeyValue("page id", entry.id)
                entry.project?.let { KeyValue("project", it) }
                entry.prio?.let { KeyValue("level", it) }
                entry.fromName?.let { KeyValue("from", it) }
                if (!entry.ackUuid.isNullOrBlank()) KeyValue("ack to", entry.ackUuid)
                if (!entry.replyUuid.isNullOrBlank()) KeyValue("ack via ProLOG", entry.replyUuid)
                if (entry.acked) {
                    Spacer(Modifier.height(8.dp))
                    Text("Already acknowledged", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("optional note with your ACK") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            val canAck = entry.delivered && !entry.acked
            TextButton(enabled = canAck, onClick = { onAck(note) }) { Text("ACK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            key,
            modifier = Modifier.width(72.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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

private fun statusLabel(status: Int, prio: String? = null): String {
    val level = prio?.trim()?.lowercase()?.takeIf {
        it in setOf("debug", "info", "warning", "alarm", "critical")
    }
    if (level != null) {
        return "$status ${level.replaceFirstChar { it.uppercase() }}"
    }
    return when (status) {
        0 -> "0 Success"
        1 -> "1 Warning"
        2 -> "2 Failed"
        3 -> "3 Critical"
        4 -> "4 In Progress"
        5 -> "5 Queued"
        6 -> "6 Acknowledged"
        7 -> "7 Debug"
        else -> status.toString()
    }
}

@Composable
private fun statusColor(status: Int) = when (status) {
    2, 3 -> MaterialTheme.colorScheme.error
    6 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
