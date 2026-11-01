package com.machankura.dedun.compose.nostr.lightning

import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.payment.Bolt11Invoice
import kotlin.time.Clock

/**
 * Something the group lets its Lightning wallet do: kind 30363, FROST-signed, never broadcast
 * (`docs/group-lightning-wallet.md`, Phase 12).
 *
 * The operator's node acts on an authorisation, and every co-signer reads it as a budget: an HTLC the
 * group's commitment offers must pay a hash some authorisation names, within its amount and fee cap.
 * So the operator cannot add a payment, only make one the group signed for.
 *
 * `pay` is a payment ([Pay]); `close` and `force-close` act on one channel ([ChannelAction]);
 * `buy-liquidity` buys inbound liquidity for one channel by splicing it ([BuyLiquidity],
 * `docs/group-lightning-liquidity.md`, Phase 4). An action this device does not know reads as null,
 * which every caller treats as no authorisation at all.
 */
object GroupLightningAuthorisationEvent {
    val KIND: Kind = GroupLightningEvents.AUTHORISATION

    private const val D_TAG = "d"
    private const val ACTION_TAG = "action"
    private const val INVOICE_TAG = "invoice"
    private const val AMOUNT_TAG = "amount"
    private const val MAX_FEE_TAG = "maxfee"
    private const val HASH_TAG = "hash"
    private const val EXPIRES_TAG = "expires"

    private const val CHANNEL_TAG = "channel"
    private const val ID_TAG = "id"

    const val PAY = "pay"
    /** A mutual close of one channel, to the onchain wallet the statement names. */
    const val CLOSE = "close"
    /** Publishing our own commitment of one channel: what the group does when the LSP has gone away. */
    const val FORCE_CLOSE = "force-close"
    /** Buying inbound liquidity for one channel from the LSP, by splicing it. */
    const val BUY_LIQUIDITY = "buy-liquidity"

    /**
     * A payment the group authorises, at most [amountMsat] plus [maxFeeMsat], until [expiresAt] (epoch
     * seconds). After that no new HTLC for it is signed; one already in flight still settles against it.
     */
    data class Pay(
        val chain: Chain,
        val wallet: Long,
        val invoice: String,
        val amountMsat: Long,
        val maxFeeMsat: Long,
        val paymentHash: String,
        val expiresAt: Long,
    ) {
        val budgetMsat: Long get() = amountMsat + maxFeeMsat
    }

