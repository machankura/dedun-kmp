package com.machankura.dedun.compose.network.relays

import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage

/**
 * The slice of the relay layer a subscription that stays open needs.
 *
 * Narrower than [RelaysSocketManager] on purpose. That class observes the active wallet and
 * starts collecting relay lists in its `init`, so it cannot be stood up in a test at all —
 * which would otherwise leave the reconcile loop that keeps group subscriptions matching group
 * membership with no coverage but the pure helpers underneath it.
 */
interface LiveSubscriptionTransport {

    /** @see RelayPool.openLiveSubscription */
    suspend fun openLiveSubscription(reqCommand: ReqCmd, relayUrl: String): Flow<NostrIncomingMessage>

    /** @see RelayPool.updateLiveSubscription */
    suspend fun updateLiveSubscription(reqCommand: ReqCmd, relayUrl: String)

    /** @see RelayPool.closeLiveSubscription */
    suspend fun closeLiveSubscription(subId: String, relayUrl: String)

    /** @see RelayPool.reconnectAll */
    suspend fun reconnectAll()
}
