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
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue

/** One PagerAPP peer currently visible over BLE, with the signal of its latest advertisement. */
data class NearbyPeer(
    val id: String,
    /** Null when the peer is connected but its advertisement has not been seen recently. */
    val rssi: Int?,
    val connected: Boolean,
)

/** Point-in-time view of the BLE neighbourhood, safe to read from the service thread. */
data class NearbySnapshot(
    val peers: List<NearbyPeer>,
    val connected: Boolean,
) {
    val count: Int get() = peers.size
    val bestRssi: Int? get() = peers.firstNotNullOfOrNull { it.rssi }
}

enum class P2PDirection { SENDING, RECEIVING }

/** Transfer progress for the notification: chunks completed out of the transfer total. */
data class P2PTransferProgress(val done: Int, val total: Int) {
    init {
        require(total > 0)
    }
}

/** What the BLE transport is doing right now, for the foreground notification. */
data class P2PActivity(
    val peers: Int,
    val bestRssi: Int?,
    val connected: Boolean,
    val connecting: Boolean,
    val syncing: Boolean,
    val outbound: P2PTransferProgress?,
    val inbound: P2PTransferProgress?,
    val queued: Int,
    val lastCompleted: P2PTransferProgress?,
    val lastDirection: P2PDirection?,
)

@SuppressLint("MissingPermission")
object BleP2P {

