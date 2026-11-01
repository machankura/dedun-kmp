package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.PayoutAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.MilliSatoshi
import kotlin.time.Clock

/**
 * What a member's 30338 says about one group on one chain: where the group pays them, or
 * that it should not. `docs/group-payout-destination.md`.
 */
sealed interface PayoutStatement {
    val member: HexKey
    val chatRoomId: String
    val chain: Chain
    /** The signed event, whole: what a later group-signed payout embeds. */
    val event: Event
    val createdAt: Long get() = event.createdAt
}

/** Where [member] wants [chatRoomId] to pay them on [chain]. */
data class GroupPayoutDestination(
    override val member: HexKey,
    override val chatRoomId: String,
    override val chain: Chain,
    val lightningAddress: LightningAddress,
    /** What the address said it accepts when it was set; for showing, never for deciding. */
    val sendable: Sendable?,
    /** An address that was unused when it was set, or null. */
    val onchainAddress: String?,
    override val event: Event,
) : PayoutStatement

/** [member] has withdrawn where [chatRoomId] pays them on [chain]: pay them nowhere. */
data class PayoutDestinationRemoved(
    override val member: HexKey,
    override val chatRoomId: String,
    override val chain: Chain,
    override val event: Event,
) : PayoutStatement

/** An LNURL-pay intent's `minSendable` and `maxSendable`. */
data class Sendable(val min: MilliSatoshi, val max: MilliSatoshi) {
    init {
        require(min.msat > 0 && min <= max) { "not a range" }
    }
}

/**
 * A member's payout destination in one group: a Lightning address, and optionally an
 * onchain address that was unused when it was set.
 *
 * ```
 * kind:    30338
 * pubkey:  <the member>
 * content: ""
 * tags:
 *   ["d",         "<room id>:<chain>"]
 *   ["h",         <room id>]
 *   ["chain",     "mainnet" | "signet" | …]
 *   ["lightning", "alice@example.com"]
 *   ["sendable",  <min msat>, <max msat>]   optional
 *   ["onchain",   "bc1p…"]                   optional
 *   ["removed"]                              instead of the three above
 * ```
 *
 * ### Signed by the member, not the group
 *
 * Where Alice wants to be paid is Alice's to say, and a quorum's signature adds nothing to
 * it but the need for one. The newest statement by a current member is the one that counts.
 *
 * ### Signed, and carried whole
 *
 * An inner event is an unsigned rumor whose authority is the MLS frame. This one's
 * signature has to outlive the room -- a group-signed payout may embed it, and a device that
 * received it again from its author (Phase 6) reads the same event -- so the room carries an
 * inner event of this kind whose content is the signed statement, whole: [carried], as
 * `MemberWalletRequestEvent` does.
 *
 * ### Never on a relay
 *
 * An onchain address beside an npub and a group is exactly the link a fresh address is
 * there to avoid.
 *
 * ### Why 30338
 *
 * After the member-wallet kinds, which end at 30337; nothing in the code or the docs uses
 * 30338 to 30348.
 */
object GroupPayoutDestinationEvent {
    val KIND: Kind = 30338

    private const val D_TAG = "d"
    private const val ROOM_TAG = "h"
    private const val CHAIN_TAG = "chain"
    private const val LIGHTNING_TAG = "lightning"
    private const val SENDABLE_TAG = "sendable"
    private const val ONCHAIN_TAG = "onchain"
    private const val REMOVED_TAG = "removed"

    /**
     * How far ahead of a reader's clock a statement may be dated and still count: past it,
     * one statement could sit on top for good and stop its author ever replacing it (D3).
     */
    const val MAX_FUTURE_SECONDS: Long = 600

    /** The `d` of [chatRoomId]'s statements on [chain]: one per member, per group, per chain (D2). */
    fun identifier(chatRoomId: String, chain: Chain): String =
        "${chatRoomId.lowercase()}:${GroupWalletStatementEvent.chainName(chain)}"

    /** Where [chatRoomId] pays the member [memberPrivateKey] holds, on [chain], signed by them. */
    fun build(
        memberPrivateKey: PrivateKey,
        chatRoomId: String,
        chain: Chain,
        lightningAddress: LightningAddress,
        sendable: Sendable? = null,
        onchainAddress: String? = null,
        createdAt: Long = Clock.System.now().epochSeconds,
    ): Event {
        val onchain = onchainAddress?.let {
            requireNotNull(PayoutAddress.parseOrNull(chain, it)) { "not an address on ${GroupWalletStatementEvent.chainName(chain)}" }.address
        }
        return signed(
            memberPrivateKey,
            createdAt,
            listOfNotNull(
                *header(chatRoomId, chain),
                arrayOf(LIGHTNING_TAG, lightningAddress.toString()),
                sendable?.let { arrayOf(SENDABLE_TAG, it.min.msat.toString(), it.max.msat.toString()) },
                onchain?.let { arrayOf(ONCHAIN_TAG, it) },
            ).toTypedArray(),
        )
    }

    /** That the member [memberPrivateKey] holds no longer wants [chatRoomId] to pay them on [chain] (D4). */
    fun buildRemoved(
        memberPrivateKey: PrivateKey,
        chatRoomId: String,
        chain: Chain,
        createdAt: Long = Clock.System.now().epochSeconds,
    ): Event = signed(memberPrivateKey, createdAt, arrayOf(*header(chatRoomId, chain), arrayOf(REMOVED_TAG)))

