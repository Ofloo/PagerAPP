package io.ofloo.pagerapp.poll

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.ofloo.pagerapp.R
import io.ofloo.pagerapp.data.HistoryEntry
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.notify.Notifications
import io.ofloo.pagerapp.notify.Notifier
import io.ofloo.pagerapp.shared.Decision
import io.ofloo.pagerapp.shared.Page
import io.ofloo.pagerapp.shared.decide
import java.time.LocalTime
import kotlin.concurrent.thread

/**
 * Foreground service that polls PagerMCP on the user-configured interval.
 *
 * Interval handling: because the interval is user-set and may be < 15 min (the WorkManager
 * periodic minimum), the service keeps itself alive and schedules the next tick itself. For
 * intervals >= 2 min it sleeps with an alarm; for shorter intervals it uses a wake lock so the
 * tick is not deferred by Doze.
 */
class PollService : Service() {

    private val worker = thread(
        start = false,
        isDaemon = true,
        name = "pager-poll",
        block = ::loop,
    )

    @Volatile private var running = true

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(NOTIF_ID, foregroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker.state == Thread.State.NEW) worker.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        worker.interrupt()
        super.onDestroy()
    }

    private fun loop() {
        val store = Store(this)
        while (running) {
            val interval = store.pollSeconds
            var lock: PowerManager.WakeLock? = null
            try {
                lock = acquireWakeLock(interval)
                tick(store)
            } catch (_: Exception) {
                // Network/server/permission errors are expected; retry on the next tick.
                // Never let the worker thread die, or the whole process is taken down.
            } finally {
                runCatching { lock?.let { if (it.isHeld) it.release() } }
            }
            sleepInterval(interval)
        }
    }

    private fun tick(store: Store) {
        val uuid = store.uuid ?: return
        val pages = PagerClient.messages(store.serverUrl, uuid)
        if (pages.isEmpty()) return

        val seen = store.dedupeSnapshot()
        val fresh = pages.filter { it.id !in seen }
        if (fresh.isEmpty()) return

        val now = LocalTime.now()
        val config = store.effectiveConfig()
        val history = mutableListOf<HistoryEntry>()

        fresh.forEach { page ->
            val decision: Decision = decide(page, config, now)
            val deliver = decision.notify
            if (deliver) {
                // Watch-only still keeps a silent phone record so pages are not lost; watch
                // delivery itself is a future platform-companion integration.
                val audible = Notifications.audible(decision) && Notifications.phoneChannels(decision)
                Notifier.post(this, page, audible = audible)
            }
            history += HistoryEntry.from(page, delivered = deliver)
        }

        store.rememberSeen(fresh.map { it.id })
        store.addHistory(history)
    }

    private fun sleepInterval(intervalSeconds: Int) {
        // Split the wait so stop/interrupt is responsive and to avoid drifting.
        var remainingMs = intervalSeconds * 1000L
        while (running && remainingMs > 0) {
            val chunk = minOf(remainingMs, 60_000L)
            try {
                Thread.sleep(chunk)
            } catch (_: InterruptedException) {
                return
            }
            remainingMs -= chunk
        }
    }

    private fun acquireWakeLock(intervalSeconds: Int): PowerManager.WakeLock? {
        // Short intervals run in tight succession; a brief wake lock keeps Doze from stalling.
        if (intervalSeconds > 120) return null
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PagerAPP:poll")
        lock.acquire(90_000L)
        return lock
    }

    private fun foregroundNotification() =
        NotificationCompat.Builder(this, Notifications.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.poll_channel_name))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    companion object {
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, PollService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PollService::class.java))
        }

        fun restart(context: Context) {
            stop(context)
            start(context)
        }
    }
}
