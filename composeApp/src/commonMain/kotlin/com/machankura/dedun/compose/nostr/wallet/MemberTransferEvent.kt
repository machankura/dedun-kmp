package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * The group recording that a member transferred part of their balance to another: `docs/member-transfers.md`, T2.
 *
 * ```
 * kind:    30343
 * pubkey:  <the room>
 * content: ""
 * tags:
 *   ["d",          "transfer:<request id>"]
 *   ["chain",      …]
 *   ["rail",       …]
 *   ["from",       <the giver>]
 *   ["p",          <the receiver>]
 *   ["amount_sat", <the request's amount>]
 *   ["request",    <the giver's signed 30342, whole>]
 *   ["coin",       "<txid>:<vout>", "<sat>"]     onchain only: one or more, summing to amount_sat (T6)
 * ```
 *
 * Nothing moves: no coin, VTXO or channel balance. Every rail's balance counts a transfer from the moment the group signs
 * it, and the deposit ledger never does (T7). It is final; a receiver who wants to give it back makes one of their own.
 *
 * `from`, `p`, `chain`, `rail` and `amount_sat` repeat the request, so a reader can index the event without opening what it
 * embeds, and [read] requires them to agree with it, as 30339 repeats its embedded destination's member.
 *
 * ### Why the group signs as well as the giver
 *
 * Two transfers the giver signed at once could each fit their balance, and devices that saw them in another order would
 * disagree about three balances. The group's signature puts them in an order, and each signer refuses the second, as it
 * refuses a second payout.
 *
 * ### Why 30343
 *
 * After the giver's request, 30342.
 */
object MemberTransferEvent {
    val KIND: Kind = 30343

    private const val D_TAG = "d"
    private const val D_PREFIX = "transfer:"
    private const val CHAIN_TAG = "chain"
    private const val RAIL_TAG = "rail"
    private const val FROM_TAG = "from"
    private const val TO_TAG = "p"
    private const val AMOUNT_TAG = "amount_sat"
    private const val REQUEST_TAG = "request"
    private const val COIN_TAG = "coin"

    /** [sat] of the coin at [key] moving from the giver to the receiver. */
    data class CoinShare(val key: DepositKey.Onchain, val sat: Long)

    data class Transfer(
        val chain: Chain,
        val rail: Rail,
        val from: HexKey,
        val to: HexKey,
        val amountSat: Long,
        val request: TransferRequestEvent.Request,
        val coins: List<CoinShare>,
        val event: Event,
    ) {
        val id: String get() = event.id
        val requestId: String get() = request.id
        val createdAt: Long get() = event.createdAt
        val note: String? get() = request.note
    }

    /** The group's transfer of [request], moving [coins] on the onchain rail and nothing on the others. */
    fun template(
        request: TransferRequestEvent.Request,
        coins: List<CoinShare> = emptyList(),
        createdAt: Long = Clock.System.now().epochSeconds,
    ): EventTemplate<*> {
        if (request.rail == Rail.ONCHAIN) {
            require(coins.isNotEmpty()) { "an onchain transfer names its coins" }
            require(coins.map { it.key }.toSet().size == coins.size) { "a coin twice" }
            require(coins.all { it.sat >= TransferRequestEvent.MIN_TRANSFER_SAT }) { "a share below dust" }
            require(coins.sumOf { it.sat } == request.amountSat) { "coins that are not the amount" }
        } else {
            require(coins.isEmpty()) { "only an onchain transfer names coins" }
        }
        return EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, D_PREFIX + request.id.lowercase()),
                arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(request.chain)),
                arrayOf(RAIL_TAG, request.rail.wire),
                arrayOf(FROM_TAG, request.giver.lowercase()),
                arrayOf(TO_TAG, request.receiver.lowercase()),
                arrayOf(AMOUNT_TAG, request.amountSat.toString()),
                arrayOf(REQUEST_TAG, request.event.toJson()),
                *coins.sortedBy { it.key.text }.map { arrayOf(COIN_TAG, it.key.text, it.sat.toString()) }.toTypedArray(),
            ),
            content = "",
        )
    }

    /**
     * What [event] records, by its shape and its embedded request -- which has to be signed by the giver it names, and agree
     * with every tag beside it -- or null. Whose room signed it, and whether the request was about that room, is [verify]'s.
     */
    fun read(event: Event): Transfer? = runCatching {
        if (event.kind != KIND || event.content.isNotEmpty()) return null
        fun all(name: String) = event.tags.filter { it.isNotEmpty() && it[0] == name }
        fun single(name: String): String? = all(name).singleOrNull()?.takeIf { it.size >= 2 }?.get(1)

        val embedded = single(REQUEST_TAG)?.let { Event.fromJsonOrNull(it) } ?: return null
        val request = TransferRequestEvent.verified(embedded) as? TransferRequestEvent.Request ?: return null
        if (single(D_TAG) != D_PREFIX + request.id.lowercase()) return null
        if (single(CHAIN_TAG) != GroupWalletStatementEvent.chainName(request.chain)) return null
        if (single(RAIL_TAG) != request.rail.wire) return null
        if (single(FROM_TAG) != request.giver) return null
        if (single(TO_TAG) != request.receiver) return null
        if (single(AMOUNT_TAG) != request.amountSat.toString()) return null

        val coins = all(COIN_TAG).map { tag ->
            if (tag.size < 3) return null
            val key = DepositKey.parse(tag[1]) as? DepositKey.Onchain ?: return null
            val sat = tag[2].toLongOrNull()?.takeIf { it >= TransferRequestEvent.MIN_TRANSFER_SAT && it.toString() == tag[2] } ?: return null
            CoinShare(key, sat)
        }
        if (request.rail == Rail.ONCHAIN) {
            if (coins.isEmpty() || coins.map { it.key }.toSet().size != coins.size) return null
            if (coins.sumOf { it.sat } != request.amountSat) return null
        } else if (coins.isNotEmpty()) {
            return null
        }
        Transfer(request.chain, request.rail, request.giver, request.receiver, request.amountSat, request, coins, event)
    }.getOrNull()

    /** [event] as [chatRoomId]'s: [read], signed by the room, about a request made in it. */
    fun verify(event: Event, chatRoomId: String): Transfer? =
        read(event)
            ?.takeIf { it.request.chatRoomId.equals(chatRoomId, ignoreCase = true) }
            ?.takeIf { GroupKeyStateEvent.isSignedByRoom(event, chatRoomId) }

    /** The order transfers apply in: the group's own timestamp, then the id. Every device agrees on it. */
    val ORDER: Comparator<Transfer> = compareBy<Transfer>({ it.createdAt }, { it.id })

    /**
     * The transfers among [events] that count for [chatRoomId] on [chain], in [ORDER]: the first the group signed for each
     * request, and no other. A second embedding of one request is a replay, or two sessions racing, and moves nothing.
     */
    fun firstPerRequest(events: List<Event>, chatRoomId: String, chain: Chain): List<Transfer> =
        events.mapNotNull { verify(it, chatRoomId) }
            .filter { it.chain == chain }
            .sortedWith(ORDER)
            .distinctBy { it.requestId }
}