    private fun header(chatRoomId: String, chain: Chain): Array<Array<String>> = arrayOf(
        arrayOf(D_TAG, identifier(chatRoomId, chain)),
        arrayOf(ROOM_TAG, chatRoomId.lowercase()),
        arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(chain)),
    )

    private fun signed(memberPrivateKey: PrivateKey, createdAt: Long, tags: Array<Array<String>>): Event {
        val member = memberPrivateKey.xOnlyPublicKey().value.toHex()
        val id = EventHasher.hashId(member, createdAt, KIND, tags, "")
        val sig = Crypto.signSchnorr(ByteVector32(id), memberPrivateKey, null)
        return Event(id = id, pubKey = member, createdAt = createdAt, kind = KIND, tags = tags, content = "", sig = sig.toHex())
    }

    /**
     * What [event] says, by its shape alone, or null when a tag is missing, doubled, the wrong
     * shape, or disagrees with another. Both addresses are read again for the chain and must
     * be written as they would be written now, so two readers never disagree on one. The
     * signature is [verified]'s.
     */
    fun read(event: Event): PayoutStatement? = runCatching {
        if (event.kind != KIND) return null
        fun all(name: String) = event.tags.filter { it.isNotEmpty() && it[0] == name }
        fun single(name: String): String? = all(name).singleOrNull()?.takeIf { it.size >= 2 }?.get(1)
        fun optional(name: String): List<Array<String>>? = all(name).takeIf { it.size <= 1 }

        val chatRoomId = single(ROOM_TAG)?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val chain = single(CHAIN_TAG)?.let(GroupWalletStatementEvent::chainNamed) ?: return null
        if (single(D_TAG) != identifier(chatRoomId, chain)) return null
        val member = event.pubKey.lowercase()

        val lightning = optional(LIGHTNING_TAG) ?: return null
        val sendable = optional(SENDABLE_TAG) ?: return null
        val onchain = optional(ONCHAIN_TAG) ?: return null
        val removed = optional(REMOVED_TAG) ?: return null

        if (removed.isNotEmpty()) {
            if (lightning.isNotEmpty() || sendable.isNotEmpty() || onchain.isNotEmpty()) return null
            return PayoutDestinationRemoved(member, chatRoomId, chain, event)
        }

        val address = lightning.singleOrNull()?.getOrNull(1)?.let { written ->
            LightningAddress.parseOrNull(written)?.takeIf { it.toString() == written }
        } ?: return null
        val range = sendable.singleOrNull()?.let { tag ->
            val min = tag.getOrNull(1)?.toLongOrNull() ?: return null
            val max = tag.getOrNull(2)?.toLongOrNull() ?: return null
            if (min <= 0 || min > max) return null
            Sendable(MilliSatoshi(min), MilliSatoshi(max))
        }
        val onchainAddress = onchain.singleOrNull()?.let { tag ->
            val written = tag.getOrNull(1) ?: return null
            PayoutAddress.parseOrNull(chain, written)?.address?.takeIf { it == written } ?: return null
        }

        GroupPayoutDestination(member, chatRoomId, chain, address, range, onchainAddress, event)
    }.getOrNull()

    /** [event] read, and signed by the member it names -- a plain BIP-340 check over its id. */
    fun verified(event: Event): PayoutStatement? {
        val statement = read(event) ?: return null
        return statement.takeIf { GroupKeyStateEvent.isSignedByRoom(event, it.member) }
    }

    /**
     * The signed statement an inner event of this kind carries -- or null when its content is
     * not one, or was put in the room by somebody other than the member who signed it.
     */
    fun carried(inner: Event): Event? {
        if (inner.kind != KIND) return null
        val statement = Event.fromJsonOrNull(inner.content)?.takeIf { it.kind == KIND } ?: return null
        return statement.takeIf { it.pubKey.equals(inner.pubKey, ignoreCase = true) }
    }

    /**
     * The newest statement of each member among [events] about [chatRoomId] on [chain], by the
     * member's own timestamp with the id breaking a tie, so two devices that read the same
     * events in another order agree. A statement dated more than [MAX_FUTURE_SECONDS] past
     * [now] is left out. A removal is kept: it is what stops an older destination counting.
     * Whether each member is still in the room is the caller's to say.
     */
    fun newestPerMember(
        events: List<Event>,
        chatRoomId: String,
        chain: Chain,
        now: Long = Clock.System.now().epochSeconds,
    ): Map<HexKey, PayoutStatement> =
        events.asSequence()
            .mapNotNull { verified(it) }
            .filter { it.chatRoomId.equals(chatRoomId, ignoreCase = true) && it.chain == chain }
            .filter { it.createdAt <= now + MAX_FUTURE_SECONDS }
            .groupBy { it.member }
            .mapValues { (_, statements) -> statements.maxWith(ORDER) }

    /** The order statements are compared in: [newestPerMember]'s. */
    val ORDER: Comparator<PayoutStatement> = compareBy<PayoutStatement>({ it.createdAt }, { it.event.id })
}
