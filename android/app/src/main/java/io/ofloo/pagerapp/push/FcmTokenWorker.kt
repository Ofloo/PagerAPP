package io.ofloo.pagerapp.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.ofloo.pagerapp.data.Store

class FcmTokenWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val token = inputData.getString(KEY_TOKEN)
        if (!store.fcmEnabled) return Result.success()
        val registered = PushManager.reconcileFcm(applicationContext, token = token, force = true)
        return if (registered > 0) Result.success() else Result.retry()
    }

    companion object {
        private const val KEY_TOKEN = "token"

        fun enqueue(context: Context, token: String) {
            val request = OneTimeWorkRequestBuilder<FcmTokenWorker>()
                .setInputData(workDataOf(KEY_TOKEN to token))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "pagerapp-fcm-token",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
