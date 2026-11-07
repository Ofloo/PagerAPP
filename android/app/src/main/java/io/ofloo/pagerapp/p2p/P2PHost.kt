package io.ofloo.pagerapp.p2p

import android.content.Context
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.poll.PollService

/**
 * Chooses which foreground service hosts Bluetooth LE.
 *
 * When reliable delivery is on, [PollService] hosts BLE so there is only one foreground notification.
 * In background-poll mode, [P2PService] hosts BLE; alarm polling starts BLE for a bounded window.
 */
object P2PHost {

    fun refresh(context: Context, store: Store) {
        if (store.alarmPolling) {
            P2PService.stop(context)
            PollService.stop(context)
            if (!store.p2pModeEnabled || store.silenceMode) BleP2P.stop()
            return
        }
        if (store.reliableDelivery) {
            P2PService.stop(context)
            PollService.start(context)
            if (store.p2pModeEnabled) BleP2P.start(context) else BleP2P.stop()
            return
        }
        PollService.stop(context)
        if (store.p2pModeEnabled && !store.silenceMode) P2PService.start(context) else P2PService.stop(context)
    }
}
