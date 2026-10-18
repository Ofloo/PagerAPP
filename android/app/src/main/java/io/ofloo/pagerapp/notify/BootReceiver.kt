package io.ofloo.pagerapp.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.poll.PollScheduler

/**
 * Restarts polling after a reboot when a mailbox is configured.
 *
 * It goes through [PollScheduler] rather than starting the service directly, so a device that
 * uses the quiet background mode never gets a foreground service (and its notification) on boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = Store(context)
        if (store.uuid != null && !store.silenceMode) {
            PollScheduler.apply(context, store)
        }
    }
}
