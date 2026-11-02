package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * The group saying whose some deposits are: `docs/member-deposits.md`, D10.
 *
 * ```
 * kind:    30340
 * pubkey:  <the room>
 * content: ""
 * tags:
 *   ["chain",   "mainnet" | …]
 *   ["deposit", "<txid>:<vout>" | "ln:<payment hash>", <member> | "group", "<amount>"]   one or more
 * ```
 *
 * For the deposits nothing else explains: what landed on the legacy receive branch, a payment
 * to a change address, a payment to an offer, a Lightning payment a device could only take on
 * the operator's word. `group` says a deposit is no member's, a transfer from the group's own
 * other wallet say. The amount is sats onchain and msat over Lightning, and every signer checks
 * it against its own ledger, so an attribution can't name a deposit that is not there.
 *
 * The newest signed attribution of a deposit wins, so a mistake is corrected by signing again.
 *
 * ### Why 30340
 *
 * After the depositor statement, 30339.
 */
object DepositAttributionEvent {
    val KIND: Kind = 30340

    private const val CHAIN_TAG = "chain"
    private const val DEPOSIT_TAG = "deposit"

    /** No member's: the group's own. */
    const val GROUP = "group"
    private const val LIGHTNING_PREFIX = "ln:"
    private const val SWAP_IN_PREFIX = "swapin:"

    /** A deposit, by what identifies it: an onchain output, or a Lightning payment's hash. */
    sealed interface DepositKey {
        val text: String

        data class Onchain(val txid: String, val vout: Long) : DepositKey {
            override val text: String get() = "$txid:$vout"
        }

        data class Lightning(val paymentHash: String) : DepositKey {
            override val text: String get() = "$LIGHTNING_PREFIX$paymentHash"
        }

        /** A swap-in coin spliced into the Lightning wallet: a Lightning deposit, known by the coin it was. */
        data class SwapIn(val txid: String, val vout: Long) : DepositKey {
            override val text: String get() = "$SWAP_IN_PREFIX$txid:$vout"
        }

        companion object {
            private val HEX64 = Regex("^[0-9a-f]{64}$")

            fun parse(text: String): DepositKey? {
                if (text.startsWith(LIGHTNING_PREFIX)) {
                    return text.removePrefix(LIGHTNING_PREFIX).takeIf { HEX64.matches(it) }?.let(::Lightning)
                }
                if (text.startsWith(SWAP_IN_PREFIX)) {
                    return (parse(text.removePrefix(SWAP_IN_PREFIX)) as? Onchain)?.let { SwapIn(it.txid, it.vout) }
                }
                val parts = text.split(':')
                if (parts.size != 2 || !HEX64.matches(parts[0])) return null
                val vout = parts[1].toLongOrNull()?.takeIf { it in 0..UInt.MAX_VALUE.toLong() && parts[1] == it.toString() } ?: return null
                return Onchain(parts[0], vout)
            }
        }
    }

    /** One deposit's attribution: to [member], or to nobody when null -- the group's own. */
    data class Attribution(val key: DepositKey, val member: HexKey?, val amount: Long)

    data class Attributions(val chain: Chain, val attributions: List<Attribution>, val event: Event) {
        val createdAt: Long get() = event.createdAt
    }

    fun template(chain: Chain, attributions: List<Attribution>, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> {
        require(attributions.isNotEmpty()) { "nothing to attribute" }
        require(attributions.map { it.key }.toSet().size == attributions.size) { "a deposit twice" }
        require(attributions.all { it.amount > 0 }) { "an amount is positive" }
        return EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(chain)),
                *attributions.map { arrayOf(DEPOSIT_TAG, it.key.text, it.member?.lowercase() ?: GROUP, it.amount.toString()) }.toTypedArray(),
            ),
            content = "",
        )
    }

    /** What [event] attributes, by its shape: a network, and at least one deposit, none twice, each well formed. */
    fun read(event: Event): Attributions? = runCatching {
        if (event.kind != KIND) return null
        val chain = event.tags.filter { it.isNotEmpty() && it[0] == CHAIN_TAG }.singleOrNull()?.getOrNull(1)
            ?.let(GroupWalletStatementEvent::chainNamed) ?: return null
        val attributions = event.tags.filter { it.isNotEmpty() && it[0] == DEPOSIT_TAG }.map { tag ->
            if (tag.size < 4) return null
            val key = DepositKey.parse(tag[1]) ?: return null
            val member = when (val who = tag[2].lowercase()) {
                GROUP -> null
                else -> who.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
            }
            val amount = tag[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            Attribution(key, member, amount)
        }
        if (attributions.isEmpty() || attributions.map { it.key }.toSet().size != attributions.size) return null
        Attributions(chain, attributions, event)
    }.getOrNull()

    /** [event] as [chatRoomId]'s: [read], and signed by the room. */
    fun verify(event: Event, chatRoomId: String): Attributions? =
        read(event)?.takeIf { GroupKeyStateEvent.isSignedByRoom(event, chatRoomId) }

    /**
     * The newest signed attribution of each deposit among [events] on [chain], by the group's own
     * timestamp with the id breaking a tie: a later attribution corrects an earlier one.
     */
    fun newestPerDeposit(events: List<Event>, chatRoomId: String, chain: Chain): Map<DepositKey, Pair<Attribution, Attributions>> =
        events.mapNotNull { verify(it, chatRoomId) }
            .filter { it.chain == chain }
            .sortedWith(compareBy<Attributions>({ it.createdAt }, { it.event.id }))
            .flatMap { signed -> signed.attributions.map { it.key to (it to signed) } }
            .toMap()
}
