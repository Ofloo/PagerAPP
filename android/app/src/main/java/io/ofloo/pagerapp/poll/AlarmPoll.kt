package io.ofloo.pagerapp.poll

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.ofloo.pagerapp.data.Store
import kotlin.concurrent.thread

/**
 * Wake-yourself-up polling: the third delivery mode.
 *
 * The app sets an alarm for the next tick, is woken by the system, fetches its mailboxes, and goes
 * back to sleep — no connection to hold open and therefore **no notification**, and nothing asked of
 * Google or of a distributor. That is the only way to keep working in the background on a device that
 * defers push to a closed app (which is what we measured: 2.5 minutes for an FCM wake-up).
 *
 * Android stretches alarms in Doze to roughly nine minutes, and on 12+ an *exact* alarm needs the
 * user to grant "Alarms & reminders"; without it we ask for the closest approximation instead of
 * silently doing nothing.
 */
object AlarmPoll {

    private const val TAG = "PagerAPP"
    private const val REQUEST = 0x9A6

    private fun intent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** (Re)arms the next tick. Safe to call repeatedly: it replaces the pending alarm. */
    fun arm(context: Context, seconds: Int) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + seconds.coerceAtLeast(30) * 1000L
        val pi = intent(context)
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // No exact-alarm permission: still ask to be woken, just without the guarantee.
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.onFailure { Log.w(TAG, "alarm arm failed: ${it.message}") }
    }

    fun disarm(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarm.cancel(intent(context)) }
    }

    /** True when the system will let us set exact alarms (always true below Android 12). */
    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
}

/**
 * One alarm tick: fetch, then arm the next one. The fetch runs on its own thread because a receiver's
 * onReceive is on the main thread and must not touch the network.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "pagerapp-alarm") {
            try {
                runCatching { Poller.pollOnce(app) }
                val store = Store(app)
                if (store.alarmPolling && !store.silenceMode) AlarmPoll.arm(app, store.pollSeconds)
            } finally {
                pending.finish()
            }
        }
    }
}
