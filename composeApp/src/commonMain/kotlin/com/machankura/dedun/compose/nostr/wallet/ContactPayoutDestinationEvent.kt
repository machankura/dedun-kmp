package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent.isHex64
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent.single
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.PayoutAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * Where the group pays a contact on one network: `docs/non-member-deposits.md`, D9.
 *
 * ```
 * kind:    30429
 * pubkey:  <the room>
 * content: ""
 * tags:
 *   ["d",         "<contact id>:<chain>"]
 *   ["contact",   <contact id>]
 *   ["chain",     "mainnet" | …]
 *   ["lightning", "mum@example.com"]       optional
 *   ["onchain",   "bc1q…"]                 optional; at least one of the two
 * ```
 *
 * or `["removed"]` in place of both: pay them nowhere on this network.
 *
 * ### The group's signature where a member's own would be
 *
 * A member's destination is their own signed 30338. A contact can't sign, so the quorum does, and
 * every signer makes the checks the member's device would have made on setting one
 * (group-payout-destination.md, D5 and D7). A payout names this event's id as its destination, as
 * it names a member's 30338, and every rail's "the newest destination" check reads it unchanged.
 *
 * ### Why the Lightning address is optional
 *
 * A member's destination requires one, because every member has a device that can make one. A
 * contact may have nothing but an onchain address. A Lightning payout to a contact without one is
 * refused, as an onchain payout to a member without an onchain address is.
 *
 * Newest wins per contact per network, as the member's 30338 does.
 */
object ContactPayoutDestinationEvent {
    val KIND: Kind = 30429

    private const val D_TAG = "d"
    private const val CONTACT_TAG = "contact"
    private const val CHAIN_TAG = "chain"
    private const val LIGHTNING_TAG = "lightning"
    private const val ONCHAIN_TAG = "onchain"
    private const val REMOVED_TAG = "removed"

    fun identifier(contactId: HexKey, chain: Chain): String = "${contactId.lowercase()}:${GroupWalletStatementEvent.chainName(chain)}"

    /** What a 30429 says: an address or two, or [removed]. */
    data class Destination(
        val contactId: HexKey,
        override val chain: Chain,
        override val lightningAddress: LightningAddress?,
        /** As [PayoutAddress] writes it down: trimmed, bech32 in lowercase. */
        override val onchainAddress: String?,
        val removed: Boolean,
        override val event: Event,
    ) : PayoutDestination {
        val createdAt: Long get() = event.createdAt
        override val payee: Party get() = Party.Contact(contactId)
    }

    fun template(
        contactId: HexKey,
        chain: Chain,
        lightningAddress: LightningAddress?,
        onchainAddress: String?,
        createdAt: Long = Clock.System.now().epochSeconds,
    ): EventTemplate<*> {
        require(lightningAddress != null || onchainAddress != null) { "a destination needs an address" }
        val onchain = onchainAddress?.let { requireNotNull(PayoutAddress.parseOrNull(chain, it)) { "not an address on this network" }.address }
        return EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = listOfNotNull(
                arrayOf(D_TAG, identifier(contactId, chain)),
                arrayOf(CONTACT_TAG, contactId.lowercase()),
                arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(chain)),
                lightningAddress?.let { arrayOf(LIGHTNING_TAG, it.toString()) },
                onchain?.let { arrayOf(ONCHAIN_TAG, it) },
            ).toTypedArray(),
            content = "",
        )
    }

    fun removedTemplate(contactId: HexKey, chain: Chain, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, identifier(contactId, chain)),
                arrayOf(CONTACT_TAG, contactId.lowercase()),
                arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(chain)),
                arrayOf(REMOVED_TAG),
            ),
            content = "",
        )

    /**
     * What [event] says, by its shape: `d` agreeing with `contact` and `chain`; then either `removed`
     * and no address, or at least one address -- a Lightning address that parses, an onchain one on
     * this network written as [PayoutAddress] writes it.
     */
    fun read(event: Event): Destination? = runCatching {
        if (event.kind != KIND) return null
        val contactId = single(event, CONTACT_TAG)?.get(1)?.lowercase()?.takeIf(::isHex64) ?: return null
        val chain = single(event, CHAIN_TAG)?.get(1)?.let(GroupWalletStatementEvent::chainNamed) ?: return null
        if (single(event, D_TAG)?.get(1) != identifier(contactId, chain)) return null
        val removed = event.tags.any { it.isNotEmpty() && it[0] == REMOVED_TAG }
        val lightningTags = event.tags.filter { it.isNotEmpty() && it[0] == LIGHTNING_TAG }
        val onchainTags = event.tags.filter { it.isNotEmpty() && it[0] == ONCHAIN_TAG }
        if (lightningTags.size > 1 || onchainTags.size > 1) return null
        if (removed) {
            if (lightningTags.isNotEmpty() || onchainTags.isNotEmpty()) return null
            return Destination(contactId, chain, null, null, removed = true, event)
        }
        val lightning = lightningTags.singleOrNull()?.let { tag -> tag.getOrNull(1)?.let { LightningAddress.parseOrNull(it) } ?: return null }
        val onchain = onchainTags.singleOrNull()?.let { tag ->
            val written = tag.getOrNull(1) ?: return null
            PayoutAddress.parseOrNull(chain, written)?.address?.takeIf { it == written } ?: return null
        }
        if (lightning == null && onchain == null) return null
        Destination(contactId, chain, lightning, onchain, removed = false, event)
    }.getOrNull()

    fun verify(event: Event, chatRoomId: String): Destination? =
        read(event)?.takeIf { GroupKeyStateEvent.isSignedByRoom(event, chatRoomId) }

    /** Each contact's newest destination on [chain] among [events], a removal included. */
    fun newestPerContact(events: List<Event>, chatRoomId: String, chain: Chain): Map<HexKey, Destination> =
        events.asSequence()
            .mapNotNull { verify(it, chatRoomId) }
            .filter { it.chain == chain }
            .groupBy { it.contactId }
            .mapValues { (_, destinations) -> destinations.maxWith(compareBy<Destination>({ it.createdAt }, { it.event.id })) }
}
