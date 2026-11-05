package io.ofloo.pagerapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import io.ofloo.pagerapp.data.HistoryEntry
import io.ofloo.pagerapp.data.QueuedSend
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.notify.RepeatAlerts
import io.ofloo.pagerapp.p2p.BleP2P
import io.ofloo.pagerapp.p2p.P2PService
import io.ofloo.pagerapp.poll.Poller
import io.ofloo.pagerapp.shared.P2PEnvelope
import io.ofloo.pagerapp.shared.p2pRouteHash
import androidx.core.content.FileProvider
import io.ofloo.pagerapp.shared.Contact
import io.ofloo.pagerapp.shared.FileLink
import io.ofloo.pagerapp.shared.PhotoLink
import io.ofloo.pagerapp.shared.Pgp
import io.ofloo.pagerapp.shared.STATUS_MESSAGE
import io.ofloo.pagerapp.shared.buildOutgoing
import io.ofloo.pagerapp.shared.buildReadReceipt
import io.ofloo.pagerapp.shared.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The conversation with one person, WhatsApp-shaped: a header with their name, a scrolling thread
 * of bubbles (ours right, theirs left) that sticks to the newest message, and an input row that
 * rides above the keyboard.
 *
 * Enter sends; reading is the acknowledgement, so the app acks their messages as it shows them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    contact: Contact,
    store: Store,
    onBack: () -> Unit,
    initialShare: SharePayload? = null,
    onShareConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val inputInteraction = remember { MutableInteractionSource() }
    var revision by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var fullscreenPhoto by remember { mutableStateOf<String?>(null) }
    var actionEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    var replyEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    var showArchiveConfirm by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }

    // System back / gesture navigation: close an open overlay first, otherwise return to the
    // conversation list. Without this the activity is finished and the app is detached.
    BackHandler(enabled = fullscreenPhoto != null) { fullscreenPhoto = null }
    BackHandler(enabled = actionEntry != null) { actionEntry = null }
    BackHandler(enabled = deleteEntry != null) { deleteEntry = null }
    BackHandler(enabled = showArchiveConfirm) { showArchiveConfirm = false }
    BackHandler(
        enabled = fullscreenPhoto == null && actionEntry == null &&
            deleteEntry == null && !showArchiveConfirm,
    ) { onBack() }

    // Live updates: incoming pages and our own sends both land here.
    DisposableEffect(store) {
        val main = Handler(Looper.getMainLooper())
        val listener = store.registerListener(Store.HISTORY_KEYS) { main.post { revision++ } }
        onDispose { store.unregisterListener(listener) }
    }

    // The contact record can change while the chat is open (first reply, learned key). Keep a
    // live view so sending encrypts with the key we just learned and the header stops saying
    // "exchange not started" after the first message.
    val liveContact = remember(revision, contact.id) {
        store.contacts().firstOrNull { it.id == contact.id } ?: contact
    }
    LaunchedEffect(liveContact.id) { replyEntry = null }
    val thread = remember(revision, liveContact.senderId, liveContact.mySenderId) {
        store.history()
            .filter { it.from == liveContact.senderId || it.from == liveContact.mySenderId }
            // Older builds stored delivery receipts as empty, unsigned chat messages. They are
            // transport metadata, not conversation: hide those legacy artifacts.
            .filterNot {
                it.message.isNullOrBlank() && it.imagePath == null && it.filePath == null &&
                    it.logs == null && !it.notice
            }
            .sortedBy { it.createdAt }
            .distinctBy { it.id }
    }
    // A queued bubble turns from a clock into an envelope once the outbox flushes. Clear the
    // explanatory banner at the same moment; stale "queued" text looked like a send failure.
    LaunchedEffect(thread) {
        if (failure?.startsWith("Queued") == true && thread.none { it.pending }) failure = null
    }
    val latestCount by rememberUpdatedState(thread.size)

    // Tapping the input raises the keyboard; bring the newest bubble back into view as the
    // window shrinks. Without this the thread only followed a new message, not the IME.
    LaunchedEffect(inputInteraction) {
        inputInteraction.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release && latestCount > 0) {
                delay(300)
                listState.animateScrollToItem(latestCount - 1)
            }
        }
    }

    // An open conversation is the one place latency matters — you are talking. Poll its mailbox
    // every couple of seconds rather than trusting a stream, which proved unreliable on some
    // devices; a single mailbox polled this way costs almost nothing.
    LaunchedEffect(liveContact.id, liveContact.inbox, liveContact.serverUrl) {
        while (true) {
            val current = store.contacts().firstOrNull { it.id == liveContact.id } ?: liveContact
            withContext(Dispatchers.IO) {
                runCatching {
                    Poller.pollMailbox(
                        context,
                        mailbox = current.inbox,
                        serverUrl = current.serverUrl,
                        contact = current,
                    )
                }
            }
            delay(2_000)
        }
    }

    // Reading the peer's messages is the acknowledgement. Mark them read and send a receipt for
    // messages that carry a mid, so the peer can turn their ✓ into ✓✓ only then.
    LaunchedEffect(thread) {
        thread.filter { it.from == liveContact.senderId && !it.acked }.forEach { entry ->
            store.markAcked(entry.id)
            val mid = entry.mid
            if (!mid.isNullOrBlank()) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            PagerClient.notify(
                                liveContact.serverUrl,
                                liveContact.outbox,
                                buildReadReceipt(liveContact, mid, store.displayName),
                            )
                        }
                    }
                }
            }
        }
    }
    // Stick to the newest message, like a messenger should.
    LaunchedEffect(thread.size) {
        if (thread.isNotEmpty()) listState.scrollToItem(thread.lastIndex)
    }

    fun sendText(
        body: String,
        imagePath: String? = null,
        filePath: String? = null,
        fileName: String? = null,
        fileMime: String? = null,
    ) {
        failure = null
        scope.launch {
            // Build the payload first so we can record what actually went out: if it carries an
            // `enc` body it was encrypted, and our own bubble must not claim otherwise.
            val mid = UUID.randomUUID().toString()
            val historyId = "out-${UUID.randomUUID()}"
            val replyingTo = replyEntry
            // Key generation, signing and encryption are CPU-heavy; never run them on the main
            // thread or the first send on a fresh install ANRs.
            val ownKeys = withContext(Dispatchers.IO) { store.ensureOwnKeys() }
            val payload = withContext(Dispatchers.IO) {
                buildOutgoing(
                    liveContact,
                    store.displayName,
                    body,
                    // Our key rides along so the peer can answer encrypted from the start.
                    myPublicArmored = ownKeys.publicArmored,
                    mid = mid,
                    mySecretArmored = ownKeys.secretArmored,
                )
            }.toMutableMap().apply {
                // Explicit routing metadata: the peer-visible sender and destination mailbox.
                put("source", liveContact.mySenderId)
                put("destination", liveContact.outbox)
                if (replyingTo != null) {
                    put("reply_to", replyingTo.mid ?: replyingTo.id)
                    put("reply_preview", replyPreviewOf(replyingTo))
                    put("reply_from", replyingTo.from)
                    put("reply_from_name", replyingTo.fromName ?: liveContact.name)
                }
            }
            val localDelivery = store.isLocalMailbox(liveContact.outbox)
            // Do not tag locally composed messages as P2P. The p2p flag belongs to a received
            // P2P envelope; a gateway adds it while forwarding that envelope to PagerMCP.
            val wasEncrypted = payload["enc"] != null
            val keyBits = if (wasEncrypted) Pgp.bitsOf(liveContact.publicKey) else null
            val result = if (localDelivery) {
                Result.success(true)
            } else withContext(Dispatchers.IO) {
                runCatching {
                    PagerClient.notify(liveContact.serverUrl, liveContact.outbox, payload)
                    // Keep a self-addressed back-channel copy while a browser session is active.
                    // It is encrypted to our own key, so the phone and web can both read it.
                    if (store.webSessionActive() && !store.uuid.isNullOrBlank()) {
                        // This is a secondary sync copy. If it fails, the peer message was still
                        // sent, so do not turn the whole send into a failure.
                        runCatching {
                            val selfCopy = linkedMapOf<String, Any?>(
                                "status" to STATUS_MESSAGE,
                                "from" to liveContact.mySenderId,
                                "from_name" to store.displayName,
                                "mid" to mid,
                                "source" to liveContact.mySenderId,
                                "destination" to store.uuid,
                                "self_copy" to true,
                            )
                            if (replyingTo != null) {
                                selfCopy["reply_to"] = replyingTo.mid ?: replyingTo.id
                                selfCopy["reply_preview"] = replyPreviewOf(replyingTo)
                                selfCopy["reply_from"] = replyingTo.from
                                selfCopy["reply_from_name"] = replyingTo.fromName ?: liveContact.name
                            }
                            runCatching { Pgp.sign(body, ownKeys.secretArmored) }
                                .onSuccess { selfCopy["sig"] = it }
                            runCatching { Pgp.encrypt(body, ownKeys.publicArmored) }
                                .onSuccess { selfCopy["enc"] = it }
                                .onFailure { selfCopy["message"] = body }
                            PagerClient.notify(store.serverUrl, store.uuid!!, selfCopy)
                        }
                    }
                    true
                }
            }
            val createdAt = System.currentTimeMillis() / 1000.0
            val optimistic = HistoryEntry(
                id = historyId,
                status = STATUS_MESSAGE,
                jobId = null,
                project = null,
                message = body,
                createdAt = createdAt,
                ackUuid = null,
                mailbox = liveContact.inbox,
                serverUrl = liveContact.serverUrl,
                imagePath = imagePath,
                mid = mid,
                filePath = filePath,
                fileName = fileName,
                fileMime = fileMime,
                keyBits = keyBits,
                delivered = result.isSuccess,
                pending = result.isFailure,
                from = liveContact.mySenderId,
                fromName = store.displayName,
                acked = false,
                encrypted = wasEncrypted,
                sig = payload["sig"] as? String,
                signatureValid = !(payload["sig"] as? String).isNullOrBlank(),
                replyTo = replyingTo?.mid ?: replyingTo?.id,
                replyPreview = replyingTo?.let(::replyPreviewOf),
                replyFrom = replyingTo?.from,
                replyFromName = replyingTo?.fromName ?: liveContact.name,
            )
            // Record the bubble before queueing it. The gateway may be unreachable now, but the
            // queue and the optimistic history row both survive a process restart.
            store.addHistory(listOf(optimistic))
            if (result.isFailure) {
                if (store.p2pModeEnabled) {
                    val maxAge = store.p2pMaxAgeSeconds.takeIf { it > 0L } ?: 604800L
                    store.enqueueP2P(
                        P2PEnvelope(
                            id = mid,
                            from = store.ownRouteHash(),
                            to = p2pRouteHash(liveContact.outbox),
                            createdAt = createdAt.toLong(),
                            expiresAt = createdAt.toLong() + maxAge,
                            payload = JSONObject(payload).toString(),
                        ),
                    )
                    P2PService.start(context)
                    BleP2P.queueChanged()
                } else {
                    store.enqueueSend(
                        QueuedSend(
                            id = historyId,
                            historyId = historyId,
                            serverUrl = liveContact.serverUrl,
                            mailbox = liveContact.outbox,
                            payloadJson = JSONObject(payload).toString(),
                            createdAt = createdAt,
                        ),
                    )
                }
            }
            replyEntry = null
            revision++
            if (result.isFailure) {
                failure = if (store.p2pModeEnabled) {
                    "Queued for nearby P2P — waiting for a peer."
                } else {
                    "Queued — will send when the gateway is reachable."
                }
            }
        }
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        text = ""
        sendText(body)
    }

    /** GPG-encrypts the photo, uploads it to the configured share host and sends the link. */
    fun sharePhoto(uri: Uri) {
        failure = null
        if (liveContact.publicKey.isBlank()) {
            failure = "Send a text message first so their key is known; photos are always encrypted."
            return
        }
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val jpeg = ChatPhotos.compress(
                        context,
                        uri,
                        ChatPhotos.MAX_LINK_BYTES,
                        maxDimension = 4096,
                    ) ?: error("could not prepare the photo")
                    val armor = Pgp.encryptBytes(jpeg, liveContact.publicKey)
                    val shareHost = PhotoShare.baseUrl(store.photoShareUrl)
                    val upload = PhotoShare.upload(shareHost, armor)
                        ?: error("upload to $shareHost failed")
                    val days = PhotoShare.retentionDays(shareHost)
                    val imageId = UUID.randomUUID().toString()
                    val path = ChatPhotos.saveImage(context, "image-$imageId", jpeg)
                    Triple(
                        PhotoLink(upload.url, sha256Hex(armor), days, upload.deleteUrl),
                        path,
                        imageId,
                    )
                }
            }
            val (link, path, _) = prepared.getOrNull() ?: run {
                failure = "Photo could not be shared: ${prepared.exceptionOrNull()?.message ?: "unknown error"}"
                return@launch
            }
            sendText(link.encode(), imagePath = path)
        }
    }

    /** Encrypts any file, uploads it to the configured share host and sends the link. */
    fun shareAttachment(uri: Uri) {
        failure = null
        if (liveContact.publicKey.isBlank()) {
            failure = "Send a text message first so their key is known; attachments are always encrypted."
            return
        }
        scope.launch {
            val mime = withContext(Dispatchers.IO) {
                context.contentResolver.getType(uri) ?: "application/octet-stream"
            }
            if (mime.startsWith("image/")) {
                sharePhoto(uri)
                return@launch
            }
            val attachment = withContext(Dispatchers.IO) { ChatPhotos.readAttachment(context, uri) }
            if (attachment == null) {
                failure = "Attachment could not be read (or is larger than 20 MB)."
                return@launch
            }
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val armor = Pgp.encryptBytes(attachment.bytes, liveContact.publicKey)
                    val shareHost = PhotoShare.baseUrl(store.photoShareUrl)
                    val upload = PhotoShare.upload(shareHost, armor)
                        ?: error("upload to $shareHost failed")
                    val days = PhotoShare.retentionDays(shareHost)
                    val link = FileLink(
                        upload.url,
                        sha256Hex(armor),
                        days,
                        attachment.name,
                        attachment.mime,
                        upload.deleteUrl,
                    )
                    val path = ChatPhotos.saveAttachment(
                        context,
                        "file-${UUID.randomUUID()}",
                        attachment.name,
                        attachment.bytes,
                    )
                    link to path
                }
            }
            val (link, path) = prepared.getOrNull() ?: run {
                failure = "Attachment could not be shared: " +
                    (prepared.exceptionOrNull()?.message ?: "unknown error")
                return@launch
            }
            sendText(
                link.encode(),
                filePath = path,
                fileName = attachment.name,
                fileMime = attachment.mime,
            )
        }
    }

    /** Retry for a share file link when the automatic download failed. */
    fun downloadSharedFile(entry: HistoryEntry, link: FileLink) {
        if (downloading == entry.id) return
        downloading = entry.id
        failure = null
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val armor = PhotoShare.download(store.photoShareUrl, link.url, link.sha256)
                        ?: error("download failed")
                    val secret = store.ownSecretKey.ifBlank { null } ?: error("no secret key")
                    val bytes = Pgp.decryptBytes(armor, secret)
                    if (bytes.isEmpty() || bytes.size > ChatPhotos.MAX_ATTACHMENT_BYTES) {
                        error("file too large")
                    }
                    val path = ChatPhotos.saveAttachment(
                        context,
                        "file-${entry.id}",
                        link.name,
                        bytes,
                    ) ?: error("save failed")
                    if (!store.webSessionActive()) PhotoShare.delete(store.photoShareUrl, link.deleteUrl)
                    Triple(path, link.name, link.mime)
                }.getOrNull()
            }
            if (file != null) {
                store.attachFile(entry.id, file.first, file.second, file.third)
                revision++
            } else {
                failure = "Attachment could not be downloaded (expired, unreachable or not decryptable)."
            }
            downloading = null
        }
    }

    /** Retry for a share link when the automatic download failed. */
    fun downloadShared(entry: HistoryEntry, link: PhotoLink) {
        if (downloading == entry.id) return
        downloading = entry.id
        failure = null
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val armor = PhotoShare.download(store.photoShareUrl, link.url, link.sha256)
                        ?: error("download failed")
                    val secret = store.ownSecretKey.ifBlank { null } ?: error("no secret key")
                    val jpeg = Pgp.decryptBytes(armor, secret)
                    val path = ChatPhotos.saveImage(context, "image-${entry.id}", jpeg)
                        ?: error("save failed")
                    if (!store.webSessionActive()) PhotoShare.delete(store.photoShareUrl, link.deleteUrl)
                    path
                }.getOrNull()
            }
            if (path != null) {
                store.attachImage(entry.id, path)
                revision++
            } else {
                failure = "Photo could not be downloaded (expired, unreachable or not decryptable)."
            }
            downloading = null
        }
    }

    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        val file = cameraFile
        cameraUri = null
        cameraFile = null
        if (ok && uri != null && (file?.length() ?: 0L) > 0L) {
            sharePhoto(uri)
        } else {
            file?.delete()
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) shareAttachment(uri)
    }

    // A share from another app opened this chat: send text, an image or a file immediately.
    // Consume only after the (possibly suspending) URI lookup: clearing initialShare first would
    // cancel this effect and silently drop a screenshot/photo sent through the share sheet.
    LaunchedEffect(initialShare) {
        val share = initialShare ?: return@LaunchedEffect
        val uri = share.uri
        if (uri != null) {
            val mime = withContext(Dispatchers.IO) {
                context.contentResolver.getType(uri)
            } ?: share.mime
            android.util.Log.i("PagerAPP", "shared stream: type=$mime uri=$uri")
            if (mime?.startsWith("image/") == true) sharePhoto(uri) else shareAttachment(uri)
        } else if (!share.text.isNullOrBlank()) {
            sendText(share.text)
        }
        onShareConsumed()
    }

    fun launchCamera() {
        failure = null
        runCatching {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "photo-${UUID.randomUUID()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            cameraFile = file
            cameraUri = uri
            cameraPicker.launch(uri)
        }.onFailure {
            failure = "Camera unavailable: ${it.message}"
            cameraFile?.delete()
            cameraFile = null
            cameraUri = null
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
            Column(modifier = Modifier.weight(1f)) {
                Text(liveContact.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        liveContact.awaitingReply -> "waiting for their first message"
                        !liveContact.publicKey.isBlank() -> "encrypted · key known"
                        else -> "not encrypted yet · exchange not started"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showArchiveConfirm = true }) {
                Icon(Icons.Default.Archive, contentDescription = "Archive chat")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            itemsIndexed(thread, key = { _, entry -> entry.id }) { index, entry ->
                val previous = thread.getOrNull(index - 1)
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (previous == null || dayKeyOf(previous.createdAt) != dayKeyOf(entry.createdAt)) {
                        DaySeparator(entry.createdAt)
                    }
                    if (entry.notice) {
                        // Protocol notices sit between the bubbles, centred and quiet.
                        Text(
                            entry.message ?: "",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        val mine = entry.from == liveContact.mySenderId
                        var dragOffset by remember(entry.id) { mutableFloatStateOf(0f) }
                        var replyArmed by remember(entry.id) { mutableStateOf(false) }
                        val replyThreshold = with(LocalDensity.current) { 72.dp.toPx() }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                        ) {
                            val entryImagePath = entry.imagePath
                            if (dragOffset > 0f) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = "Reply",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .alpha((dragOffset / replyThreshold).coerceIn(0f, 1f))
                                        .padding(horizontal = 6.dp),
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (mine) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .offset { IntOffset(dragOffset.roundToInt(), 0) }
                                    .pointerInput(entry.id) {
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { change, dragAmount ->
                                                if (dragAmount > 0f || dragOffset > 0f) {
                                                    dragOffset = (dragOffset + dragAmount)
                                                        .coerceIn(0f, replyThreshold * 1.25f)
                                                    if (!replyArmed && dragOffset >= replyThreshold) {
                                                        replyArmed = true
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    } else if (dragOffset < replyThreshold) {
                                                        replyArmed = false
                                                    }
                                                    change.consume()
                                                }
                                            },
                                            onDragEnd = {
                                                if (dragOffset >= replyThreshold) replyEntry = entry
                                                dragOffset = 0f
                                                replyArmed = false
                                            },
                                            onDragCancel = {
                                                dragOffset = 0f
                                                replyArmed = false
                                            },
                                        )
                                    }
                                    .widthIn(max = 300.dp)
                                    .combinedClickable(
                                        onClick = {
                                            if (entryImagePath != null) fullscreenPhoto = entryImagePath
                                        },
                                        onLongClick = { actionEntry = entry },
                                    ),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    entry.replyTo?.let { replyId ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 4.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                                    RoundedCornerShape(6.dp),
                                                )
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                entry.replyFromName ?: "Reply",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                entry.replyPreview ?: "Message $replyId",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    val link = entry.message?.let { PhotoLink.parse(it) }
                                    val fileLink = entry.message?.let { FileLink.parse(it) }
                                    val imagePath = entry.imagePath
                                    val filePath = entry.filePath
                                    when {
                                        imagePath != null -> PhotoBubble(imagePath)
                                        filePath != null -> {
                                            val fileName = entry.fileName ?: fileLink?.name ?: "Attachment"
                                            val fileMime = entry.fileMime ?: fileLink?.mime
                                                ?: "application/octet-stream"
                                            Column {
                                                Text(fileName, fontWeight = FontWeight.Bold)
                                                Text(
                                                    fileMime,
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Row {
                                                    TextButton(onClick = {
                                                        ChatPhotos.openAttachment(context, filePath, fileMime)
                                                    }) { Text(tr("Open")) }
                                                    TextButton(onClick = {
                                                        scope.launch {
                                                            val ok = withContext(Dispatchers.IO) {
                                                                ChatPhotos.saveAttachmentToDownloads(
                                                                    context,
                                                                    filePath,
                                                                    fileName,
                                                                    fileMime,
                                                                )
                                                            }
                                                            Toast.makeText(
                                                                context,
                                                                if (ok) {
                                                                    "Attachment saved"
                                                                } else {
                                                                    "Could not save attachment"
                                                                },
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                        }
                                                    }) { Text(tr("Save")) }
                                                }
                                            }
                                        }
                                        link != null -> Column {
                                            Text(tr("Photo link · ${link.days} day(s)"))
                                            TextButton(
                                                enabled = downloading != entry.id,
                                                onClick = { downloadShared(entry, link) },
                                            ) {
                                                Text(if (downloading == entry.id) "Downloading…" else "Download")
                                            }
                                        }
                                        fileLink != null -> Column {
                                            Text(tr("Attachment link · ${fileLink.days} day(s)"))
                                            Text(
                                                fileLink.name,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            TextButton(
                                                enabled = downloading != entry.id,
                                                onClick = { downloadSharedFile(entry, fileLink) },
                                            ) {
                                                Text(if (downloading == entry.id) "Downloading…" else "Download")
                                            }
                                        }
                                        else -> LinkedText(entry.message ?: "(no text)")
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val notEncrypted = !entry.encrypted && !mine &&
                                            liveContact.publicKey.isNotBlank() && !entry.notice
                                        // A message sent by this phone/web is signed with our own
                                        // key. Older web self-copies were stored as invalid before
                                        // they were verified against that key; trust a present sig.
                                        val cryptoSigned = entry.signatureValid == true ||
                                            (mine && !entry.sig.isNullOrBlank())
                                        val badSignature = entry.signatureValid == false && !mine
                                        val sendFailed = !entry.sendError.isNullOrBlank()
                                        Text(
                                            timeOf(entry.createdAt),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        val iconColor = if (entry.acked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (notEncrypted || badSignature || sendFailed) WarningIcon(iconColor)
                                            if (entry.encrypted || cryptoSigned) {
                                                CryptoStateIcon(
                                                    encrypted = entry.encrypted,
                                                    signed = cryptoSigned,
                                                    tint = iconColor,
                                                )
                                            }
                                            MailStateIcon(
                                                state = when {
                                                    entry.pending -> MailState.QUEUED
                                                    entry.acked -> MailState.READ
                                                    mine && entry.remoteDelivered -> MailState.DELIVERED
                                                    mine -> MailState.SENT
                                                    else -> MailState.DELIVERED
                                                },
                                                tint = iconColor,
                                            )
                                        }
                                        if (notEncrypted) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "not encrypted",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
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

        replyEntry?.let { target ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Replying to ${target.fromName ?: liveContact.name}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        replyPreviewOf(target),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { replyEntry = null }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel reply")
                }
            }
        }

        // No send button: Enter sends (the IME's action key too), and Shift+Enter inserts a
        // newline, so the field keeps the full width. The filled pill shape follows the
        // messenger convention; the border is transparent so only the surface reads.
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(tr("Message")) },
            maxLines = 6,
            shape = RoundedCornerShape(24.dp),
            interactionSource = inputInteraction,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            trailingIcon = {
                Row {
                    IconButton(onClick = { attachmentPicker.launch("*/*") }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Send attachment")
                    }
                    IconButton(onClick = { launchCamera() }) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = "Take photo")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
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

        fullscreenPhoto?.let { path ->
            PhotoViewerDialog(path = path, onDismiss = { fullscreenPhoto = null })
        }

        actionEntry?.let { entry ->
            MessageInfoDialog(
                entry = entry,
                onDismiss = { actionEntry = null },
                onReply = {
                    actionEntry = null
                    replyEntry = entry
                },
                onDelete = {
                    actionEntry = null
                    deleteEntry = entry
                },
            )
        }

        if (showArchiveConfirm) {
            AlertDialog(
                onDismissRequest = { showArchiveConfirm = false },
                title = { Text(tr("Archive chat?")) },
                text = {
                    Text(
                        "This clears the whole conversation on this device. The other side keeps " +
                            "their copy. New messages will still arrive here.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        thread.forEach { entry ->
                            entry.imagePath?.let { runCatching { File(it).delete() } }
                            entry.filePath?.let { runCatching { File(it).delete() } }
                        }
                        store.deleteThread(
                            senderId = liveContact.senderId,
                            mySenderId = liveContact.mySenderId,
                        )
                        showArchiveConfirm = false
                        onBack()
                    }) { Text(tr("Archive")) }
                },
                dismissButton = {
                    TextButton(onClick = { showArchiveConfirm = false }) { Text(tr("Cancel")) }
                },
            )
        }

        deleteEntry?.let { entry ->
            AlertDialog(
                onDismissRequest = { deleteEntry = null },
                title = { Text(tr("Delete message?")) },
                text = {
                    Text(
                        "This removes the message from this device only. The other side keeps " +
                            "their copy.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        entry.imagePath?.let { runCatching { File(it).delete() } }
                        entry.filePath?.let { runCatching { File(it).delete() } }
                        RepeatAlerts.remove(entry.id)
                        store.deleteEntry(entry.id)
                        revision++
                        deleteEntry = null
                    }) { Text(tr("Delete")) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteEntry = null }) { Text(tr("Cancel")) }
                },
            )
        }
    }
}

@Composable
private fun PhotoBubble(path: String) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { ChatPhotos.decodeSampled(path, 720)?.asImageBitmap() }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .heightIn(max = 260.dp)
                .padding(bottom = 4.dp),
        )
    } ?: Text(tr("Photo"), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PhotoViewerDialog(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { ChatPhotos.decodeSampled(path, 2048)?.asImageBitmap() }.getOrNull()
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            IconButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            ChatPhotos.saveToGallery(
                                context,
                                path,
                                "PagerAPP-${System.currentTimeMillis()}",
                            )
                        }
                        saving = false
                        Toast.makeText(
                            context,
                            if (saved) "Photo saved to gallery" else "Could not save photo",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
            }
        }
    }
}

