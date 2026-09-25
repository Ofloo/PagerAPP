package io.ofloo.pagerapp.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the ACK notification action: posts an acknowledgement back through PagerMCP. */
class AckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pageId = intent.getStringExtra(EXTRA_PAGE_ID) ?: return
        Notifier.sendAck(
            context = context,
            pageId = pageId,
            jobId = intent.getStringExtra(EXTRA_JOB_ID),
            ackUuid = intent.getStringExtra(EXTRA_ACK_UUID),
        )
    }

    companion object {
        const val EXTRA_PAGE_ID = "page_id"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_ACK_UUID = "ack_uuid"
    }
}
