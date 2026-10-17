package io.ofloo.pagerapp.poll

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Background polling pass. Used when the user prefers no persistent notification; Android caps
 * periodic work at a 15-minute minimum, so delivery is slower than the foreground service.
 */
class PollWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        Poller.pollOnce(applicationContext)
        return Result.success()
    }
}
