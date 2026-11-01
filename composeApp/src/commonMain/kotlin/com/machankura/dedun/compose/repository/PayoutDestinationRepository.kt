package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.network.LightningAddressResolver
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.Sendable
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.UnusedAddressCheck
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain

/**
 * Where a group pays each of its members, for the screens: setting the active profile's, and
 * reading everybody's. `docs/group-payout-destination.md`. The key, the Tor setting and the
 * network are the repository's, never the screen's.
 */
interface PayoutDestinationRepository {

    /** What the payout screen needs to know about [chatRoomId] for the active profile. */
    data class Standing(
        /** A Marmot group that is still going: the only kind of room that pays anybody. */
        val isGroup: Boolean,
        /** Whether this profile can sign a destination: it holds a nostr key. */
        val canSet: Boolean,
        /** The active profile's destination in this group, or null for none or withdrawn. */
        val current: GroupPayoutDestination?,
        /** The `lud16` of the active profile's own kind 0, the first time's starting value. */
        val profileLightningAddress: String?,
    )

    suspend fun standing(chatRoomId: String, chain: Chain): Standing?

    /** Every current member's destination in [chatRoomId] on [chain]. */
    suspend fun destinationsIn(chatRoomId: String, chain: Chain): Map<HexKey, GroupPayoutDestination>

    /** [address]'s pay request, unless the active profile has Tor on (D6). */
    suspend fun resolve(address: LightningAddress): LightningAddressResolver.Resolution

    /** Whether [script] has been paid on [chain], or is one of [chatRoomId]'s own addresses (D7). */
    suspend fun usageOf(chatRoomId: String, chain: Chain, script: ByteVector): UnusedAddressCheck.Usage

    /** Signs and sends the active profile's destination in [chatRoomId] on [chain]. */
    suspend fun set(chatRoomId: String, chain: Chain, lightningAddress: LightningAddress, sendable: Sendable?, onchainAddress: String?)

    /** Signs and sends that the active profile no longer wants [chatRoomId] to pay it on [chain] (D4). */
    suspend fun remove(chatRoomId: String, chain: Chain)

    companion object {
        /** Nothing to read and nothing to send: previews, and screens built before the repository. */
        val NO_OP = object : PayoutDestinationRepository {
            override suspend fun standing(chatRoomId: String, chain: Chain): Standing? = null
            override suspend fun destinationsIn(chatRoomId: String, chain: Chain): Map<HexKey, GroupPayoutDestination> = emptyMap()
            override suspend fun resolve(address: LightningAddress): LightningAddressResolver.Resolution = LightningAddressResolver.Resolution.Unreachable
            override suspend fun usageOf(chatRoomId: String, chain: Chain, script: ByteVector): UnusedAddressCheck.Usage = UnusedAddressCheck.Usage.Unknown
            override suspend fun set(chatRoomId: String, chain: Chain, lightningAddress: LightningAddress, sendable: Sendable?, onchainAddress: String?) =
                throw UnsupportedOperationException("no repository")
            override suspend fun remove(chatRoomId: String, chain: Chain) = throw UnsupportedOperationException("no repository")
        }
    }
}
