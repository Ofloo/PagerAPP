package io.ofloo.pagerapp.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.data.p2pEnvelopeFromJson
import io.ofloo.pagerapp.data.toJson
import io.ofloo.pagerapp.shared.P2PAssembler
import io.ofloo.pagerapp.shared.P2PChunk
import io.ofloo.pagerapp.shared.P2PChunker
import io.ofloo.pagerapp.shared.P2PEnvelope
import io.ofloo.pagerapp.shared.isLocalP2PTarget
import io.ofloo.pagerapp.shared.sha256Hex
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Minimal BLE GATT queue transport. It implements the transport part of docs/p2p-protocol.md:
 * CBOR is not used yet; JSON frames are used while the BLE link itself is being validated.
 */
object BleP2P {

    private const val TAG = "PagerAPP"
    private val SERVICE_UUID: UUID = UUID.fromString("0000f00d-0000-1000-8000-00805f9b34fb")
    private val RX_UUID: UUID = UUID.fromString("7a2f3c10-6b3b-4c0a-9e5c-3c8d6a1f2b71")
    private val TX_UUID: UUID = UUID.fromString("7a2f3c10-6b3b-4c0a-9e5c-3c8d6a1f2b72")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var context: Context? = null
    private var store: Store? = null
    private var adapter: BluetoothAdapter? = null
    private var server: BluetoothGattServer? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var clientGatt: BluetoothGatt? = null
    private var serverDevice: BluetoothDevice? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var notifyReady = false
    private var centralConnected = false
    private var connecting = false
    private var localId: String = ""
    private var mtu = 23
    /** One frame, and which link it belongs to (null = the link we opened as central). */
    private class OutgoingPacket(val bytes: ByteArray, val device: BluetoothDevice?)
    private val outgoing = ConcurrentLinkedQueue<OutgoingPacket>()
    private var sending = false
    // Every link has its own reassembly buffer. A peer's frames must never be parsed with another
    // peer's offset; that used to make replies go to the wrong phone.
    private val clientFrames = FrameReader { raw -> onJson(raw, null) }
    private val serverReaders = java.util.concurrent.ConcurrentHashMap<String, FrameReader>()
    /** Devices that enabled notifications on our GATT server. */
    private val notifyDevices = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )
    private val assemblers = mutableMapOf<String, P2PAssembler>()
    private val transferEnvelopes = mutableMapOf<String, String>()
    private val transferDevices = mutableMapOf<String, BluetoothDevice?>()
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var haveSent = false
    private var advertising = false
    private var scanning = false
    private class PeerSeen(val device: BluetoothDevice, val rssi: Int, val seenAt: Long)
    private val peers = java.util.concurrent.ConcurrentHashMap<String, PeerSeen>()
    /** The peer we last linked to; skipped for a while so a second phone gets a turn. */
    private var linkedPeer: String = ""
    private var linkedPeerUntil = 0L
    /** When the current link last carried a frame; a silent link is recycled. */
    @Volatile private var lastLinkActivity = 0L
    private var discoverAttempts = 0
    private var consecutiveSendTimeouts = 0
    private var consecutiveWriteErrors = 0
    // Envelopes are sent one at a time: firing several 70-chunk transfers at once saturates the
    // link and can stall the peer's own transfer in the other direction.
    private val sendLock = Any()
    private val sendQueue = ArrayDeque<P2PEnvelope>()
    private var sendingEnvelope: String? = null
    private var sendingSince = 0L
    private val sendAttempts = HashMap<String, Int>()

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        if (!hasPermissions(ctx)) {
            Log.w(TAG, "BLE P2P permissions missing")
            return
        }
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val a = manager?.adapter ?: return
        if (!a.isEnabled) return
        context = ctx.applicationContext
        store = Store(ctx)
        adapter = a
        localId = store!!.ownRouteHash().take(8)
        if (localId.length < 8) {
            Log.w(TAG, "BLE P2P has no own route hash")
            return
        }
        started = true
        // With the screen off the CPU suspends and the GATT handshake never finishes. Nearby sync
        // is opt-in, so keeping the CPU awake while it is enabled is a fair trade. This lives here
        // (not in a service) so it covers both hosts: PollService and P2PService.
        wakeLock = (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PagerAPP:p2p")
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        deliverQueuedLocally()
        startHeartbeat()
        openServer()
        advertiser = a.bluetoothLeAdvertiser
        startAdvertise()
        scanner = a.bluetoothLeScanner
        startScan()
        Log.i(TAG, "BLE P2P started as $localId")
    }

    @Synchronized
    fun stop() {
        started = false
        runCatching { wakeLock?.release() }
        wakeLock = null
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { scanner?.stopScan(scanCallback) }
        advertising = false
        scanning = false
        runCatching { clientGatt?.close() }
        runCatching { server?.close() }
        clientGatt = null
        server = null
        serverDevice = null
        notifyReady = false
        centralConnected = false
        connecting = false
        rx = null
        tx = null
        haveSent = false
        outgoing.clear()
        sending = false
        clientFrames.reset()
        serverReaders.values.forEach { it.reset() }
        Log.i(TAG, "BLE P2P stopped")
    }

    private fun hasPermissions(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun openServer() {
        val manager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        server = manager.openGattServer(context, serverCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        rx = BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        tx = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx!!.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        server?.addService(service)
    }

    private fun startAdvertise() {
        if (advertising) return
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), localId.toByteArray(Charsets.UTF_8))
            .setIncludeDeviceName(false)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        runCatching { advertiser?.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { Log.w(TAG, "BLE advertise failed: ${it.message}") }
    }

    private fun startScan() {
        if (scanning) return
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner?.startScan(filters, settings, scanCallback) }
            .onFailure { Log.w(TAG, "BLE scan failed: ${it.message}") }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            Log.i(TAG, "BLE advertising")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Log.w(TAG, "BLE advertising failed: $errorCode")
            scheduleRestart()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            scanning = true
            val data = result?.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
            if (data == null) {
                Log.d(TAG, "BLE scan without PagerAPP service data: ${result?.device?.address}")
                return
            }
            val remote = String(data, Charsets.UTF_8)
            Log.d(TAG, "BLE scan found peer $remote")
            if (remote.length < 8 || remote == localId) return
            val device = result.device
            if (device == null) {
                Log.w(TAG, "BLE scan result without a device (background scan anonymised?)")
                return
            }
            peers[remote] = PeerSeen(device, result.rssi, System.currentTimeMillis())

            // A link that stopped carrying frames while we still have data to send is dead even
            // if the stack never delivered a disconnect callback.
            val now = System.currentTimeMillis()
            val silent = now - lastLinkActivity
            val otherVisible = peers.any { (id, seen) ->
                id != linkedPeer && now - seen.seenAt < 5_000L
            }
            if (centralConnected &&
                ((outgoing.isNotEmpty() && silent > 20_000L) || (silent > 60_000L && otherVisible))
            ) {
                recycleClient()
            }
            if (centralConnected || connecting || serverDevice != null) return
            // Only the lower id opens a link. Among several nearby phones, prefer the strongest
            // signal; after a while give another visible phone a turn so a mesh with more than
            // two phones still syncs with all of them.
            val candidates = peers.entries
                .filter { now - it.value.seenAt < 5_000L && localId < it.key }
            if (candidates.isEmpty()) return
            val fresh = candidates.filter { it.key != linkedPeer || now > linkedPeerUntil }
            val best = (if (fresh.isNotEmpty()) fresh else candidates).maxByOrNull { it.value.rssi }
                ?: return
            linkedPeer = best.key
            linkedPeerUntil = now + 60_000L
            runCatching { clientGatt?.close() }
            clientGatt = null
            connecting = true
            Log.i(TAG, "BLE connecting to ${best.key} rssi=${best.value.rssi}")
            runCatching {
                clientGatt = best.value.device.connectGatt(
                    context, false, clientCallback, BluetoothDevice.TRANSPORT_LE,
                )
            }.onFailure {
                connecting = false
                Log.w(TAG, "BLE connect failed: ${it.message}")
            }
            main.postDelayed({
                if (connecting && !centralConnected) {
                    Log.w(TAG, "BLE connect timed out")
                    runCatching { clientGatt?.close() }
                    clientGatt = null
                    connecting = false
                    scheduleRestart()
                }
            }, 10_000L)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.w(TAG, "BLE scan failed: $errorCode")
            scheduleRestart()
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                // The same peer must not hold two links (one in each direction); that produced two
                // streams to the same phone and made the stack drop the connection.
                if (device != null && clientGatt?.device?.address == device.address) {
                    Log.i(TAG, "BLE duplicate link ignored")
                    return
                }
                if (device != null) {
                    serverDevice = device
                    serverReaders[device.address]?.reset()
                    Log.i(TAG, "BLE peer connected (peripheral)")
                }
                lastLinkActivity = System.currentTimeMillis()
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                if (device != null) {
                    serverReaders.remove(device.address)?.reset()
                    notifyDevices.remove(device.address)
                    if (serverDevice == device) {
                        serverDevice = null
                        notifyReady = false
                    }
                    haveSent = false
                    scheduleRestart()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            this@BleP2P.mtu = mtu.coerceAtLeast(23)
            Log.i(TAG, "BLE server MTU ${this@BleP2P.mtu}")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor?.uuid == CCCD_UUID) {
                notifyReady = true
                if (device != null) notifyDevices.add(device.address)
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                sendJson(JSONObject().put("t", "hello"), device)
                sendHaveTo(device)
            } else if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (device != null) {
                value?.let { bytes ->
                    serverReaders.computeIfAbsent(device.address) {
                        FrameReader { raw -> onJson(raw, device) }
                    }.feed(bytes)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            lastLinkActivity = System.currentTimeMillis()
            consecutiveSendTimeouts = 0
            outgoing.poll()
            sending = false
            pump()
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "BLE client state status=$status newState=$newState")
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                centralConnected = true
                connecting = false
                lastLinkActivity = System.currentTimeMillis()
                rx = null
                tx = null
                discoverAttempts = 0
                // A previous link that died without a callback leaves "sending" stuck and old
                // fragments queued. This is a fresh link; start clean.
                sending = false
                outgoing.clear()
                resetIncoming()
                gatt.discoverServices()
                // A half-open link reports "connected" but never resolves services. Retry
                // instead of waiting for the 45 s idle recycle.
                main.postDelayed({
                    if (centralConnected && (rx == null || tx == null)) {
                        Log.w(TAG, "BLE service discovery timed out")
                        recycleClient()
                    }
                }, 6_000L)
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                centralConnected = false
                connecting = false
                runCatching { gatt.close() }
                clientGatt = null
                notifyReady = false
                haveSent = false
                resetIncoming()
                outgoing.clear()
                sending = false
                scheduleRestart()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "BLE services discovered status=$status")
            val service = gatt.getService(SERVICE_UUID)
            val foundRx = service?.getCharacteristic(RX_UUID)
            val foundTx = service?.getCharacteristic(TX_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || foundRx == null || foundTx == null) {
                // Android's GATT cache is famously stale after a reconnect: the service exists but
                // its characteristics are missing. Refresh and retry instead of staying mute.
                discoverAttempts++
                Log.w(TAG, "BLE service incomplete (attempt $discoverAttempts)")
                if (discoverAttempts <= 2) {
                    refreshGattCache(gatt)
                    main.postDelayed({ runCatching { gatt.discoverServices() } }, 400L)
                } else {
                    discoverAttempts = 0
                    recycleClient()
                }
                return
            }
            discoverAttempts = 0
            rx = foundRx
            tx = foundTx
            // Do not wait for onMtuChanged: some stacks return true but never call it, which
            // leaves notifications disabled and the queue sync silently dead.
            gatt.requestMtu(512)
            enableNotifications(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@BleP2P.mtu = mtu.coerceAtLeast(23)
            Log.i(TAG, "BLE client MTU ${this@BleP2P.mtu} status=$status")
            enableNotifications(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor?, status: Int) {
            Log.i(TAG, "BLE descriptor write status=$status")
            if (descriptor?.uuid == CCCD_UUID) {
                notifyReady = true
                sending = false
                sendJson(JSONObject().put("t", "hello"))
                sendHave()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            clientFrames.feed(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let(clientFrames::feed)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?, status: Int) {
            lastLinkActivity = System.currentTimeMillis()
            consecutiveSendTimeouts = 0
            if (status == BluetoothGatt.GATT_SUCCESS) {
                consecutiveWriteErrors = 0
                outgoing.poll()
                sending = false
                pump()
                return
            }
            // A failed write never reached the peer, so the packet must stay queued. Two failures
            // in a row mean the link is gone even if the stack still calls it connected; retrying
            // the same fragment forever is what left messages stuck at the "under way" icon.
            Log.w(TAG, "BLE write failed: $status")
            sending = false
            consecutiveWriteErrors++
            if (consecutiveWriteErrors >= 2) {
                consecutiveWriteErrors = 0
                main.post { recycleClient() }
            } else {
                main.postDelayed({ pump() }, 200L)
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val characteristic = tx ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            notifyReady = true
            sendHave()
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    /**
     * Keeps an idle link warm so a dead one is noticed: a hello makes the peer answer with HAVE.
     * Without it a stale link can stay "connected" forever and block every reconnect.
     */
    private fun startHeartbeat() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!started) return
                val now = System.currentTimeMillis()
                val linked = centralConnected || (serverDevice != null && notifyReady)
                if (linked && now - lastLinkActivity > 15_000L) {
                    sendJson(JSONObject().put("t", "hello"))
                }
                var advance = false
                synchronized(sendLock) {
                    val id = sendingEnvelope
                    if (id != null && now - sendingSince > 20_000L) {
                        Log.w(TAG, "BLE envelope ACK timeout for $id")
                        sendingEnvelope = null
                        val attempts = sendAttempts[id] ?: 0
                        if (attempts < 3) {
                            store?.p2pQueue()?.firstOrNull { it.id == id }?.let(sendQueue::addLast)
                        }
                        advance = true
                    }
                }
                if (advance) sendNextEnvelope()
                main.postDelayed(this, 15_000L)
            }
        }, 15_000L)
    }

    /** Android caches GATT services per device; a stale cache hides the characteristics. */
    private fun refreshGattCache(gatt: BluetoothGatt) {
        runCatching {
            val refresh = gatt.javaClass.getMethod("refresh")
            refresh.isAccessible = true
            refresh.invoke(gatt)
        }.onFailure { Log.w(TAG, "BLE cache refresh failed: ${it.message}") }
    }

    private fun recycleClient() {
        Log.i(TAG, "BLE link stale, reconnecting")
        runCatching { clientGatt?.close() }
        clientGatt = null
        centralConnected = false
        connecting = false
        notifyReady = false
        haveSent = false
        resetIncoming()
        outgoing.clear()
        sending = false
        scheduleRestart()
    }

    private fun scheduleRestart() {
        if (!started) return
        main.postDelayed({
            if (!started) return@postDelayed
            runCatching { scanner?.stopScan(scanCallback) }
            scanning = false
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            advertising = false
            startAdvertise()
            startScan()
        }, 2_000L)
    }

    fun queueChanged() {
        if (started) sendHave(force = true)
    }

    /**
     * Deliver envelopes already queued for one of our own mailboxes. They may have been stored
     * earlier on a link whose decryption failed, or while the key/contact was not ready yet.
     */
    private fun deliverQueuedLocally() {
        val st = store ?: return
        val ctx = context ?: return
        val local = st.localRouteHashes()
        st.p2pQueue().forEach { envelope ->
            if (!isLocalP2PTarget(envelope.to, local)) return@forEach
            val delivered = runCatching { P2PReceiver.deliver(ctx, envelope) }
                .onFailure { Log.w(TAG, "BLE local delivery failed: ${it.message}") }
                .getOrDefault(false)
            if (delivered) st.markP2PDelivered(envelope.id)
        }
    }

    private fun sendHave(force: Boolean = false) {
        deliverQueuedLocally()
        if (haveSent && !force) return
        haveSent = true
        val ids = store?.p2pQueue()?.map { it.id } ?: emptyList()
        Log.i(TAG, "BLE HAVE ${ids.size} envelope(s)")
        broadcast(JSONObject().put("t", "have").put("ids", JSONArray(ids)))
    }

    private fun sendHaveTo(device: BluetoothDevice?) {
        deliverQueuedLocally()
        val ids = store?.p2pQueue()?.map { it.id } ?: emptyList()
        sendJson(JSONObject().put("t", "have").put("ids", JSONArray(ids)), device)
    }

    private fun sendJson(json: JSONObject, device: BluetoothDevice? = null) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val max = (mtu - 3).coerceAtLeast(20)
        val frame = ByteArray(bytes.size + 2)
        frame[0] = ((bytes.size ushr 8) and 0xff).toByte()
        frame[1] = (bytes.size and 0xff).toByte()
        System.arraycopy(bytes, 0, frame, 2, bytes.size)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + max, frame.size)
            outgoing.add(OutgoingPacket(frame.copyOfRange(offset, end), device))
            offset = end
        }
        pump()
    }

    /** Sends to every live link: the one we opened and each phone connected to our server. */
    private fun broadcast(json: JSONObject) {
        if (clientGatt != null) sendJson(json, null)
        notifyDevices.toList().forEach { address ->
            notifyDevicesDevice(address)?.let { device -> sendJson(json, device) }
        }
    }

    private fun notifyDevicesDevice(address: String): BluetoothDevice? =
        serverDevice?.takeIf { it.address == address } ?: runCatching {
            server?.connectedDevices?.firstOrNull { it.address == address }
        }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun pump() {
        if (sending) return
        // Peek before choosing a channel: polling here would silently drop packets while the
        // peer has not enabled notifications yet.
        val packet = outgoing.peek() ?: return
        if (packet.device == null) {
            val gatt = clientGatt ?: return
            val characteristic = rx ?: return
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = packet.bytes
            if (gatt.writeCharacteristic(characteristic)) {
                sending = true
                armSendWatchdog()
            } else {
                // Another GATT operation is in flight. Never drop the packet: retry shortly.
                main.postDelayed({ pump() }, 50L)
            }
            return
        }
        val characteristic = tx
        if (characteristic != null && notifyDevices.contains(packet.device.address)) {
            characteristic.value = packet.bytes
            if (server?.notifyCharacteristicChanged(packet.device, characteristic, false) == true) {
                sending = true
                armSendWatchdog()
            } else {
                main.postDelayed({ pump() }, 50L)
            }
        }
    }

    /**
     * A link that drops mid-frame leaves a partial length prefix in the reassembly buffer. If it
     * is not cleared, every frame on the next connection is parsed at the wrong offset and the
     * peer's HAVE/WANT looks empty. Drop the partial frame and any unfinished transfer.
     */
    private fun resetIncoming() {
        clientFrames.reset()
        serverReaders.values.forEach { it.reset() }
        assemblers.clear()
        transferEnvelopes.clear()
        // A new link is a new chance: let previously failed envelopes be retried again.
        synchronized(sendLock) { sendAttempts.clear() }
    }

    /** A callback that never arrives would stop all further frames; recover instead of hanging. */
    private fun armSendWatchdog() {
        main.postDelayed({
            if (sending) {
                // A missing callback does not mean a failed write: resending the bytes would
                // duplicate them mid-frame and corrupt the peer's parser. Assume it arrived;
                // a genuinely missing chunk is recovered with a whole-envelope NACK.
                Log.w(TAG, "BLE send callback lost, assuming delivered")
                outgoing.poll()
                sending = false
                consecutiveSendTimeouts++
                if (consecutiveSendTimeouts >= 2) {
                    // Two write callbacks in a row never arrived: the link is gone even if the
                    // stack did not say so. Reset it instead of burning the whole transfer.
                    Log.w(TAG, "BLE link is dead, reconnecting")
                    consecutiveSendTimeouts = 0
                    main.post { recycleClient() }
                    return@postDelayed
                }
                pump()
            }
        }, 5_000L)
    }

    /** Reassembles length-prefixed frames for one link; each link has its own instance. */
    private class FrameReader(private val onFrame: (String) -> Unit) {
        private val buffer = ByteArrayOutputStream()
        private var expected = -1

        fun feed(value: ByteArray) {
            synchronized(this) {
                buffer.write(value)
                while (true) {
                    val current = buffer.toByteArray()
                    if (expected < 0) {
                        if (current.size < 2) return
                        expected = ((current[0].toInt() and 0xff) shl 8) or (current[1].toInt() and 0xff)
                    }
                    val total = expected + 2
                    if (current.size < total) return
                    val payload = current.copyOfRange(2, total)
                    buffer.reset()
                    if (current.size > total) buffer.write(current, total, current.size - total)
                    expected = -1
                    onFrame(String(payload, Charsets.UTF_8))
                }
            }
        }

        fun reset() {
            synchronized(this) {
                buffer.reset()
                expected = -1
            }
        }
    }

    private fun onJson(raw: String, source: BluetoothDevice?) {
        lastLinkActivity = System.currentTimeMillis()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (json.optString("t")) {
            // Reply on the link the request came from, not on whichever peer connected last.
            "hello" -> sendHaveTo(source)
            "have" -> {
                val remote = json.optJSONArray("ids")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty().toSet()
                val local = store?.p2pQueue()?.map { it.id }?.toSet().orEmpty()
                val want = remote - local
                if (want.isNotEmpty()) {
                    Log.i(TAG, "BLE WANT ${want.size} envelope(s)")
                    sendJson(JSONObject().put("t", "want").put("ids", JSONArray(want.toList())), source)
                }
            }
            "want" -> {
                val wanted = json.optJSONArray("ids")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty()
                val queue = store?.p2pQueue().orEmpty().associateBy { it.id }
                val selected = wanted.mapNotNull(queue::get)
                Log.i(TAG, "BLE queueing ${selected.size}/${wanted.size} wanted envelope(s)")
                queueEnvelopes(selected, source)
            }
            "start" -> {
                val transfer = json.optString("transfer")
                val id = json.optString("envelope")
                val total = json.optInt("total")
                val sha = json.optString("sha256")
                if (transfer.isNotBlank() && id.isNotBlank() && total > 0 && sha.length == 64) {
                    assemblers[transfer] = P2PAssembler(transfer, total, sha)
                    transferEnvelopes[transfer] = id
                    transferDevices[transfer] = source
                }
            }
            "chunk" -> {
                val transfer = json.optString("transfer")
                val assembler = assemblers[transfer] ?: return
                val data = runCatching { Base64.decode(json.optString("data"), Base64.NO_WRAP) }.getOrNull() ?: return
                assembler.accept(
                    P2PChunk(
                        transferId = transfer,
                        seq = json.optInt("seq"),
                        total = json.optInt("total"),
                        data = data,
                        crc32 = json.optLong("crc32"),
                    ),
                )
            }
            "done" -> finishTransfer(json.optString("transfer"))
            "nack" -> {
                val id = json.optString("envelope")
                if (id.isNotBlank()) {
                    Log.i(TAG, "BLE NACK for $id; queueing a retry")
                    retryEnvelope(id)
                }
            }
            "ack" -> {
                val id = json.optString("envelope")
                if (json.optBoolean("stored") && id.isNotBlank()) {
                    if (json.optBoolean("delivered")) {
                        Log.i(TAG, "BLE ACK delivered envelope $id")
                        store?.markP2PDelivered(id)
                    } else {
                        Log.i(TAG, "BLE ACK custody envelope $id")
                        // Someone is carrying it, but not the owner: keep it queued (clock icon)
                        // and offer it to the next phone until the owner confirms.
                    }
                    envelopeStored(id)
                }
            }
        }
    }

    private val sendTargets = HashMap<String, BluetoothDevice?>()

    private fun queueEnvelopes(envelopes: List<P2PEnvelope>, device: BluetoothDevice?) {
        synchronized(sendLock) {
            envelopes.forEach { envelope ->
                sendTargets[envelope.id] = device
                if (sendQueue.none { it.id == envelope.id } && sendingEnvelope != envelope.id) {
                    sendQueue.addLast(envelope)
                }
            }
        }
        sendNextEnvelope()
    }

    private fun sendNextEnvelope() {
        synchronized(sendLock) {
            if (sendingEnvelope != null) return
            val next = sendQueue.removeFirstOrNull() ?: return
            sendingEnvelope = next.id
            sendingSince = System.currentTimeMillis()
            sendAttempts[next.id] = (sendAttempts[next.id] ?: 0) + 1
            sendEnvelope(next, sendTargets[next.id])
        }
    }

    /** The peer could not assemble the envelope; send it once more (bounded). */
    private fun retryEnvelope(id: String) {
        synchronized(sendLock) {
            val attempts = sendAttempts[id] ?: 0
            if (attempts >= 3) {
                Log.w(TAG, "BLE giving up on $id after $attempts attempts")
                return
            }
            sendQueue.removeAll { it.id == id }
            if (sendingEnvelope == id) {
                sendingEnvelope = null
                sendQueue.addFirst(store?.p2pQueue()?.firstOrNull { it.id == id } ?: return)
                main.post { sendNextEnvelope() }
            } else {
                val envelope = store?.p2pQueue()?.firstOrNull { it.id == id } ?: return
                sendQueue.addLast(envelope)
                main.post { sendNextEnvelope() }
            }
        }
    }

    /** The peer confirmed custody of [id]; move on to the next queued envelope. */
    private fun envelopeStored(id: String) {
        synchronized(sendLock) {
            sendQueue.removeAll { it.id == id }
            if (sendingEnvelope == id) {
                sendingEnvelope = null
                main.post { sendNextEnvelope() }
            }
        }
    }

    private fun sendEnvelope(envelope: P2PEnvelope, device: BluetoothDevice?) {
        val bytes = envelope.toJson().toString().toByteArray(Charsets.UTF_8)
        val transfer = UUID.randomUUID().toString()
        val sha = sha256Hex(bytes)
        // Fewer frames means fewer chances to lose one. 240 bytes fit one MTU-sized packet on a
        // modern link; fall back to 120 when the negotiated MTU is small.
        val chunkSize = if (mtu >= 200) 240 else 120
        val chunks = P2PChunker.split(transfer, bytes, chunkSize)
        Log.i(TAG, "BLE transfer start ${envelope.id} bytes=${bytes.size} chunks=${chunks.size}")
        sendJson(
            JSONObject()
                .put("t", "start")
                .put("transfer", transfer)
                .put("envelope", envelope.id)
                .put("total", chunks.size)
                .put("sha256", sha),
            device,
        )
        chunks.forEach { chunk ->
            sendJson(
                JSONObject()
                    .put("t", "chunk")
                    .put("transfer", transfer)
                    .put("seq", chunk.seq)
                    .put("total", chunk.total)
                    .put("crc32", chunk.crc32)
                    .put("data", Base64.encodeToString(chunk.data, Base64.NO_WRAP)),
                device,
            )
        }
        sendJson(JSONObject().put("t", "done").put("transfer", transfer), device)
    }

    private fun finishTransfer(transfer: String) {
        val assembler = assemblers.remove(transfer) ?: return
        val id = transferEnvelopes.remove(transfer).orEmpty()
        val source = transferDevices.remove(transfer)
        val bytes = assembler.finish()
        if (bytes == null) {
            Log.w(TAG, "BLE transfer incomplete $id; asking for a retry")
            sendAck(id, "", false, source)
            sendNack(id, source)
            return
        }
        val envelope = runCatching { p2pEnvelopeFromJson(JSONObject(String(bytes, Charsets.UTF_8))) }
            .getOrNull()
        if (envelope == null || envelope.isExpired()) {
            sendAck(id, "", false, source)
            return
        }
        val store = store ?: return
        store.enqueueP2P(envelope)
        val localTarget = isLocalP2PTarget(envelope.to, store.localRouteHashes())
        val deliveredLocally = localTarget && runCatching {
            P2PReceiver.deliver(context ?: return, envelope)
        }.onFailure {
            Log.w(TAG, "BLE local delivery failed: ${it.message}")
        }.getOrDefault(false)
        if (deliveredLocally) store.markP2PDelivered(envelope.id)
        Log.i(
            TAG,
            "BLE stored ${envelope.id}; to=${envelope.to.take(8)} localTarget=$localTarget " +
                "localDelivery=$deliveredLocally",
        )
        sendAck(envelope.id, envelope.payloadSha256(), true, source, deliveredLocally)
    }

    private fun sendNack(id: String, device: BluetoothDevice?) {
        if (id.isBlank()) return
        sendJson(JSONObject().put("t", "nack").put("envelope", id), device)
    }

    private fun sendAck(
        id: String,
        sha: String,
        stored: Boolean,
        device: BluetoothDevice?,
        delivered: Boolean = false,
    ) {
        if (id.isBlank()) return
        sendJson(
            JSONObject()
                .put("t", "ack")
                .put("envelope", id)
                .put("sha256", sha)
                .put("stored", stored)
                // The phone that owns the destination is the delivery, by definition; no separate
                // receipt round-trip is needed for the bubble to close.
                .put("delivered", delivered),
            device,
        )
    }
}
