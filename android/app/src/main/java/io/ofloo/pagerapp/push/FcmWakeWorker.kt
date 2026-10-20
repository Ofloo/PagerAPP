package io.ofloo.pagerapp.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.poll.Poller

class FcmWakeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!Store(applicationContext).fcmEnabled) return Result.success()
        if (Poller.runFcmWake(applicationContext, inputData.getString(KEY_MAILBOX))) return Result.success()
        return Result.retry()
    }

    companion object {
        private const val KEY_MAILBOX = "mailbox"

        fun enqueue(context: Context, mailbox: String?) {
            val request = OneTimeWorkRequestBuilder<FcmWakeWorker>()
                .setInputData(workDataOf(KEY_MAILBOX to mailbox))
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
