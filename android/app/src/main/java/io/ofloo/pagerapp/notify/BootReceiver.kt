package io.ofloo.pagerapp.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.poll.PollService

/** Restarts polling after a reboot when a mailbox is configured. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = Store(context)
        if (store.uuid != null && !store.silenceMode) {
            PollService.start(context)
        }
    }
}
