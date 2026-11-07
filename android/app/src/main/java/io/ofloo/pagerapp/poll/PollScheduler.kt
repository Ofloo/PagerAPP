package io.ofloo.pagerapp.poll

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.ofloo.pagerapp.data.Store
import java.util.concurrent.TimeUnit

/**
 * Decides how polling happens:
 *
 * - **Reliable** ([Store.reliableDelivery]): the foreground service, every 30s–5min, with one
 *   low-importance notification.
 * - **Background**: periodic WorkManager work, no notification, but Android enforces a 15-minute
 *   minimum and Doze may delay it further.
 */
object PollScheduler {

    private const val WORK_NAME = "pagerapp-poll"

    /** Android's floor for periodic background work. */
    const val MIN_BACKGROUND_MINUTES = 15L

    /** (Re)configures polling from the current settings. Safe to call on every app start. */
    fun apply(context: Context, store: Store) {
        if (store.alarmPolling) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            PollService.stop(context)
            io.ofloo.pagerapp.p2p.P2PHost.refresh(context, store)
            AlarmPoll.arm(context, store.pollSeconds)
            return
        }
        AlarmPoll.disarm(context)
        if (store.reliableDelivery) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            io.ofloo.pagerapp.p2p.P2PHost.refresh(context, store)
        } else {
            io.ofloo.pagerapp.p2p.P2PHost.refresh(context, store)
            if (!store.p2pModeEnabled) io.ofloo.pagerapp.p2p.BleP2P.stop()
            val minutes = maxOf(MIN_BACKGROUND_MINUTES, store.pollSeconds / 60L)
            val request =
                PeriodicWorkRequestBuilder<PollWorker>(minutes, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    /** Stops all polling (silence mode, or no mailbox configured). */
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        PollService.stop(context)
        AlarmPoll.disarm(context)
    }
}
