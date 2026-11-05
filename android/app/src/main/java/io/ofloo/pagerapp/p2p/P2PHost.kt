package io.ofloo.pagerapp.p2p

import android.content.Context
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.poll.PollService

/**
 * Chooses which foreground service hosts Bluetooth LE.
 *
 * When reliable delivery is on, [PollService] is already running and shows the one notification
 * the user expects; nearby sync rides along there instead of adding a second "nearby sync"
 * notification. In the quiet modes nothing else is running, so [P2PService] hosts it itself.
 */
object P2PHost {

    fun refresh(context: Context, store: Store) {
        if (!store.p2pModeEnabled || store.silenceMode) {
            P2PService.stop(context)
            BleP2P.stop()
            return
        }
        if (store.reliableDelivery) {
            P2PService.stop(context)
            // PollService.onCreate starts BleP2P when P2P mode is on.
            PollService.start(context)
        } else {
            P2PService.start(context)
        }
    }
}
