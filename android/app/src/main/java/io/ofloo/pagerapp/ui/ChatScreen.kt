package io.ofloo.pagerapp.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ofloo.pagerapp.data.HistoryEntry
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.poll.Poller
import io.ofloo.pagerapp.shared.Contact
import io.ofloo.pagerapp.shared.STATUS_MESSAGE
import io.ofloo.pagerapp.shared.buildOutgoing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The conversation with one person, WhatsApp-shaped: a header with their name, a scrolling thread
 * of bubbles (ours right, theirs left) that sticks to the newest message, and an input row that
 * rides above the keyboard.
 *
 * Enter sends; reading is the acknowledgement, so the app acks their messages as it shows them.
 */
@Composable
fun ChatScreen(contact: Contact, store: Store, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var revision by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }

    // Live updates: incoming pages and our own sends both land here.
    DisposableEffect(store) {
        val main = Handler(Looper.getMainLooper())
        val listener = store.registerListener(Store.HISTORY_KEYS) { main.post { revision++ } }
        onDispose { store.unregisterListener(listener) }
    }

    val thread = remember(revision) {
        store.history()
            .filter { it.from == contact.senderId || it.from == contact.mySenderId }
            .sortedBy { it.createdAt }
    }

    // An open conversation is the one place latency matters — you are talking. Poll its mailbox
    // every couple of seconds rather than trusting a stream, which proved unreliable on some
    // devices; a single mailbox polled this way costs almost nothing.
    LaunchedEffect(contact.id) {
        while (true) {
            withContext(Dispatchers.IO) {
                runCatching {
                    Poller.pollMailbox(
                        context,
                        mailbox = contact.inbox,
                        serverUrl = contact.serverUrl,
                        contact = contact,
                    )
                }
            }
            delay(2_000)
        }
    }

    // Reading the peer's messages is the acknowledgement.
    LaunchedEffect(thread) {
        thread.filter { it.from == contact.senderId && !it.acked }.forEach { store.markAcked(it.id) }
    }
    // Stick to the newest message, like a messenger should.
    LaunchedEffect(thread.size) {
        if (thread.isNotEmpty()) listState.scrollToItem(thread.lastIndex)
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        text = ""
        failure = null
        scope.launch {
            // Build the payload first so we can record what actually went out: if it carries an
            // `enc` body it was encrypted, and our own bubble must not claim otherwise.
            val payload = buildOutgoing(
                contact,
                store.displayName,
                body,
                // Our key rides along so the peer can answer encrypted from the start.
                myPublicArmored = store.ensureOwnKeys().publicArmored,
            )
            val wasEncrypted = payload["enc"] != null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PagerClient.notify(store.serverUrl, contact.outbox, payload)
                    true
                }
            }
            if (result.isSuccess) {
                store.addHistory(
                    listOf(
                        HistoryEntry(
                            id = "out-${UUID.randomUUID()}",
                            status = STATUS_MESSAGE,
                            jobId = null,
                            project = null,
                            message = body,
                            createdAt = System.currentTimeMillis() / 1000.0,
                            ackUuid = null,
                            delivered = true,
                            from = contact.mySenderId,
                            fromName = store.displayName,
                            acked = true,
                            encrypted = wasEncrypted,
                        ),
                    ),
                )
                revision++          // show it right away, don't wait for a poll
            } else {
                // Show the actual reason: "offline" was misleading when key generation failed.
                val cause = result.exceptionOrNull()
                failure = "Not sent: ${cause?.message ?: cause?.javaClass?.simpleName ?: "unknown error"}"
            }
        }
    }

    // imePadding() is the correct fix once the activity is edge-to-edge: the window no longer
    // resizes, so the keyboard inset has to move the input row up.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // Header: back, name, and the honest status of this chat's keys.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text(contact.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        contact.awaitingReply -> "waiting for their first message"
                        !contact.publicKey.isBlank() -> "encrypted · key known"
                        else -> "not encrypted yet · exchange not started"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            items(thread, key = { it.id }) { entry ->
                if (entry.notice) {
                    // Protocol notices sit between the bubbles, centred and quiet.
                    Text(
                        entry.message ?: "",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                    return@items
                }
                val mine = entry.from == contact.mySenderId
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(entry.message ?: "(no text)")
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildString {
                                    // Warn only about what we did not send ourselves: an incoming
                                    // plaintext message in a chat that has keys is the anomaly;
                                    // our own plaintext is just a peer who lacks our key yet.
                                    if (entry.encrypted) {
                                        append("🔒 ")
                                    } else if (!mine && contact.publicKey.isNotBlank() && !entry.notice) {
                                        append("⚠️ not encrypted · ")
                                    }
                                    append(timeOf(entry.createdAt))
                                    if (mine) append("  ").append(if (entry.acked) "✓✓" else "✓")
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        failure?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }

        // No send button: Enter sends (the IME's action key too), and Shift+Enter inserts a
        // newline, so the field keeps the full width.
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Message") },
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(8.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        if (event.isShiftPressed) false   // Shift+Enter: newline
                        else {
                            send()                        // Enter: send
                            true
                        }
                    } else {
                        false
                    }
                },
        )
    }
}

private fun timeOf(epochSeconds: Double): String =
    if (epochSeconds <= 0) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((epochSeconds * 1000).toLong()))
