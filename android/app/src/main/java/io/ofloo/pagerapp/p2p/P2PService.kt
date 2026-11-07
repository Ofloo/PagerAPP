package io.ofloo.pagerapp.p2p

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import io.ofloo.pagerapp.notify.Notifications
import io.ofloo.pagerapp.notify.ServiceNotification

/**
 * Keeps the Bluetooth LE transport alive while the app is not visible.
 *
 * A foreground service is required here: since Android 8 a plain background service is stopped
 * once the app is no longer in the foreground, and OEM power management (OnePlus/HANS, Doze)
 * freezes the process when the screen goes off. Without one, nearby sync only worked while
 * PagerAPP was on screen.
 */
class P2PService : Service() {

    /** True only when this service is the one actually hosting BLE. */
    private var hosting = false

    private val main = Handler(Looper.getMainLooper())
    private var lastText: String? = null
    private val notificationTick = object : Runnable {
        override fun run() {
            refreshNotification()
            main.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = io.ofloo.pagerapp.data.Store(this)
        if (!store.p2pModeEnabled || store.silenceMode || store.reliableDelivery || store.alarmPolling) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        hosting = true
        BleP2P.start(this)
        main.removeCallbacks(notificationTick)
        main.postDelayed(notificationTick, 1_000L)
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val text = ServiceNotification.text(this, p2pEnabled = true)
        lastText = text
        val notification = ServiceNotification.build(this, text, p2pEnabled = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun refreshNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val text = ServiceNotification.text(this, p2pEnabled = true)
        if (text == lastText) return
        lastText = text
        NotificationManagerCompat.from(this).notify(
            NOTIF_ID,
            ServiceNotification.build(this, text, p2pEnabled = true),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Swiping the task away must not stop nearby sync; the user toggles it off in Settings. */
    override fun onTaskRemoved(rootIntent: Intent?) = super.onTaskRemoved(rootIntent)

    override fun onDestroy() {
        main.removeCallbacks(notificationTick)
        // Do not stop BLE when PollService is the host: this service may be stopping itself
        // (sticky restart, update) while PollService is using the same BleP2P singleton.
        if (hosting) BleP2P.stop()
        hosting = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2
        fun start(context: Context) {
            val intent = Intent(context, P2PService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, P2PService::class.java))
        }
    }
}
