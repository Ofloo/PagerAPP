package io.ofloo.pagerapp.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.ofloo.pagerapp.data.HistoryEntry
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.shared.Contact
import io.ofloo.pagerapp.shared.ContactKind
import io.ofloo.pagerapp.shared.ContactQr
import io.ofloo.pagerapp.shared.STATUS_MESSAGE
import io.ofloo.pagerapp.shared.buildOutgoing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Contacts, the private way: each contact gets their **own channel mailbox**, so sharing never
 * exposes the private inbox where your systems send their pages. Spam on a channel is solved by
 * dropping the contact and inviting again.
 */
enum class NfcMode { NONE, READ, WRITE }

@Composable
fun ContactsScreen(store: Store, onOpenChat: (Contact) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(store.displayName) }
    var contacts by remember { mutableStateOf(store.contacts()) }
    var inviteQr by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var sendTo by remember { mutableStateOf<Contact?>(null) }
    var renameTarget by remember { mutableStateOf<Contact?>(null) }
    val config = store.config

    val activity = context.findActivity()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var nfcMode by remember { mutableStateOf(NfcMode.NONE) }

    fun refresh() { contacts = store.contacts() }

    // Leave reader mode when the screen goes away.
    DisposableEffect(activity) {
        onDispose { activity?.let { NfcShare.stopReader(it) } }
    }

    /** Adds a contact from a QR or NFC payload. */
    fun addFromPayload(scanned: String, mySenderId: String) {
        val contact = ContactQr.decode(
            text = scanned,
            fallbackServer = store.serverUrl,
            mySenderId = mySenderId,
        )
        if (contact != null) {
            store.upsertContact(contact)
            refresh()
            status = "Added ${contact.name.ifBlank { contact.uuid.take(8) }}"
        } else {
            status = "Could not read that contact code"
        }
    }

    // A contact tag tapped while the app was closed is waiting for us.
    LaunchedEffect(Unit) {
        val pending = store.pendingContact
        if (!pending.isNullOrBlank()) {
            store.pendingContact = null
            addFromPayload(pending, UUID.randomUUID().toString())
        }
    }

    /** Reads a contact from an NFC tag, or writes ours to one. */
    fun startNfc(mode: NfcMode, payloadToWrite: String? = null) {
        val act = activity
        if (act == null) { status = "NFC needs the app in the foreground."; return }
        if (!NfcShare.isAvailable(context)) { status = "NFC is unavailable or switched off."; return }
        nfcMode = mode
        val started = NfcShare.startReader(act) { tag ->
            when (mode) {
                NfcMode.READ -> {
                    val text = NfcShare.readText(tag)
                    mainHandler.post {
                        nfcMode = NfcMode.NONE
                        if (text != null) addFromPayload(text, UUID.randomUUID().toString())
                        else status = "That tag has no PagerAPP contact on it."
                    }
                    true
                }
                NfcMode.WRITE -> {
                    val reason = payloadToWrite?.let { NfcShare.writeTag(tag, it) }
                    mainHandler.post {
                        nfcMode = NfcMode.NONE
                        status = reason ?: "Written to the tag."
                    }
                    true
                }
                NfcMode.NONE -> false
            }
        }
        if (!started) { nfcMode = NfcMode.NONE; status = "Could not start NFC." }
    }

    // Keep the list (and each contact's recent messages) live as pages arrive.
    DisposableEffect(store) {
        val main = Handler(Looper.getMainLooper())
        val listener = store.registerListener(Store.CONTACT_KEYS + Store.HISTORY_KEYS) {
            main.post { refresh() }
        }
        onDispose { store.unregisterListener(listener) }
    }

    val scanner = rememberQrScanner(
        prompt = "Scan a PagerAPP contact QR code",
        onResult = { scanned -> addFromPayload(scanned, UUID.randomUUID().toString()) },
        onDenied = { status = "Camera permission is needed to scan QR codes." },
    )

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; store.displayName = it },
                        label = { Text("your name (shown to contacts)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                status = null
                                scope.launch {
                                    // Two mailboxes per chat: one we read on, one prepared for them.
                                    val pair = withContext(Dispatchers.IO) {
                                        runCatching {
                                            PagerClient.createMailbox(store.serverUrl) to
                                                PagerClient.createMailbox(store.serverUrl)
                                        }.getOrNull()
                                    }
                                    if (pair == null) {
                                        status = "Could not create the mailboxes (server unreachable?)"
                                    } else {
                                        val (mine, theirs) = pair
                                        val invite = Contact(
                                            name = store.displayName,
                                            serverUrl = store.serverUrl,
                                            uuid = mine,
                                            senderId = "",
                                            mySenderId = UUID.randomUUID().toString(),
                                            sendTo = theirs,
                                            receiveOn = mine,
                                        )
                                        store.upsertContact(invite)
                                        refresh()
                                        inviteQr = ContactQr.encode(invite)
                                    }
                                    busy = false
                                }
                            },
                        ) { Text(if (busy) "Creating…" else "Invite a contact") }
                        OutlinedButton(onClick = { scanner() }, enabled = !busy) { Text("Scan contact") }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { startNfc(NfcMode.READ) },
                            enabled = !busy && nfcMode == NfcMode.NONE,
                        ) { Text("Scan NFC tag") }
                        if (nfcMode != NfcMode.NONE) {
                            Text(
                                if (nfcMode == NfcMode.READ) "Hold a tag to the phone…"
                                else "Hold a blank tag to the phone…",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Text(
                        "Each contact gets their own channel mailbox. Your private mailbox — where " +
                            "your systems send pages — is never shared.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
        item { Text("Contacts", fontWeight = FontWeight.Bold) }
        item { Spacer(Modifier.height(4.dp)) }

        if (contacts.isEmpty()) {
            item {
                Text(
                    "No contacts yet. Invite someone and let them scan your QR, or scan theirs.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(contacts, key = { it.id }) { contact ->
            val inbound = store.history().filter { it.from == contact.senderId && it.from.isNotBlank() }
                .takeLast(3)
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        contact.name.ifBlank { "(unnamed)" } +
                            if (contact.awaitingReply) " · waiting for their first reply" else "",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        contact.uuid,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Picker(
                            label = "type",
                            value = contact.kind,
                            options = ContactKind.entries,
                            display = { if (it == ContactKind.PERSON) "person (chat)" else "system (alarms)" },
                            onSelect = { store.upsertContact(contact.copy(kind = it)); refresh() },
                        )
                        if (contact.kind == ContactKind.PERSON) {
                            val levels = config.chatLevels.keys.toList()
                            if (levels.isNotEmpty()) {
                                Picker(
                                    label = "level",
                                    value = contact.level,
                                    options = levels,
                                    display = { it },
                                    onSelect = {
                                        store.upsertContact(contact.copy(level = it)); refresh()
                                    },
                                )
                            }
                        }
                    }
                    if (inbound.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        inbound.forEach { m -> Text("• ${m.message ?: "(no text)"}", fontSize = 13.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (contact.kind == ContactKind.PERSON) {
                            Button(onClick = { onOpenChat(contact) }) { Text("Open chat") }
                        } else {
                            Button(onClick = { sendTo = contact }) { Text("Send") }
                        }
                        TextButton(onClick = { renameTarget = contact }) { Text("Rename") }
                        TextButton(onClick = { store.removeContact(contact.id); refresh() }) {
                            Text("Delete")
                        }
                    }
                    if (contact.kind == ContactKind.SYSTEM) {
                        Text(
                            "System contacts always need a manual ACK, so you know it was seen.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        item {
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    inviteQr?.let { payload ->
        AlertDialog(
            onDismissRequest = { inviteQr = null },
            confirmButton = { TextButton(onClick = { inviteQr = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    inviteQr = null
                    startNfc(NfcMode.WRITE, payload)
                }) { Text("Write to NFC tag") }
            },
            title = { Text("Let them scan this") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bitmap = qrBitmap(payload), contentDescription = "Contact QR code")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "They open PagerAPP → Contacts → Scan contact (QR or NFC). " +
                            "Their reply appears here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            },
        )
    }

    sendTo?.let { contact ->
        SendDialog(
            contact = contact,
            onDismiss = { sendTo = null },
            onSend = { text ->
                sendTo = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val payload = buildOutgoing(contact, store.displayName, text)
                            PagerClient.notify(store.serverUrl, contact.outbox, payload)
                            true
                        }.getOrDefault(false)
                    }
                    status = if (ok) "Sent to ${contact.name.ifBlank { contact.uuid.take(8) }}"
                    else "Send failed (offline or server?)"
                }
            },
        )
    }

    renameTarget?.let { contact ->
        var newName by remember(contact.id) { mutableStateOf(contact.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename contact") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.upsertContact(contact.copy(name = newName.trim()))
                    renameTarget = null
                    refresh()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SendDialog(contact: Contact, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to ${contact.name.ifBlank { contact.uuid.take(8) }}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("message") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSend(text.trim()) }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