@Composable
private fun MessageInfoDialog(
    entry: HistoryEntry,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val link = entry.message?.let { PhotoLink.parse(it) }
    val fileLink = entry.message?.let { FileLink.parse(it) }
    val copyText = when {
        link != null -> link.url
        fileLink != null -> fileLink.url
        !entry.message.isNullOrBlank() -> entry.message
        entry.filePath != null -> entry.filePath
        entry.imagePath != null -> entry.imagePath
        else -> ""
    }
    val type = when {
        entry.imagePath != null -> "Photo"
        entry.filePath != null -> "File"
        link != null -> "Photo link"
        fileLink != null -> "File link"
        else -> "Text"
    }
    val encryption = when {
        !entry.encrypted -> "no"
        entry.keyBits != null -> "yes · ${entry.keyBits} bits"
        else -> "yes"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.jobId ?: entry.project ?: "Message") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (entry.imagePath == null && link == null && !entry.message.isNullOrBlank()) {
                    LinkedText(entry.message ?: "")
                    Spacer(Modifier.height(6.dp))
                }
                InfoRow("sent", fullDateTime(entry.createdAt))
                InfoRow("received", entry.receivedAt?.let(::fullDateTime) ?: "—")
                InfoRow("type", type)
                InfoRow("encrypted", encryption)
                InfoRow("status", "${entry.status}" + (entry.prio?.let { " · $it" } ?: ""))
                if (entry.remoteDelivered) InfoRow("delivered", "yes")
                entry.sendError?.let { InfoRow("send error", it) }
                entry.signatureValid?.let {
                    InfoRow(
                        "signature",
                        when {
                            it -> "valid"
                            entry.sig == null -> "unsigned"
                            else -> "invalid"
                        },
                    )
                }
                entry.fromName?.let { InfoRow("from", it) }
                entry.from?.let { InfoRow("from id", it) }
                InfoRow("message id", entry.id)
                entry.jobId?.let { InfoRow("job", it) }
                entry.project?.let { InfoRow("project", it) }
                entry.ackUuid?.let { InfoRow("ack to", it) }
                entry.replyUuid?.let { InfoRow("ack via reply mailbox", it) }
                entry.fileName?.let { InfoRow("file name", it) }
                entry.fileMime?.let { InfoRow("file type", it) }
                entry.filePath?.let { InfoRow("file path", it) }
                entry.mailbox?.let { InfoRow("mailbox", it) }
                entry.serverUrl?.let { InfoRow("server", it) }
                if (entry.acked) InfoRow("acked", "yes")
                if (entry.archived) InfoRow("archived", "yes")
            }
        },
        confirmButton = {
            Row {
                if (copyText.isNotBlank()) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(tr("PagerAPP message"), copyText))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Text(tr("Copy")) }
                }
                TextButton(onClick = onReply) { Text(tr("Reply")) }
                TextButton(onClick = onDismiss) { Text(tr("Close")) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(tr("Delete"), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

private enum class MailState { SENT, DELIVERED, READ, QUEUED }

/** Drawn envelope: motion lines = under way, closed = delivered, open flap = read. */
@Composable
private fun MailStateIcon(state: MailState, tint: Color? = null) {
    val color = tint ?: if (state == MailState.READ) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(13.dp)) {
        val stroke = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round)
        fun closed() {
            drawPath(
                Path().apply {
                    moveTo(size.width * 0.08f, size.height * 0.30f)
                    lineTo(size.width * 0.92f, size.height * 0.30f)
                    lineTo(size.width * 0.92f, size.height * 0.86f)
                    lineTo(size.width * 0.08f, size.height * 0.86f)
                    close()
                },
                color,
                style = stroke,
            )
            drawPath(
                Path().apply {
                    moveTo(size.width * 0.08f, size.height * 0.30f)
                    lineTo(size.width * 0.50f, size.height * 0.62f)
                    lineTo(size.width * 0.92f, size.height * 0.30f)
                },
                color,
                style = stroke,
            )
        }
        when (state) {
            MailState.SENT -> {
                rotate(degrees = -14f, pivot = center) { closed() }
                drawLine(color, Offset(0f, size.height * 0.24f), Offset(size.width * 0.20f, size.height * 0.24f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(0f, size.height * 0.45f), Offset(size.width * 0.12f, size.height * 0.45f), stroke.width, StrokeCap.Round)
            }
            MailState.DELIVERED -> closed()
            MailState.QUEUED -> {
                drawCircle(color, radius = size.minDimension * 0.40f, center = center, style = stroke)
                drawLine(color, center, Offset(center.x, center.y - size.height * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(color, center, Offset(center.x + size.width * 0.22f, center.y), stroke.width, StrokeCap.Round)
            }
            MailState.READ -> {
                drawPath(
                    Path().apply {
                        moveTo(size.width * 0.08f, size.height * 0.42f)
                        lineTo(size.width * 0.08f, size.height * 0.86f)
                        lineTo(size.width * 0.92f, size.height * 0.86f)
                        lineTo(size.width * 0.92f, size.height * 0.42f)
                    },
                    color,
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(size.width * 0.08f, size.height * 0.42f)
                        lineTo(size.width * 0.50f, size.height * 0.08f)
                        lineTo(size.width * 0.92f, size.height * 0.42f)
                    },
                    color,
                    style = stroke,
                )
                drawLine(color, Offset(size.width * 0.08f, size.height * 0.42f), Offset(size.width * 0.92f, size.height * 0.42f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

/** A padlock, optionally accompanied by a feather for a signed message. */
@Composable
private fun CryptoStateIcon(encrypted: Boolean, signed: Boolean, tint: Color? = null) {
    val color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    val width = if (encrypted && signed) 21.dp else 13.dp
    Canvas(modifier = Modifier.width(width).height(13.dp)) {
        val stroke = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round)
        fun lock(x: Float, w: Float) {
            val bodyTop = size.height * 0.44f
            val bodyHeight = size.height * 0.48f
            drawRect(
                color,
                topLeft = Offset(x + w * 0.10f, bodyTop),
                size = Size(w * 0.80f, bodyHeight),
                style = stroke,
            )
            drawPath(
                Path().apply {
                    moveTo(x + w * 0.28f, bodyTop)
                    lineTo(x + w * 0.28f, size.height * 0.28f)
                    cubicTo(
                        x + w * 0.28f, size.height * 0.02f,
                        x + w * 0.72f, size.height * 0.02f,
                        x + w * 0.72f, size.height * 0.28f,
                    )
                    lineTo(x + w * 0.72f, bodyTop)
                },
                color,
                style = stroke,
            )
            drawCircle(
                color,
                radius = 0.9.dp.toPx(),
                center = Offset(x + w * 0.50f, bodyTop + bodyHeight * 0.48f),
            )
        }
        fun feather(x: Float, w: Float) {
            drawPath(
                Path().apply {
                    moveTo(x + w * 0.18f, size.height * 0.88f)
                    cubicTo(
                        x + w * 0.18f, size.height * 0.55f,
                        x + w * 0.48f, size.height * 0.28f,
                        x + w * 0.92f, size.height * 0.08f,
                    )
                    cubicTo(
                        x + w * 0.85f, size.height * 0.48f,
                        x + w * 0.55f, size.height * 0.75f,
                        x + w * 0.18f, size.height * 0.88f,
                    )
                    close()
                },
                color,
                style = stroke,
            )
            drawLine(
                color,
                Offset(x + w * 0.20f, size.height * 0.84f),
                Offset(x + w * 0.72f, size.height * 0.24f),
                stroke.width,
                StrokeCap.Round,
            )
        }
        val lockWidth = 13.dp.toPx()
        if (encrypted) lock(0f, lockWidth)
        if (signed) {
            feather(
                x = if (encrypted) lockWidth + 1.dp.toPx() else 0f,
                w = if (encrypted) size.width - lockWidth - 1.dp.toPx() else size.width,
            )
        }
    }
}

@Composable
private fun WarningIcon(tint: Color? = null) {
    val color = tint ?: MaterialTheme.colorScheme.error
    Canvas(modifier = Modifier.size(12.dp)) {
        val stroke = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round)
        drawPath(
            Path().apply {
                moveTo(size.width * 0.50f, size.height * 0.08f)
                lineTo(size.width * 0.94f, size.height * 0.90f)
                lineTo(size.width * 0.06f, size.height * 0.90f)
                close()
            },
            color,
            style = stroke,
        )
        drawLine(
            color,
            Offset(size.width * 0.50f, size.height * 0.36f),
            Offset(size.width * 0.50f, size.height * 0.64f),
            stroke.width,
            StrokeCap.Round,
        )
        drawCircle(
            color,
            radius = 0.8.dp.toPx(),
            center = Offset(size.width * 0.50f, size.height * 0.78f),
        )
    }
}

private fun replyPreviewOf(entry: HistoryEntry): String {
    val link = entry.message?.let { PhotoLink.parse(it) }
    val file = entry.message?.let { FileLink.parse(it) }
    return when {
        entry.imagePath != null || link != null -> "Photo"
        entry.filePath != null -> "Attachment: ${entry.fileName ?: file?.name ?: "file"}"
        file != null -> "Attachment: ${file.name}"
        !entry.message.isNullOrBlank() -> entry.message.take(160)
        else -> "Message"
    }
}

private fun fullDateTime(epochSeconds: Double): String =
    if (epochSeconds <= 0) "—"
    else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date((epochSeconds * 1000).toLong()))

private fun timeOf(epochSeconds: Double): String =
    if (epochSeconds <= 0) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((epochSeconds * 1000).toLong()))

/** Local calendar day, used to insert one date separator per conversation day. */
private fun dayKeyOf(epochSeconds: Double): Int {
    if (epochSeconds <= 0) return 0
    val cal = Calendar.getInstance().apply { timeInMillis = (epochSeconds * 1000).toLong() }
    return cal.get(Calendar.YEAR) * 10_000 +
        (cal.get(Calendar.MONTH) + 1) * 100 +
        cal.get(Calendar.DAY_OF_MONTH)
}

private fun dayLabelOf(epochSeconds: Double): String {
    val date = Date((epochSeconds * 1000).toLong())
    val dateCal = Calendar.getInstance().apply { time = date }
    val sameYear = dateCal.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
    val pattern = if (sameYear) "EEEE d MMMM" else "EEEE d MMMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}

@Composable
private fun DaySeparator(epochSeconds: Double) {
    Text(
        dayLabelOf(epochSeconds),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
    )
}
