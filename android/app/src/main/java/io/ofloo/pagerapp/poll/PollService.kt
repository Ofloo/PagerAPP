package io.ofloo.pagerapp.poll

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ofloo.pagerapp.R
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.notify.Notifications
import io.ofloo.pagerapp.shared.Contact
import kotlin.concurrent.thread

/**
 * Foreground service used in "reliable" mode.
 *
 * Two cooperating loops:
 * - a **stream** thread holding `GET /mailboxes/{id}/events` (SSE) on the private mailbox, so a
 *   page arrives in about a second;
 * - a **poll** thread on the user's interval, covering every contact channel and acting as a
 *   backstop if the stream cannot be established (blocked proxy, no SSE support).
 *
 * Both funnel into [Poller.handlePages], which deduplicates, so an overlap between the two never
 * shows a page twice.
 */
class PollService : Service() {

    private val streamThread = thread(
        start = false,
        isDaemon = true,
        name = "pager-streams",
        block = ::streamManagerLoop,
    )

    /** One live stream per mailbox: the private one plus every contact channel. */
    private val streams = java.util.concurrent.ConcurrentHashMap<String, Thread>()

    private val pollThread = thread(
        start = false,
        isDaemon = true,
        name = "pager-poll",
        block = ::pollLoop,
    )

    @Volatile private var running = true

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(NOTIF_ID, foregroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (streamThread.state == Thread.State.NEW) streamThread.start()
        if (pollThread.state == Thread.State.NEW) pollThread.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        streamThread.interrupt()
        pollThread.interrupt()
        super.onDestroy()
    }

    /**
     * Holds a live SSE stream on the private mailbox only: that is where system pages (CI, alerts)
     * land and where a second of latency matters. Chat channels are left to the interval poll —
     * an open conversation polls itself every couple of seconds — which keeps the number of
     * connections small and predictable.
     */
    private fun streamManagerLoop() {
        while (running) {
            val store = Store(this)
            val own = store.uuid
            if (own != null && streams[own]?.isAlive != true) {
                streams[own] = thread(
                    start = true,
                    isDaemon = true,
                    name = "pager-stream-$own",
                    block = { streamMailbox(own, null) },
                )
            }
            if (!sleepQuietly(30_000)) return
        }
    }

    /** Holds one mailbox's stream, reconnecting with backoff and sweeping when it drops. */
    private fun streamMailbox(mailbox: String, contact: Contact?) {
        var backoffMs = 1_000L
        while (running) {
            val store = Store(this)
            try {
                PagerClient.streamEvents(store.serverUrl, mailbox) { page ->
                    Poller.handlePages(this, mailbox, store.serverUrl, contact, listOf(page))
                }
                backoffMs = 1_000L
            } catch (e: Exception) {
                Log.w(TAG, "sse $mailbox ended: ${e.message}")
            }
            if (!running) return
            // Stream gone: sweep once (the server replays anything unacked) and back off.
            runCatching { Poller.pollMailbox(this, mailbox, store.serverUrl, contact) }
            if (!sleepQuietly(backoffMs)) return
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /** Interval polling across the private mailbox and every contact channel. */
    private fun pollLoop() {
        while (running) {
            val interval = PollInterval.seconds(this)
            var lock: PowerManager.WakeLock? = null
            try {
                lock = acquireWakeLock(interval)
                Poller.pollOnce(this)
            } catch (_: Exception) {
                // Network/server/permission errors are expected; retry on the next tick.
                // Never let the worker thread die, or the whole process is taken down.
            } finally {
                runCatching { lock?.let { if (it.isHeld) it.release() } }
            }
            if (!sleepQuietly(interval * 1000L)) return
        }
    }

    /** Sleeps in chunks so stop/interrupt stays responsive. Returns false when interrupted. */
    private fun sleepQuietly(totalMs: Long): Boolean {
        var remaining = totalMs
        while (running && remaining > 0) {
            val chunk = minOf(remaining, 60_000L)
            try {
                Thread.sleep(chunk)
            } catch (_: InterruptedException) {
                return false
            }
            remaining -= chunk
        }
        return running
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
        private const val TAG = "PagerAPP"
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
