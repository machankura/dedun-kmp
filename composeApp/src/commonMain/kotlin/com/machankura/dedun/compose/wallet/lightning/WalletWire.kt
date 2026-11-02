package com.machankura.dedun.compose.wallet.lightning

import kotlinx.serialization.Serializable

/**
 * What the operator tells the group about its Lightning wallet, and how a member asks to be paid
 * (`docs/group-lightning-wallet.md`, Phases 10 and 11), as JSON inside Marmot inner events.
 */
object WalletWire {
    /** One channel as the operator's node holds it. */
    @Serializable
    data class ChannelView(
        val channelId: String,
        val state: String,
        val capacitySat: Long,
        val oursMsat: Long,
        val theirsMsat: Long,
        val fundingTxId: String,
        val fundingIndex: Long,
        val commitIndex: Long,
        /**
         * How many funding outputs carry commitments: 1, or 2 while a splice has not locked, when the LSP
         * refuses another (`docs/group-lightning-liquidity.md`, Phase 6).
         */
        val liveFundingOutputs: Int = 1,
    )

    /** One payment the node made or received. [id] is lightning-kmp's own. */
    @Serializable
    data class PaymentView(
        val id: String,
        val incoming: Boolean,
        val amountMsat: Long,
        val feesMsat: Long,
        val paymentHash: String? = null,
        val description: String? = null,
        val completedAt: Long? = null,
        val succeeded: Boolean,
        /** [PAYMENT], or [LIQUIDITY] for inbound bought: then [amountMsat] is its fee, as in Phoenix, and [liquiditySat] what it bought. */
        val kind: String = PAYMENT,
        val liquiditySat: Long? = null,
        /**
         * For an incoming payment, the invoice request it paid, as the operator's node joined them by
         * hash: the operator's word, which a device holding the request and its reply checks for
         * itself. `docs/member-deposits.md`, D6.
         */
        val requestId: String? = null,
        /** Who asked for that invoice, as the operator's node recorded it: so a device that missed the request can attribute it, saying whose word it is. */
        val requester: String? = null,
        /**
         * For a splice-in, each swap-in coin it spent: whose address it was on, and what it held
         * (`docs/group-lightning-swap-in.md`, Phase 5). An older device ignores it, and reads the payment as
         * it always has.
         */
        val swapIns: List<SwapInCredit> = emptyList(),
    )

    /** One swap-in coin a splice-in spent: its outpoint, the depositor and index of its address, its amount and script. */
    @Serializable
    data class SwapInCredit(val outPoint: String, val member: String, val index: Long, val amountSat: Long, val script: String)

    const val PAYMENT = "payment"
    const val LIQUIDITY = "liquidity"

    /** One of the LSP's liquidity-ads rates, as lightning-kmp's `LiquidityAds.FundingRate` has it. */
    @Serializable
    data class FundingRate(val minAmountSat: Long, val maxAmountSat: Long, val fundingWeight: Int, val feeProportional: Int, val feeBaseSat: Long, val channelCreationFeeSat: Long)

    /** What the LSP sells, as its `init` said: [rates], paid by any of [paymentTypes] (BOLT names). */
    @Serializable
    data class FundingRates(val rates: List<FundingRate>, val paymentTypes: List<String>)

    /**
     * What came of one `buy-liquidity` authorisation, [id], on the operator's node: [WAITING] for an
     * earlier splice of the channel to lock, [PENDING] with the splice's [fundingTxId] once the LSP signed
     * it, [LOCKED] once the channel is on that output alone, or [FAILED] with [reason].
     */
    @Serializable
    data class LiquidityPurchase(
        val id: String,
        val channelId: String,
        val amountSat: Long,
        val state: String,
        val fundingTxId: String? = null,
        val feeSat: Long? = null,
        val reason: String? = null,
    )

    const val WAITING = "waiting"
    const val PENDING = "pending"
    const val LOCKED = "locked"
    const val FAILED = "failed"

    /**
     * Kind 30362: the operator's claim about the wallet, sent when something changes and every few
     * minutes while the node runs. [assignments] is the round-one pool's assignments, so an operator
     * elected after this one can sign under the verification nonces this one published.
     */
    @Serializable
    data class Snapshot(
        val wallet: String,
        val term: Long,
        val nodeId: String,
        val sentAt: Long,
        val channels: List<ChannelView>,
        val payments: List<PaymentView>,
        val offer: String? = null,
        val assignments: Map<String, Long> = emptyMap(),
        /** What closed channels left the group, for any member to sweep into the onchain wallet. */
        val heldOutputs: List<GroupSweep.Input> = emptyList(),
        /** What the LSP sells, for a member to price a purchase from, and the feerate the node would buy at, in sat/vbyte: the operator's claim. */
        val fundingRates: FundingRates? = null,
        val feeratePerByte: Long? = null,
        /** What came of each liquidity purchase the group authorised. */
        val purchases: List<LiquidityPurchase> = emptyList(),
        /** Whether the node could reach the LSP, settled; absent from an older operator. `docs/lsp-connection-status.md`, D8. */
        val lsp: LspLink? = null,
    ) {
        val spendableMsat: Long get() = channels.sumOf { it.oursMsat }
        val inboundMsat: Long get() = channels.sumOf { it.theirsMsat }
    }

    /**
     * The operator's word on its node's connection to the LSP: [connected] or not since [since], in epoch
     * seconds, and when not, the [cause] of the last drop by its name in [LspDrop.Cause]. Never the drop's
     * detail, which can carry a host or an address and stays on the operator's device.
     */
    @Serializable
    data class LspLink(val connected: Boolean, val since: Long, val cause: String? = null)

    /**
     * Kind 30364: a member asks the operator for an invoice. [depositor] is the id of the group's
     * depositor statement for the member who asks, which an invoice is issued against: no statement,
     * no invoice. `docs/member-deposits.md`, D5.
     */
    @Serializable
    data class InvoiceRequest(val wallet: String, val requestId: String, val amountMsat: Long? = null, val description: String, val depositor: String? = null)

    /**
     * Kind 30365: the operator's answer, addressed to the whole room by [requestId], naming whose
     * request it answers and the hash it is paid by, so a device needn't parse the invoice to join it.
     */
    @Serializable
    data class InvoiceReply(val wallet: String, val requestId: String, val bolt11: String, val requester: String? = null, val paymentHash: String? = null)
}