    fun template(pay: Pay, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, "${GroupLightningWalletStatementEvent.identifier(pay.chain, pay.wallet)}:${pay.paymentHash}"),
                arrayOf(ACTION_TAG, PAY),
                arrayOf(INVOICE_TAG, pay.invoice),
                arrayOf(AMOUNT_TAG, pay.amountMsat.toString()),
                arrayOf(MAX_FEE_TAG, pay.maxFeeMsat.toString()),
                arrayOf(HASH_TAG, pay.paymentHash),
                arrayOf(EXPIRES_TAG, pay.expiresAt.toString()),
            ),
            content = "",
        )

    /**
     * The payment [tags] authorise, or null for anything this device cannot stand behind: an action it
     * does not know, an invoice it cannot read, or one that disagrees with what the tags say it is --
     * another network, another hash, another amount, or an expiry past the invoice's own.
     */
    fun read(tags: Array<Array<String>>): Pay? {
        fun value(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        if (value(ACTION_TAG) != PAY) return null
        val d = value(D_TAG) ?: return null
        val parts = d.split(':')
        if (parts.size != 3) return null
        val chain = GroupWalletStatementEvent.chainNamed(parts[0]) ?: return null
        val wallet = parts[1].toLongOrNull() ?: return null
        val bolt11 = value(INVOICE_TAG) ?: return null
        val invoice = runCatching { Bolt11Invoice.read(bolt11).get() }.getOrNull() ?: return null
        val amount = value(AMOUNT_TAG)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val maxFee = value(MAX_FEE_TAG)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val hash = value(HASH_TAG) ?: return null
        val expires = value(EXPIRES_TAG)?.toLongOrNull() ?: return null
        if (invoice.chain != chain) return null
        if (parts[2] != hash || invoice.paymentHash.toHex() != hash) return null
        if (invoice.amount != null && invoice.amount!!.msat != amount) return null
        if (expires > invoice.timestampSeconds + (invoice.expirySeconds ?: Bolt11Invoice.DEFAULT_EXPIRY_SECONDS.toLong())) return null
        return Pay(chain, wallet, bolt11, amount, maxFee, hash, expires)
    }

    /** A [CLOSE] or [FORCE_CLOSE] of [channelId], open until [expiresAt] (epoch seconds). */
    data class ChannelAction(val action: String, val chain: Chain, val wallet: Long, val channelId: String, val expiresAt: Long) {
        init {
            require(action == CLOSE || action == FORCE_CLOSE) { "not a channel action: $action" }
        }
    }

    fun template(channelAction: ChannelAction, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, "${GroupLightningWalletStatementEvent.identifier(channelAction.chain, channelAction.wallet)}:${channelAction.action}:${channelAction.channelId}"),
                arrayOf(ACTION_TAG, channelAction.action),
                arrayOf(CHANNEL_TAG, channelAction.channelId),
                arrayOf(EXPIRES_TAG, channelAction.expiresAt.toString()),
            ),
            content = "",
        )

    /** The channel action [tags] authorise, or null for a payment, an unknown action, or tags that disagree. */
    fun readChannelAction(tags: Array<Array<String>>): ChannelAction? {
        fun value(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        val action = value(ACTION_TAG)?.takeIf { it == CLOSE || it == FORCE_CLOSE } ?: return null
        val parts = value(D_TAG)?.split(':') ?: return null
        if (parts.size != 4 || parts[2] != action) return null
        val chain = GroupWalletStatementEvent.chainNamed(parts[0]) ?: return null
        val wallet = parts[1].toLongOrNull() ?: return null
        val channelId = value(CHANNEL_TAG)?.takeIf { it.length == 64 && it == parts[3] } ?: return null
        val expires = value(EXPIRES_TAG)?.toLongOrNull() ?: return null
        return ChannelAction(action, chain, wallet, channelId, expires)
    }

    /**
     * Buying [amountSat] of inbound liquidity for [channelId] from the LSP, by splicing the channel, for
     * at most [maxFeeSat] in fees -- our share of the splice's mining fee and the purchase's own, mining
     * and service, together -- until [expiresAt] (epoch seconds). The fee comes out of the group's
     * balance in the channel. [id] tells two purchases for one channel apart.
     */
    data class BuyLiquidity(
        val chain: Chain,
        val wallet: Long,
        val channelId: String,
        val amountSat: Long,
        val maxFeeSat: Long,
        val expiresAt: Long,
        val id: String,
    )

    fun template(buy: BuyLiquidity, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, "${GroupLightningWalletStatementEvent.identifier(buy.chain, buy.wallet)}:$BUY_LIQUIDITY:${buy.channelId}:${buy.id}"),
                arrayOf(ACTION_TAG, BUY_LIQUIDITY),
                arrayOf(CHANNEL_TAG, buy.channelId),
                arrayOf(AMOUNT_TAG, buy.amountSat.toString()),
                arrayOf(MAX_FEE_TAG, buy.maxFeeSat.toString()),
                arrayOf(EXPIRES_TAG, buy.expiresAt.toString()),
                arrayOf(ID_TAG, buy.id),
            ),
            content = "",
        )

    /** The liquidity purchase [tags] authorise, or null for any other action, or tags that disagree. */
    fun readBuyLiquidity(tags: Array<Array<String>>): BuyLiquidity? {
        fun value(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        if (value(ACTION_TAG) != BUY_LIQUIDITY) return null
        val parts = value(D_TAG)?.split(':') ?: return null
        if (parts.size != 5 || parts[2] != BUY_LIQUIDITY) return null
        val chain = GroupWalletStatementEvent.chainNamed(parts[0]) ?: return null
        val wallet = parts[1].toLongOrNull() ?: return null
        val channelId = value(CHANNEL_TAG)?.takeIf { it.length == 64 && it == parts[3] } ?: return null
        val id = value(ID_TAG)?.takeIf { it.isNotEmpty() && it == parts[4] } ?: return null
        val amount = value(AMOUNT_TAG)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val maxFee = value(MAX_FEE_TAG)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val expires = value(EXPIRES_TAG)?.toLongOrNull() ?: return null
        return BuyLiquidity(chain, wallet, channelId, amount, maxFee, expires, id)
    }
}
