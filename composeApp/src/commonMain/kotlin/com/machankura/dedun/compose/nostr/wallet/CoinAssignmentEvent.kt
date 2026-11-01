package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * The group saying whose some of its coins are: `docs/payouts-only.md`, D3.
 *
 * ```
 * kind:    30341
 * pubkey:  <the room>
 * content: ""
 * tags:
 *   ["chain", "mainnet" | …]
 *   ["coin",  "<txid>:<vout>", "<sat>"]                  one per coin, its whole amount
 *   ["share", "<txid>:<vout>", <member>, "<sat>"]        one or more per coin, summing to it
 * ```
 *
 * For the coins no branch explains: what landed on the legacy receive branch, change from the
 * spends before payouts were the only spend, and what a closed channel paid back. The wallet
 * signs only payouts, and a coin nobody owns can't be paid to anybody, so until the group says
 * whose it is it stays where it is. Every sat is a member's -- there is no `group` share, since a
 * share nobody owns could never leave -- and no share is below the dust limit.
 *
 * The newest signed assignment of a coin wins, so a mistake is corrected by signing again.
 *
 * ### Why a kind of its own, not another tag on 30340
 *
 * An attribution says who *deposited*, and the tallies count it. A change coin attributed as a
 * deposit would count its money twice: once when it came in, and again now. An assignment says
 * who *owns* a coin, moves no tally, and is read only by the owner rule and the balances.
 *
 * ### Why 30341
 *
 * After the deposit attribution, 30340. Nothing used 30341 to 30348.
 */
object CoinAssignmentEvent {
    val KIND: Kind = 30341

    private const val CHAIN_TAG = "chain"
    private const val COIN_TAG = "coin"
    private const val SHARE_TAG = "share"

    /** The smallest share: what a coin's least owner is paid has to be an output the network relays. */
    const val MIN_SHARE_SAT: Long = 546

    /** One coin, [amount] sats, owned in [shares] that sum to it. */
    data class Coin(val key: DepositKey.Onchain, val amount: Long, val shares: Map<HexKey, Long>)

    data class Assignments(val chain: Chain, val coins: List<Coin>, val event: Event) {
        val createdAt: Long get() = event.createdAt
    }

    fun template(chain: Chain, coins: List<Coin>, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> {
        require(coins.isNotEmpty()) { "nothing to assign" }
        require(coins.map { it.key }.toSet().size == coins.size) { "a coin twice" }
        coins.forEach { coin ->
            require(coin.shares.isNotEmpty()) { "a coin with no owner" }
            require(coin.shares.values.all { it >= MIN_SHARE_SAT }) { "a share below dust" }
            require(coin.shares.values.sum() == coin.amount) { "shares that are not the coin" }
        }
        return EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(chain)),
                *coins.flatMap { coin ->
                    listOf(arrayOf(COIN_TAG, coin.key.text, coin.amount.toString())) +
                        coin.shares.entries.sortedBy { it.key }.map { (member, sat) -> arrayOf(SHARE_TAG, coin.key.text, member.lowercase(), sat.toString()) }
                }.toTypedArray(),
            ),
            content = "",
        )
    }

    /**
     * What [event] assigns, by its shape: a network, and at least one coin, none twice, each with
     * shares of at least [MIN_SHARE_SAT] by distinct members that add up to it, and no share for a
     * coin it doesn't list.
     */
    fun read(event: Event): Assignments? = runCatching {
        if (event.kind != KIND) return null
        val chain = event.tags.filter { it.isNotEmpty() && it[0] == CHAIN_TAG }.singleOrNull()?.getOrNull(1)
            ?.let(GroupWalletStatementEvent::chainNamed) ?: return null
        val amounts = event.tags.filter { it.isNotEmpty() && it[0] == COIN_TAG }.map { tag ->
            if (tag.size < 3) return null
            val key = DepositKey.parse(tag[1]) as? DepositKey.Onchain ?: return null
            key to (tag[2].toLongOrNull()?.takeIf { it > 0 } ?: return null)
        }
        if (amounts.isEmpty() || amounts.map { it.first }.toSet().size != amounts.size) return null
        val shares = event.tags.filter { it.isNotEmpty() && it[0] == SHARE_TAG }.map { tag ->
            if (tag.size < 4) return null
            val key = DepositKey.parse(tag[1]) as? DepositKey.Onchain ?: return null
            val member = tag[2].lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
            val sat = tag[3].toLongOrNull()?.takeIf { it >= MIN_SHARE_SAT } ?: return null
            Triple(key, member, sat)
        }
        val coins = amounts.map { (key, amount) ->
            val mine = shares.filter { it.first == key }
            if (mine.isEmpty() || mine.map { it.second }.toSet().size != mine.size) return null
            if (mine.sumOf { it.third } != amount) return null
            Coin(key, amount, mine.associate { it.second to it.third })
        }
        if (shares.any { share -> amounts.none { it.first == share.first } }) return null
        Assignments(chain, coins, event)
    }.getOrNull()

    /** [event] as [chatRoomId]'s: [read], and signed by the room. */
    fun verify(event: Event, chatRoomId: String): Assignments? =
        read(event)?.takeIf { GroupKeyStateEvent.isSignedByRoom(event, chatRoomId) }

    /**
     * The newest signed assignment of each coin among [events] on [chain], by the group's own
     * timestamp with the id breaking a tie: a later assignment corrects an earlier one.
     */
    fun newestPerCoin(events: List<Event>, chatRoomId: String, chain: Chain): Map<DepositKey.Onchain, Coin> =
        events.mapNotNull { verify(it, chatRoomId) }
            .filter { it.chain == chain }
            .sortedWith(compareBy<Assignments>({ it.createdAt }, { it.event.id }))
            .flatMap { signed -> signed.coins.map { it.key to it } }
            .toMap()
}