    private const val TAG = "PagerAPP"
    private const val CENTRAL_TARGET = "@central"
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
    private var clientRx: BluetoothGattCharacteristic? = null
    private var clientTx: BluetoothGattCharacteristic? = null
    private var serverTx: BluetoothGattCharacteristic? = null
    @Volatile private var centralConnected = false
    private var centralReady = false
    @Volatile private var connecting = false
    private var localId = ""
    private var centralMtu = 23
    private val deviceMtu = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private class OutgoingPacket(
        val bytes: ByteArray,
        val device: BluetoothDevice?,
        val link: String,
        val envelopeId: String? = null,
        val chunkSeq: Int? = null,
        val chunkTotal: Int? = null,
        val lastChunkFragment: Boolean = false,
    )
    private val queuedByLink = java.util.concurrent.ConcurrentHashMap<String, ConcurrentLinkedQueue<OutgoingPacket>>()
    private val sending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val inFlightByLink = java.util.concurrent.ConcurrentHashMap<String, OutgoingPacket>()
    private val assemblingBytes = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val linkGenerations = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val linkTimeouts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val linkWriteErrors = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val clientFrames = FrameReader { raw -> onJson(raw, null) }
    private val serverReaders = java.util.concurrent.ConcurrentHashMap<String, FrameReader>()
    private val connectedDevices = java.util.concurrent.ConcurrentHashMap<String, BluetoothDevice>()
    private val notifyDevices = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )
    private val notifyReadyDevices = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )
    private val assemblers = java.util.concurrent.ConcurrentHashMap<String, P2PAssembler>()
    private val transferEnvelopes = java.util.concurrent.ConcurrentHashMap<String, String>()
    // A transfer belongs either to a peripheral's address or to the central link (CENTRAL_TARGET).
    // ConcurrentHashMap cannot store a null BluetoothDevice for the central link.
    private val transferSources = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private val bleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pagerapp-ble-state").apply { isDaemon = true }
    }
    private var started = false
    private var generation = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val haveSentDevices = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var haveSentClient = false
    private var advertising = false
    private var scanning = false
    private class PeerSeen(val device: BluetoothDevice, val rssi: Int, val seenAt: Long)
    private val peers = java.util.concurrent.ConcurrentHashMap<String, PeerSeen>()
    private val recentlyVisited = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var linkedPeer = ""
    private var lastLinkActivity = 0L
    private val linkActivity = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var discoverAttempts = 0
    private val sendLock = Any()
    private val sendQueue = ArrayDeque<P2PEnvelope>()
    @Volatile private var sendingEnvelope: String? = null
    private var sendingSince = 0L
    @Volatile private var outboundTotal = 0
    @Volatile private var outboundDone = 0
    @Volatile private var lastWantAt = 0L
    @Volatile private var lastCompletedAt = 0L
    @Volatile private var lastCompletedDone = 0
    @Volatile private var lastCompletedTotal = 0
    @Volatile private var lastCompletedDirection: P2PDirection? = null
    private val sendAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val sendTargets = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val envelopeTargets = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var sendingTarget: String? = null

    @Synchronized
    fun start(ctx: Context) {
        if (started) {
            if (store?.alarmPolling == true) stopAfter(60_000L)
            return
        }
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
        generation++
        if (store!!.alarmPolling) stopAfter(60_000L)
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

    fun stopAfter(delayMillis: Long) {
        val appContext = context ?: return
        val scheduledGeneration = synchronized(this) { generation }
        main.postDelayed({
            if (Store(appContext).alarmPolling && synchronized(this) { generation == scheduledGeneration }) stop()
        }, delayMillis)
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
        centralConnected = false
        centralReady = false
        connecting = false
        clientRx = null
        clientTx = null
        serverTx = null
        connectedDevices.clear()
        linkActivity.clear()
        deviceMtu.clear()
        notifyDevices.clear()

        notifyReadyDevices.clear()
        haveSentDevices.clear()
        haveSentClient = false
        synchronized(sendLock) {
            queuedByLink.forEach { (link, queue) ->
                synchronized(queue) {
                    queue.clear()
                    queuedByLink.remove(link, queue)
                    linkGenerations.computeIfAbsent(link) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
                    sending.remove(link)
                    inFlightByLink.remove(link)
                }
            }
            sending.clear()
            inFlightByLink.clear()
            linkTimeouts.clear()
            linkWriteErrors.clear()
            sendTargets.clear()
            envelopeTargets.clear()
            sendingEnvelope = null
            sendingTarget = null
            sendQueue.clear()
            sendAttempts.clear()
        }

        assemblers.clear()
        assemblingBytes.clear()
        transferEnvelopes.clear()
        transferSources.clear()
        serverReaders.values.forEach { it.reset() }
        serverReaders.clear()
        clientFrames.reset()
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
        val serverRx = BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        serverTx = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        serverTx!!.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(serverRx)
        service.addCharacteristic(serverTx!!)
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
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
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
            val data = result?.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            val remote = String(data, Charsets.UTF_8)
            if (remote.length < 8 || remote == localId) return
            val device = result.device ?: return
            val now = System.currentTimeMillis()
            peers[remote] = PeerSeen(device, result.rssi, now)
            val silent = now - lastLinkActivity
            if (centralConnected && silent > if (hasOtherVisiblePeer(now)) 8_000L else 60_000L) {
                recycleClient()
            }
            if (centralConnected || connecting) return
            val candidates = peers.entries
                .filter { now - it.value.seenAt < 5_000L && localId < it.key }
            if (candidates.isEmpty()) return
            val fresh = candidates.filter { (recentlyVisited[it.key] ?: 0L) <= now }
            val best = (fresh.ifEmpty { candidates }).maxByOrNull { it.value.rssi } ?: return
            linkedPeer = best.key
            recentlyVisited[best.key] = now + if (candidates.size > 1) 8_000L else 60_000L
            connecting = true
            Log.i(TAG, "BLE connecting to ${best.key} rssi=${best.value.rssi}")
            runCatching {
                clientGatt = best.value.device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
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
                    recentlyVisited[best.key] = System.currentTimeMillis() + 5_000L
                }
            }, 10_000L)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.w(TAG, "BLE scan failed: $errorCode")
            scheduleRestart()
        }
    }

    private fun hasOtherVisiblePeer(now: Long): Boolean = peers.any { (id, seen) ->
        id != linkedPeer && now - seen.seenAt < 5_000L
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                if (device != null && clientGatt?.device?.address == device.address) return
                if (device != null) {
                    connectedDevices[device.address] = device
                    linkActivity[device.address] = System.currentTimeMillis()
                    serverReaders.computeIfAbsent(device.address) { FrameReader { raw -> onJson(raw, device) } }.reset()
                    Log.i(TAG, "BLE peer connected (peripheral ${device.address.takeLast(5)})")
                }
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED && device != null) {
                connectedDevices.remove(device.address)
                linkActivity.remove(device.address)
                deviceMtu.remove(device.address)
                serverReaders.remove(device.address)?.reset()
                notifyDevices.remove(device.address)
                notifyReadyDevices.remove(device.address)
                haveSentDevices.remove(device.address)
                val queue = queuedByLink[device.address]
                if (queue != null) synchronized(queue) {
                    queue.clear()
                    queuedByLink.remove(device.address, queue)
                }
                linkGenerations.computeIfAbsent(device.address) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
                sending.remove(device.address)
                inFlightByLink.remove(device.address)
                synchronized(sendLock) {
                    envelopeTargets.values.forEach { it.remove(device.address) }
                    envelopeTargets.entries.removeIf { it.value.isEmpty() }
                    sendAttempts.keys.removeIf { envelopeTargets[it].isNullOrEmpty() }
                    sendTargets.entries.removeIf { it.value == device.address }
                    if (sendingTarget == device.address) {
                        val id = sendingEnvelope
                        sendingEnvelope = null
                        sendingTarget = null
                        id?.let { envelope ->
                            if (!envelopeTargets[envelope].isNullOrEmpty()) {
                                store?.p2pQueue()?.firstOrNull { it.id == envelope }?.let(sendQueue::addLast)
                            }
                        }
                    }
                }
                queuedByLink.keys.toList().forEach(::pump)
                main.post { pump(); sendNextEnvelope() }
                scheduleRestart()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            if (device != null) deviceMtu[device.address] = mtu.coerceAtLeast(23)
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
                device?.let {
                    notifyDevices.add(it.address)
                    if (value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true) {
                        notifyReadyDevices.add(it.address)
                    } else {
                        notifyReadyDevices.remove(it.address)
                    }
                }
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                val enabled = device?.address?.let(notifyReadyDevices::contains) == true
                device?.address?.let { haveSentDevices.remove(it) }
                if (enabled) {
                    sendJson(JSONObject().put("t", "hello"), device)
                    sendHaveTo(device)
                }
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
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (device != null) value?.let { bytes ->
                bleExecutor.execute {
                    val reader = serverReaders.computeIfAbsent(device.address) { FrameReader { raw -> onJson(raw, device) } }
                    reader.feed(bytes)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            completeSend(device, status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "BLE client state status=$status newState=$newState")
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                centralConnected = true
                connecting = false
                lastLinkActivity = System.currentTimeMillis()
                clientRx = null
                clientTx = null
                centralMtu = 23
                discoverAttempts = 0
                clientFrames.reset()
                gatt.discoverServices()
                main.postDelayed({
                    if (clientGatt === gatt && centralConnected && (clientRx == null || clientTx == null)) recycleClient()
                }, 6_000L)
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                if (clientGatt !== gatt) return
                centralConnected = false
                centralReady = false
                connecting = false
                runCatching { gatt.close() }
                clientGatt = null
                clientRx = null
                clientTx = null
                haveSentClient = false
                clientFrames.reset()
                val queue = queuedByLink[CENTRAL_TARGET]
                if (queue != null) synchronized(queue) {
                    queue.clear()
                    queuedByLink.remove(CENTRAL_TARGET, queue)
                    linkGenerations.computeIfAbsent(CENTRAL_TARGET) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
                    sending.remove(CENTRAL_TARGET)
                    inFlightByLink.remove(CENTRAL_TARGET)
                } else {
                    linkGenerations.computeIfAbsent(CENTRAL_TARGET) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
                    sending.remove(CENTRAL_TARGET)
                    inFlightByLink.remove(CENTRAL_TARGET)
                }
                synchronized(sendLock) {
                    envelopeTargets.values.forEach { it.remove(CENTRAL_TARGET) }
                    envelopeTargets.entries.removeIf { it.value.isEmpty() }
                    sendAttempts.keys.removeIf { envelopeTargets[it].isNullOrEmpty() }
                    sendTargets.entries.removeIf { it.value == CENTRAL_TARGET }
                    if (sendingTarget == CENTRAL_TARGET) {
                        val id = sendingEnvelope
                        sendingEnvelope = null
                        sendingTarget = null
                        id?.let { envelope ->
                            if (!envelopeTargets[envelope].isNullOrEmpty()) {
                                store?.p2pQueue()?.firstOrNull { it.id == envelope }?.let(sendQueue::addLast)
                            }
                        }
                    }
                }
                recentlyVisited[linkedPeer] = System.currentTimeMillis() + 2_000L
                queuedByLink.keys.toList().forEach(::pump)
                main.post { pump(); sendNextEnvelope() }
                scheduleRestart()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (clientGatt !== gatt || !centralConnected) return
            val service = gatt.getService(SERVICE_UUID)
            val foundRx = service?.getCharacteristic(RX_UUID)
            val foundTx = service?.getCharacteristic(TX_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || foundRx == null || foundTx == null) {
                discoverAttempts++
                Log.w(TAG, "BLE service incomplete (attempt $discoverAttempts)")
                if (discoverAttempts <= 2) {
                    refreshGattCache(gatt)
                    main.postDelayed({ if (clientGatt === gatt) runCatching { gatt.discoverServices() } }, 400L)
                } else {
                    discoverAttempts = 0
                    recycleClient()
                }
                return
            }
            discoverAttempts = 0
            clientRx = foundRx
            clientTx = foundTx
            gatt.requestMtu(512)
            enableNotifications(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (clientGatt !== gatt || !centralConnected) return
            centralMtu = mtu.coerceAtLeast(23)
            enableNotifications(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (clientGatt !== gatt || !centralConnected || status != BluetoothGatt.GATT_SUCCESS) return
            if (descriptor?.uuid == CCCD_UUID) {
                centralReady = true
                haveSentClient = false
                sendJson(JSONObject().put("t", "hello"))
                sendHave()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (clientGatt === gatt && characteristic.uuid == TX_UUID) bleExecutor.execute { clientFrames.feed(value) }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (clientGatt === gatt && characteristic.uuid == TX_UUID) characteristic.value?.let { value ->
                bleExecutor.execute { clientFrames.feed(value) }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (clientGatt !== gatt || !centralConnected) return
            completeSend(null, status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        if (clientGatt !== gatt) return
        val characteristic = clientTx ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            centralReady = true
            haveSentClient = false
            sendHave()
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    private fun startHeartbeat() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!started) return
                val now = System.currentTimeMillis()
                peers.entries.removeIf { now - it.value.seenAt >= 5_000L }
                val clients = mutableListOf<BluetoothDevice?>()
                if (centralConnected && centralReady && now - lastLinkActivity > 15_000L) clients.add(null)
                notifyDevices.toList().forEach { address ->
                    val last = linkActivity[address] ?: 0L
                    if (now - last > 15_000L) notifyDevicesDevice(address)?.let(clients::add)
                }
                clients.forEach { sendJson(JSONObject().put("t", "hello"), it) }
                synchronized(sendLock) {
                    val id = sendingEnvelope
                    val target = sendingTarget
                    if (id != null && target != null && now - sendingSince > 20_000L) {
                        Log.w(TAG, "BLE envelope ACK timeout for $id")
                        sendingEnvelope = null
                        sendingTarget = null
                        sendTargets.remove(id)
                        if ((sendAttempts[id] ?: 0) < 3 && target in envelopeTargets[id].orEmpty()) {
                            store?.p2pQueue()?.firstOrNull { it.id == id }?.let(sendQueue::addFirst)
                        } else if (envelopeTargets[id].isNullOrEmpty()) {
                            sendAttempts.remove(id)
                        }
                    }
                }
                sendNextEnvelope()
                main.postDelayed(this, 15_000L)
            }
        }, 15_000L)
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        runCatching {
            val refresh = gatt.javaClass.getMethod("refresh")
            refresh.isAccessible = true
            refresh.invoke(gatt)
        }.onFailure { Log.w(TAG, "BLE cache refresh failed: ${it.message}") }
    }

    private fun recycleClient() {
        val gatt = clientGatt ?: return
        Log.i(TAG, "BLE link stale, reconnecting")
        runCatching { gatt.close() }
        clientGatt = null
        centralConnected = false
        connecting = false
        clientRx = null
        clientTx = null
        centralReady = false
        haveSentClient = false
        clientFrames.reset()
        val queue = queuedByLink[CENTRAL_TARGET]
        if (queue != null) synchronized(queue) {
            queue.clear()
            queuedByLink.remove(CENTRAL_TARGET, queue)
        }
        linkGenerations.computeIfAbsent(CENTRAL_TARGET) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        sending.remove(CENTRAL_TARGET)
        inFlightByLink.remove(CENTRAL_TARGET)

        recentlyVisited[linkedPeer] = System.currentTimeMillis() + 1_000L
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
        if (started) {
            haveSentClient = false
            haveSentDevices.clear()
            sendHave(force = true)
            pump()
        }
    }

    /**
     * The peers we have heard from recently, strongest first. Only PagerAPP advertisements are
     * scanned (the service UUID filter), so this is the actual BLE neighbourhood, not every
     * Bluetooth device in the room. Stale entries are filtered here as well as by the scan loop.
     */
    fun nearbySnapshot(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 10_000L): NearbySnapshot {
        val central = centralConnected
        val centralPeer = if (central) linkedPeer else ""
        val connectedAddresses = connectedDevices.keys
        val recent = peers.entries
            .asSequence()
            .filter { now - it.value.seenAt <= maxAgeMs }
            .map { (id, seen) ->
                NearbyPeer(
                    id = id,
                    rssi = seen.rssi,
                    connected = id == centralPeer || seen.device.address in connectedAddresses,
                )
            }
            .toMutableList()
        val recentIds = recent.mapTo(mutableSetOf()) { it.id }
        val recentAddresses = recent.mapNotNullTo(mutableSetOf()) { peer ->
            peers[peer.id]?.device?.address
        }
        // A connected peer may stop advertising, so the 5 s scan cache drops it while the link
        // is still up. Keep it in the count and show its last known signal (or no dBm yet).
        if (central && centralPeer.isNotBlank() && centralPeer !in recentIds) {
            recent += NearbyPeer(centralPeer, peers[centralPeer]?.rssi, connected = true)
        }
        connectedAddresses.forEach { address ->
            if (address !in recentAddresses) {
                val last = peers.entries.firstOrNull { it.value.device.address == address }?.value?.rssi
                recent += NearbyPeer(address.takeLast(6), last, connected = true)
            }
        }
        val found = recent.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
        return NearbySnapshot(found, connected = central || connectedAddresses.isNotEmpty())
    }

    /** Current transfer/sync state for the P2P Telemetry notification. */
    fun activitySnapshot(now: Long = System.currentTimeMillis()): P2PActivity {
        val nearby = nearbySnapshot(now)
        val outbound = synchronized(sendLock) {
            val total = outboundTotal
            if (sendingEnvelope != null && total > 0) {
                P2PTransferProgress(outboundDone.coerceIn(0, total), total)
            } else {
                null
            }
        }
        val inbound = assemblers.values
            .firstOrNull { !it.isComplete }
            ?.let { P2PTransferProgress(it.receivedChunks.coerceIn(0, it.total), it.total) }
        val last = if (now - lastCompletedAt in 0..3_000L && lastCompletedTotal > 0) {
            P2PTransferProgress(lastCompletedDone.coerceIn(0, lastCompletedTotal), lastCompletedTotal)
        } else {
            null
        }
        val queued = store?.p2pQueue()?.size ?: 0
        return P2PActivity(
            peers = nearby.count,
            bestRssi = nearby.bestRssi,
            connected = nearby.connected,
            connecting = connecting,
            syncing = now - lastWantAt in 0..3_000L,
            outbound = outbound,
            inbound = inbound,
            queued = queued,
            lastCompleted = last,
            lastDirection = lastCompletedDirection,
        )
    }

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
        if (centralConnected && centralReady && (!haveSentClient || force)) {
            haveSentClient = true
            val ids = store?.p2pQueue()?.map { it.id } ?: emptyList()
            Log.i(TAG, "BLE HAVE ${ids.size} envelope(s) central")
            sendJson(JSONObject().put("t", "have").put("ids", JSONArray(ids)))
        }
        notifyDevices.toList().forEach { address ->
            if (force || !haveSentDevices.contains(address)) {
                notifyDevicesDevice(address)?.let { device ->
                    haveSentDevices.add(address)
                    val ids = store?.p2pQueue()?.map { it.id } ?: emptyList()
                    Log.i(TAG, "BLE HAVE ${ids.size} envelope(s) peripheral")
                    sendJson(JSONObject().put("t", "have").put("ids", JSONArray(ids)), device)
                }
            }
        }
    }

    private fun sendHaveTo(device: BluetoothDevice?) {
        deliverQueuedLocally()
        val ids = store?.p2pQueue()?.map { it.id } ?: emptyList()
        sendJson(JSONObject().put("t", "have").put("ids", JSONArray(ids)), device)
    }

    private fun sendJson(json: JSONObject, device: BluetoothDevice? = null) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val payloadLimit = ((if (device == null) centralMtu else deviceMtu[device.address] ?: 23) - 3).coerceAtLeast(20)
        val frame = ByteArray(bytes.size + 2)
        frame[0] = ((bytes.size ushr 8) and 0xff).toByte()
        frame[1] = (bytes.size and 0xff).toByte()
        System.arraycopy(bytes, 0, frame, 2, bytes.size)
        val link = device?.address ?: CENTRAL_TARGET
        val queue = queuedByLink.computeIfAbsent(link) { ConcurrentLinkedQueue() }
        synchronized(queue) {
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(offset + payloadLimit, frame.size)
                queue.add(OutgoingPacket(frame.copyOfRange(offset, end), device, link))
                offset = end
            }
        }
        pump(link)
    }

    private fun notifyDevicesDevice(address: String): BluetoothDevice? = connectedDevices[address]

    private fun linkReady(device: BluetoothDevice?): Boolean = if (device == null) {
        centralConnected && centralReady && clientGatt != null && clientRx != null
    } else {
        notifyReadyDevices.contains(device.address) && notifyDevices.contains(device.address) &&
            connectedDevices.containsKey(device.address)
    }

    @SuppressLint("MissingPermission")
    private fun pump(link: String? = null) {
        val links = link?.let(::listOf) ?: queuedByLink.keys.toList()
        links.forEach { targetLink ->
            val queue = queuedByLink[targetLink] ?: return@forEach
            val packet = synchronized(queue) {
                if (!sending.add(targetLink)) return@forEach
                val next = queue.peek()
                if (next == null || !linkReady(next.device)) {
                    sending.remove(targetLink)
                    return@forEach
                }
                inFlightByLink[targetLink] = next
                next
            }
            val generation = linkGenerations.computeIfAbsent(targetLink) { java.util.concurrent.atomic.AtomicLong() }.get()
            if (packet.device == null) {
                val gatt = clientGatt ?: run { retryPacket(packet, generation); return@forEach }
                val characteristic = clientRx ?: run { retryPacket(packet, generation); return@forEach }
                val accepted = synchronized(queue) {
                    if (inFlightByLink[targetLink] !== packet || linkGenerations[targetLink]?.get() != generation) {
                        false
                    } else {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        characteristic.value = packet.bytes
                        gatt.writeCharacteristic(characteristic)
                    }
                }
                if (accepted) {
                    armSendWatchdog(packet, generation)
                } else {
                    retryPacket(packet, generation)
                }
            } else {
                val characteristic = serverTx ?: run { retryPacket(packet, generation); return@forEach }
                val accepted = synchronized(queue) {
                    if (inFlightByLink[targetLink] !== packet || linkGenerations[targetLink]?.get() != generation) {
                        false
                    } else {
                        characteristic.value = packet.bytes
                        server?.notifyCharacteristicChanged(packet.device, characteristic, false) == true
                    }
                }
                if (accepted) {
                    armSendWatchdog(packet, generation)
                } else {
                    retryPacket(packet, generation)
                }
            }
        }
    }

    private fun retryPacket(packet: OutgoingPacket, generation: Long) {
        val queue = queuedByLink[packet.link] ?: return
        val retry = synchronized(queue) {
            if (queuedByLink[packet.link] !== queue ||
                inFlightByLink[packet.link] !== packet ||
                linkGenerations[packet.link]?.get() != generation
            ) {
                false
            } else {
                inFlightByLink.remove(packet.link)
                sending.remove(packet.link)
                true
            }
        }
        if (retry) main.postDelayed({ pump(packet.link) }, 100L)
    }

    private fun completeSend(device: BluetoothDevice?, success: Boolean) {
        val link = device?.address ?: CENTRAL_TARGET
        val queue = queuedByLink[link] ?: return
        val sent = synchronized(queue) {
            if (queuedByLink[link] !== queue) return
            inFlightByLink[link]?.takeIf { it.device?.address == device?.address } ?: return
        }
        val generation = linkGenerations[link]?.get() ?: return
        if (!success) {
            Log.w(TAG, "BLE frame send failed")
            val errors = linkWriteErrors.computeIfAbsent(link) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            if (errors >= 3) {
                linkWriteErrors[link]?.set(0)
                main.post { if (link == CENTRAL_TARGET) recycleClient() else scheduleRestart() }
            }
            retryPacket(sent, generation)
            return
        }
        synchronized(queue) {
            if (queuedByLink[link] !== queue || inFlightByLink[link] !== sent || queue.peek() !== sent) return
            queue.poll()
            inFlightByLink.remove(link)
            sending.remove(link)
            if (queue.isEmpty()) {
                queuedByLink.remove(link, queue)
            }
        }
        if (sent.lastChunkFragment && sent.envelopeId != null) {
            synchronized(sendLock) {
                if (sendingEnvelope == sent.envelopeId && outboundTotal > 0) {
                    outboundDone = (outboundDone + 1).coerceAtMost(outboundTotal)
                }
            }
        }
        linkWriteErrors[link]?.set(0)
        linkTimeouts[link]?.set(0)
        main.post { pump(link) }
        if (sent.device != null) {
            val now = System.currentTimeMillis()
            linkActivity[sent.device.address] = now
            lastLinkActivity = now
        }
        pump(link)
    }

    private fun armSendWatchdog(packet: OutgoingPacket, generation: Long) {
        main.postDelayed({
            val queue = queuedByLink[packet.link] ?: return@postDelayed
            val timedOut = synchronized(queue) {
                if (queuedByLink[packet.link] !== queue ||
                    inFlightByLink[packet.link] !== packet ||
                    linkGenerations[packet.link]?.get() != generation
                ) {
                    false
                } else {
                    inFlightByLink.remove(packet.link)
                    sending.remove(packet.link)
                    true
                }
            }
            if (!timedOut) return@postDelayed
            Log.w(TAG, "BLE send callback lost")
            val timeouts = linkTimeouts.computeIfAbsent(packet.link) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            if (timeouts >= 2) {
                linkTimeouts[packet.link]?.set(0)
                main.post { if (packet.link == CENTRAL_TARGET) recycleClient() else scheduleRestart() }
                return@postDelayed
            }
            main.postDelayed({ pump(packet.link) }, 100L)
        }, 5_000L)
    }

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
                        if (expected == 0 || expected > 32 * 1024) {
                            reset()
                            Log.w(TAG, "BLE frame length rejected: $expected")
                            return
                        }
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
        if (Thread.currentThread().name != "pagerapp-ble-state") {
            bleExecutor.execute { onJson(raw, source) }
            return
        }
        val now = System.currentTimeMillis()
        lastLinkActivity = now
        if (source != null) linkActivity[source.address] = now
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (json.optString("t")) {
            "hello" -> {
                if (source == null) haveSentClient = false
                else source.address.let(haveSentDevices::remove)
                sendHaveTo(source)
            }
            "have" -> {
                val remote = json.optJSONArray("ids")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty().toSet()
                val local = store?.p2pQueue()?.map { it.id }?.toSet().orEmpty()
                val want = remote - local
                if (want.isNotEmpty()) sendJson(JSONObject().put("t", "want").put("ids", JSONArray(want.toList())), source)
            }
            "want" -> {
                lastWantAt = System.currentTimeMillis()
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
                if (transfer.isNotBlank() && id.isNotBlank() && total in 1..512 && sha.matches(Regex("[0-9a-fA-F]{64}"))) {
                    val peerKey = source?.address ?: CENTRAL_TARGET
                    val existing = transferSources.entries.count { (key, sourceKey) ->
                        sourceKey == peerKey && key != transfer
                    }
                    if (existing < 4) {
                        val assembler = runCatching { P2PAssembler(transfer, total, sha) }.getOrNull()
                        if (assembler != null) {
                            val old = assemblers.putIfAbsent(transfer, assembler)
                            if (old == null) {
                                assemblingBytes[transfer] = java.util.concurrent.atomic.AtomicInteger()
                                transferEnvelopes[transfer] = id
                                transferSources[transfer] = peerKey
                                main.postDelayed({
                                    if (assemblers.remove(transfer, assembler)) {
                                        assemblingBytes.remove(transfer)
                                        transferEnvelopes.remove(transfer)
                                        transferSources.remove(transfer)
                                    }
                                }, 60_000L)
                            }
                        }
                    }
                }
            }
            "chunk" -> {
                val transfer = json.optString("transfer")
                val sourceAddress = source?.address
                if (transferSources[transfer] != (sourceAddress ?: CENTRAL_TARGET)) return
                val assembler = assemblers[transfer] ?: return
                val data = runCatching { Base64.decode(json.optString("data"), Base64.NO_WRAP) }.getOrNull() ?: return
                val buffered = assemblingBytes[transfer] ?: return
                val invalid = synchronized(assembler) {
                    val previousSize = assembler.bufferedBytes
                    val accepted = assembler.accept(
                        P2PChunk(transfer, json.optInt("seq"), json.optInt("total"), data, json.optLong("crc32")),
                    )
                    val addedBytes = assembler.bufferedBytes - previousSize
                    !accepted || buffered.addAndGet(addedBytes) > 128 * 1024
                }
                if (invalid) {
                    assemblers.remove(transfer, assembler)
                    assemblingBytes.remove(transfer, buffered)
                    transferEnvelopes.remove(transfer)
                    transferSources.remove(transfer)
                }
            }
            "done" -> {
                val transfer = json.optString("transfer")
                if (transferSources[transfer] == (source?.address ?: CENTRAL_TARGET)) finishTransfer(transfer)
            }
            "nack" -> {
                val id = json.optString("envelope")
                val target = source?.address ?: CENTRAL_TARGET
                if (id.isNotBlank() && target in envelopeTargets[id].orEmpty()) retryEnvelope(id, target)
            }
            "ack" -> {
                val id = json.optString("envelope")
                val target = source?.address ?: CENTRAL_TARGET
                val selected = synchronized(sendLock) { sendingTarget to sendingEnvelope }
                val selectedTarget = selected.first
                val selectedEnvelope = selected.second
                val queuedEnvelope = store?.p2pQueue()?.firstOrNull { it.id == id }
                if (id.isNotBlank() && target == selectedTarget && selectedEnvelope == id &&
                    queuedEnvelope != null && json.optBoolean("stored") &&
                    json.optString("sha256") == queuedEnvelope.payloadSha256()
                ) {
                    synchronized(sendLock) {
                        lastCompletedTotal = outboundTotal
                        lastCompletedDone = outboundTotal
                        lastCompletedAt = System.currentTimeMillis()
                        lastCompletedDirection = P2PDirection.SENDING
                        outboundTotal = 0
                        outboundDone = 0
                    }
                    if (json.optBoolean("delivered")) {
                        Log.i(TAG, "BLE ACK delivered envelope $id")
                        store?.markP2PDelivered(id)
                    } else {
                        Log.i(TAG, "BLE ACK custody envelope $id")
                        store?.markP2PCustody(id)
                    }
                    envelopeStored(id, target)
                }
            }
        }
    }

    private fun queueEnvelopes(envelopes: List<P2PEnvelope>, device: BluetoothDevice?) {
        val target = device?.address ?: CENTRAL_TARGET
        synchronized(sendLock) {
            envelopes.forEach { envelope ->
                val targets = envelopeTargets.getOrPut(envelope.id) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                targets.add(target)
                sendTargets.putIfAbsent(envelope.id, target)
                if (sendQueue.none { it.id == envelope.id } && sendingEnvelope != envelope.id) sendQueue.addLast(envelope)
            }
        }
        sendNextEnvelope()
    }

    private fun sendNextEnvelope() {
        val selected = synchronized(sendLock) {
            if (sendingEnvelope != null) return
            val available = sendQueue.indexOfFirst { envelope ->
                envelopeTargets[envelope.id].orEmpty().any { linkReady(deviceForTarget(it)) }
            }
            if (available < 0) return
            repeat(available) { sendQueue.addLast(sendQueue.removeFirst()) }
            val next = sendQueue.removeFirst()
            val target = envelopeTargets[next.id].orEmpty().firstOrNull { linkReady(deviceForTarget(it)) } ?: return
            val device = deviceForTarget(target)
            sendTargets[next.id] = target
            sendingEnvelope = next.id
            sendingTarget = target
            sendingSince = System.currentTimeMillis()
            sendAttempts[next.id] = (sendAttempts[next.id] ?: 0) + 1
            next to device
        }
        sendEnvelope(selected.first, selected.second)
    }

    private fun deviceForTarget(target: String): BluetoothDevice? =
        if (target == CENTRAL_TARGET) null else connectedDevices[target]

    private fun retryEnvelope(id: String, target: String) {
        var queued = false
        synchronized(sendLock) {
            val attempts = sendAttempts[id] ?: 0
            if (attempts >= 3 || target !in envelopeTargets[id].orEmpty()) return
            if (sendingEnvelope == id && sendingTarget == target) {
                sendingEnvelope = null
                sendingTarget = null
                sendTargets.remove(id)
                sendQueue.removeAll { it.id == id }
                store?.p2pQueue()?.firstOrNull { it.id == id }?.let {
                    sendQueue.addFirst(it)
                    queued = true
                }
            } else if (sendingEnvelope != id && sendQueue.none { it.id == id }) {
                store?.p2pQueue()?.firstOrNull { it.id == id }?.let {
                    sendQueue.addLast(it)
                    queued = true
                }
            }
        }
        if (queued) main.post { sendNextEnvelope() }
    }

    private fun envelopeStored(id: String, target: String) {
        synchronized(sendLock) {
            envelopeTargets[id]?.remove(target)
            if (envelopeTargets[id].isNullOrEmpty()) {
                envelopeTargets.remove(id)
                sendAttempts.remove(id)
            }
            if (sendingEnvelope == id && sendingTarget == target) {
                sendingEnvelope = null
                sendingTarget = null
                sendTargets.remove(id)
            }
            if (envelopeTargets[id].isNullOrEmpty()) sendQueue.removeAll { it.id == id }
        }
        main.post { sendNextEnvelope() }
    }

    private fun sendEnvelope(envelope: P2PEnvelope, device: BluetoothDevice?) {
        val bytes = envelope.toJson().toString().toByteArray(Charsets.UTF_8)
        val transfer = UUID.randomUUID().toString()
        val sha = sha256Hex(bytes)
        val payloadSize = ((if (device == null) centralMtu else deviceMtu[device.address] ?: 23) - 3).coerceAtLeast(20)
        val chunkSize = (minOf(240, payloadSize * 3 / 4 - 128)).coerceAtLeast(32)
        val chunks = P2PChunker.split(transfer, bytes, chunkSize)
        if (chunks.isEmpty() || chunks.size > 512) {
            Log.w(TAG, "BLE envelope is not transferable: ${envelope.id} chunks=${chunks.size}")
            synchronized(sendLock) {
                outboundTotal = 0
                outboundDone = 0
                val target = sendingTarget
                if (sendingEnvelope == envelope.id) {
                    sendingEnvelope = null
                    sendingTarget = null
                    if (target != null) envelopeTargets[envelope.id]?.remove(target)
                    sendTargets.remove(envelope.id)
                    if (envelopeTargets[envelope.id].isNullOrEmpty()) envelopeTargets.remove(envelope.id)
                }
            }
            main.post { sendNextEnvelope() }
            return
        }
        Log.i(TAG, "BLE transfer start ${envelope.id} bytes=${bytes.size} chunks=${chunks.size}")
        synchronized(sendLock) {
            outboundTotal = chunks.size
            outboundDone = 0
        }
        val messages = buildList {
            add(JSONObject().put("t", "start").put("transfer", transfer).put("envelope", envelope.id).put("total", chunks.size).put("sha256", sha))
            chunks.forEach { chunk ->
                add(JSONObject().put("t", "chunk").put("transfer", transfer).put("seq", chunk.seq).put("total", chunk.total).put("crc32", chunk.crc32).put("data", Base64.encodeToString(chunk.data, Base64.NO_WRAP)))
            }
            add(JSONObject().put("t", "done").put("transfer", transfer))
        }
        val link = device?.address ?: CENTRAL_TARGET
        val frameLimit = ((if (device == null) centralMtu else deviceMtu[link] ?: 23) - 3).coerceAtLeast(20)
        val queue = queuedByLink.computeIfAbsent(link) { ConcurrentLinkedQueue() }
        synchronized(queue) {
            messages.forEach { message ->
                val type = message.optString("t")
                val chunkSeq = if (type == "chunk") message.optInt("seq") else null
                val chunkTotal = if (type == "chunk") message.optInt("total") else null
                val envelopeId = if (type == "start" || type == "chunk" || type == "done") {
                    envelope.id
                } else {
                    null
                }
                val payload = message.toString().toByteArray(Charsets.UTF_8)
                val frame = ByteArray(payload.size + 2)
                frame[0] = ((payload.size ushr 8) and 0xff).toByte()
                frame[1] = (payload.size and 0xff).toByte()
                System.arraycopy(payload, 0, frame, 2, payload.size)
                var offset = 0
                while (offset < frame.size) {
                    val end = minOf(offset + frameLimit, frame.size)
                    queue.add(
                        OutgoingPacket(
                            bytes = frame.copyOfRange(offset, end),
                            device = device,
                            link = link,
                            envelopeId = envelopeId,
                            chunkSeq = chunkSeq,
                            chunkTotal = chunkTotal,
                            lastChunkFragment = type == "chunk" && end == frame.size,
                        ),
                    )
                    offset = end
                }
            }
        }
        pump(link)
    }

    private fun finishTransfer(transfer: String) {
        if (Thread.currentThread().name != "pagerapp-ble-state") {
            bleExecutor.execute { finishTransfer(transfer) }
            return
        }
        val assembler = assemblers[transfer] ?: return
        val bytes = synchronized(assembler) { assembler.finish() }
        if (bytes == null) {
            assemblers.remove(transfer, assembler)
            val id = transferEnvelopes.remove(transfer).orEmpty()
            assemblingBytes.remove(transfer)
            val sourceKey = transferSources.remove(transfer) ?: return
            val source = if (sourceKey == CENTRAL_TARGET) null else connectedDevices[sourceKey] ?: return
            sendAck(id, "", false, source)
            sendNack(id, source)
            return
        }
        assemblers.remove(transfer, assembler)
        val id = transferEnvelopes.remove(transfer).orEmpty()
        assemblingBytes.remove(transfer)
        val sourceKey = transferSources.remove(transfer) ?: return
        val source = if (sourceKey == CENTRAL_TARGET) null else connectedDevices[sourceKey] ?: return
        val envelope = runCatching { p2pEnvelopeFromJson(JSONObject(String(bytes, Charsets.UTF_8))) }.getOrNull()
        if (envelope == null || envelope.id != id || envelope.isExpired()) {
            sendAck(id, "", false, source)
            return
        }
        val st = store ?: return
        val preexisting = st.p2pQueue().firstOrNull { it.id == envelope.id }
        if (preexisting != null && preexisting.payloadSha256() != envelope.payloadSha256()) {
            Log.w(TAG, "BLE envelope id collision rejected: ${envelope.id}")
            sendAck(envelope.id, envelope.payloadSha256(), false, source)
            return
        }
        st.enqueueP2P(envelope)
        val localTarget = isLocalP2PTarget(envelope.to, st.localRouteHashes())
        val deliveredLocally = if (preexisting != null) {
            preexisting.to == envelope.to
        } else if (localTarget) {
            runCatching { P2PReceiver.deliver(context ?: return, envelope) }
                .onFailure { Log.w(TAG, "BLE local delivery failed: ${it.message}") }
                .getOrDefault(false)
        } else {
            false
        }
        if (deliveredLocally) st.markP2PDelivered(envelope.id)
        lastCompletedAt = System.currentTimeMillis()
        lastCompletedDone = assembler.total
        lastCompletedTotal = assembler.total
        lastCompletedDirection = P2PDirection.RECEIVING
        Log.i(TAG, "BLE stored ${envelope.id}; to=${envelope.to.take(8)} localTarget=$localTarget localDelivery=$deliveredLocally")
        sendAck(envelope.id, envelope.payloadSha256(), true, source, deliveredLocally)
    }

    private fun sendNack(id: String, device: BluetoothDevice?) {
        if (id.isNotBlank()) sendJson(JSONObject().put("t", "nack").put("envelope", id), device)
    }

    private fun sendAck(id: String, sha: String, stored: Boolean, device: BluetoothDevice?, delivered: Boolean = false) {
        if (id.isBlank()) return
        sendJson(JSONObject().put("t", "ack").put("envelope", id).put("sha256", sha).put("stored", stored).put("delivered", delivered), device)
    }
}
