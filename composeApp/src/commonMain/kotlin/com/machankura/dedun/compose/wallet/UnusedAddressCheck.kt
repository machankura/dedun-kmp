package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether an onchain payout address has ever been paid: `docs/group-payout-destination.md`, D7.
 *
 * `startScriptHashSubscription` rather than `getScriptHashHistory`: lightning-kmp answers a failed
 * history call with an empty list, so an address checked while the server is down reads as never
 * used. A subscription's status is null exactly when the script has no history, the mempool
 * included, and the call waits for a server rather than failing -- so [TIMEOUT] is what turns no
 * server into [Usage.Unknown] (Spike B, against regtest's electrs).
 */
object UnusedAddressCheck {

    sealed interface Usage {
        data object Unused : Usage
        /** Paid at least once, confirmed or in the mempool. */
        data object Used : Usage
        /** No answer in time. Never read as unused. */
        data object Unknown : Usage
        /** One of the group's own addresses: paying it is the group paying itself. */
        data object TheGroups : Usage
    }

    val TIMEOUT: Duration = 15.seconds

    /**
     * What [electrum] says about [script]. [isTheGroups] is asked first, and needs no server: the
     * group's wallet already knows its addresses.
     */
    suspend fun check(
        electrum: IElectrumClient,
        script: ByteVector,
        isTheGroups: (ByteVector) -> Boolean = { false },
        timeout: Duration = TIMEOUT,
    ): Usage {
        if (isTheGroups(script)) return Usage.TheGroups
        val scriptHash = Crypto.sha256(script).byteVector32().reversed()
        val answer = withTimeoutOrNull(timeout) {
            runCatching { electrum.startScriptHashSubscription(scriptHash) }.getOrNull()
        } ?: return Usage.Unknown
        return if (answer.status == null) Usage.Unused else Usage.Used
    }
}
