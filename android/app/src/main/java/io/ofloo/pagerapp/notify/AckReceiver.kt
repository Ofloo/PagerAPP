package io.ofloo.pagerapp.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Handles the ACK notification action: posts an acknowledgement back through PagerMCP.
 *
 * Network I/O must not run on the main thread (a BroadcastReceiver's onReceive does), so the
 * work is handed to a background thread and the broadcast is kept alive via goAsync().
 */
class AckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pageId = intent.getStringExtra(EXTRA_PAGE_ID) ?: return
        val pending = goAsync()
        val appContext = context.applicationContext
        thread(name = "pager-ack") {
            try {
                Notifier.sendAck(
                    context = appContext,
                    pageId = pageId,
                    jobId = intent.getStringExtra(EXTRA_JOB_ID),
                    ackUuid = intent.getStringExtra(EXTRA_ACK_UUID),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PAGE_ID = "page_id"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_ACK_UUID = "ack_uuid"
    }
}
