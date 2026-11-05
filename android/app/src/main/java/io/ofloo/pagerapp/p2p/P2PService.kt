package io.ofloo.pagerapp.p2p

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.ofloo.pagerapp.R
import io.ofloo.pagerapp.notify.Notifications

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

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundCompat()
        // A sticky restart (or an app update) must not resurrect this second foreground service:
        // in reliable mode PollService already hosts BLE, and with P2P off it must not run at all.
        val store = io.ofloo.pagerapp.data.Store(this)
        if (!store.p2pModeEnabled || store.reliableDelivery) {
            stopSelf()
            return
        }
        hosting = true
        BleP2P.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val store = io.ofloo.pagerapp.data.Store(this)
        if (!store.p2pModeEnabled || store.reliableDelivery) {
            stopSelf()
            return START_NOT_STICKY
        }
        hosting = true
        BleP2P.start(this)
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.p2p_channel_name))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Swiping the task away must not stop nearby sync; the user toggles it off in Settings. */
    override fun onTaskRemoved(rootIntent: Intent?) = super.onTaskRemoved(rootIntent)

    override fun onDestroy() {
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
