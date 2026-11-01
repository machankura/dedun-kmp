package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.LiquidityEvents
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.FundingContributions
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.wire.LiquidityAds
import fr.acinq.phoenix.data.Notification

/**
 * One address of the personal node's swap-in wallet, as the swap-in screen lists it
 * (docs/personal-swap-in-wallet.md, D3 and D4).
 */
data class SwapInAddress(
    val address: String,
    /** The derivation index, or null for the legacy address, which has none. */
    val index: Int?,
    val status: Status,
    /** What it holds now: zero for an address never paid, and for one paid and swapped in. */
    val balance: Satoshi,
) {
    enum class Status {
        /** The address being handed out: the lowest unused one. */
        CURRENT,

        /** Paid at least once, whether or not the coins have moved since. */
        USED,
    }
}

/** One coin on a swap-in address, and where it is on its way into a channel. */
data class SwapInCoin(
    val outPoint: OutPoint,
    val amount: Satoshi,
    /** The address it was paid to, and that address's index, or null for the legacy one. */
    val index: Int?,
    val confirmations: Int,
    val status: Status,
) {
    val txId: TxId get() = outPoint.txid

    sealed interface Status {
        /** In the mempool. */
        data object Unconfirmed : Status

        /** In a block, but short of the [needed] confirmations a swap waits for. */
        data class Confirming(val confirmations: Int, val needed: Int) : Status

        /** Deep enough, and the node may use it in the next splice or channel it asks for. */
        data object Ready : Status

        /** Ready, but with fewer than [SwapInWalletView.EXPIRY_WARNING_BLOCKS] blocks left to be swapped in. */
        data class ExpiringSoon(val blocksLeft: Int) : Status

        /** Held by a funding attempt that has not confirmed: being moved into Lightning. */
        data object Swapping : Status

        /** Too old to swap and not old enough to refund: the refund opens in [blocksUntilRefund]. */
        data class Locked(val blocksUntilRefund: Int) : Status

        /** Past the refund delay: the user alone can spend it. */
        data object Refundable : Status
    }
}

/**
 * The swap-in wallet as the swap-in screen shows it, from the wallet state the node already keeps.
 * Pure: nothing here reaches the node, so the rules can be tested over a hand-built [WalletState].
 */
object SwapInWalletView {
    /**
     * Two weeks of blocks: a coin this close to [SwapInParams.maxConfirmations] is flagged, so that a
     * user who opens the app once a week sees the warning at least twice before it locks.
     */
    const val EXPIRY_WARNING_BLOCKS: Int = 144 * 14

    /**
     * The addresses worth listing, newest first: every derived one that has been paid, and the
     * current one; then the [legacy] address, if it has ever been paid. Null until Electrum has said
     * which are used -- before that every address reads as unused, and the current one would be
     * index 0 for a wallet paid fifty times.
     *
     * Nothing past the current address: a swap-in wallet is one person's, so there is nobody to hand
     * a different address to (D3).
     */
    fun addresses(state: WalletState, legacy: String): List<SwapInAddress>? {
        val (_, current) = state.firstUnusedDerivedAddress ?: return null
        val derived = state.addresses.mapNotNull { (address, addressState) ->
            val index = (addressState.meta as? WalletState.AddressMeta.Derived)?.index ?: return@mapNotNull null
            when {
                addressState.alreadyUsed -> SwapInAddress(address, index, SwapInAddress.Status.USED, addressState.balance)
                index == current.index -> SwapInAddress(address, index, SwapInAddress.Status.CURRENT, addressState.balance)
                else -> null
            }
        }.sortedByDescending { it.index }
        val legacyRow = state.addresses[legacy]
            ?.takeIf { it.alreadyUsed }
            ?.let { SwapInAddress(legacy, null, SwapInAddress.Status.USED, it.balance) }
        return derived + listOfNotNull(legacyRow)
    }

    /**
     * Every coin on the wallet's addresses with its status, the mempool first and then newest first.
     *
     * [reserved] is what `SwapInManager.reservedWalletInputs` returns for the node's channels: the
     * coins an unconfirmed funding transaction spends. It is read from the channels rather than from
     * the node's `SwapInEvents.Requested`, because the channels are read back after a restart and the
     * event is not.
     */
    fun coins(state: WalletState, currentBlockHeight: Int, swapInParams: SwapInParams, reserved: Set<OutPoint>): List<SwapInCoin> {
        val wallet = WalletState.WalletWithConfirmations(swapInParams, currentBlockHeight, state.utxos)
        return state.utxos
            .map { utxo ->
                val confirmations = wallet.confirmations(utxo)
                SwapInCoin(
                    outPoint = utxo.outPoint,
                    amount = utxo.amount,
                    index = utxo.addressMeta.indexOrNull,
                    confirmations = confirmations,
                    status = statusOf(utxo, confirmations, swapInParams, reserved),
                )
            }
            .sortedWith(compareBy<SwapInCoin> { it.confirmations != 0 }.thenBy { it.confirmations })
    }

    private fun statusOf(utxo: WalletState.Utxo, confirmations: Int, params: SwapInParams, reserved: Set<OutPoint>): SwapInCoin.Status = when {
        utxo.outPoint in reserved -> SwapInCoin.Status.Swapping
        utxo.blockHeight == 0L -> SwapInCoin.Status.Unconfirmed
        confirmations < params.minConfirmations -> SwapInCoin.Status.Confirming(confirmations, params.minConfirmations)
        confirmations < params.maxConfirmations -> {
            val blocksLeft = params.maxConfirmations - confirmations
            if (blocksLeft < EXPIRY_WARNING_BLOCKS) SwapInCoin.Status.ExpiringSoon(blocksLeft) else SwapInCoin.Status.Ready
        }
        confirmations < params.refundDelay -> SwapInCoin.Status.Locked(params.refundDelay - confirmations)
        else -> SwapInCoin.Status.Refundable
    }

    /**
     * What opening the node's first channel with a swap-in would cost, worked out the way `Peer` works
     * it out for a node with no channel (`AddWalletInputsToChannel`, docs/personal-swap-in-wallet.md,
     * Phase 0): our mining fee for one coin on [swapInScript] and our share of the funding transaction,
     * plus the LSP's mining and service fees for the liquidity the node buys alongside. Null when the LSP
     * offers no rate for that purchase, which `Peer` also gives up on.
     *
     * The fee does not depend on the coin's amount, only on its script, so one placeholder coin stands in.
     */
    fun openingFee(swapInScript: ByteVector, rates: LiquidityAds.WillFundRates, feerate: FeeratePerKw, inboundLiquidityTarget: Satoshi): Satoshi? {
        val rate = rates.findRate(inboundLiquidityTarget) ?: return null
        val coinTx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32.Zeroes), 0), 0)), listOf(TxOut(Satoshi(100_000), swapInScript)), 0)
        val coin = WalletState.Utxo(coinTx.txid, 0, 0, coinTx, WalletState.AddressMeta.Derived(0))
        val ours = Transactions.weight2fee(feerate, FundingContributions.computeWeightPaid(isInitiator = true, null, PLACEHOLDER_FUNDING_SCRIPT, listOf(coin), emptyList()))
        val theirs = LiquidityAds.RequestFunding(inboundLiquidityTarget, rate, LiquidityAds.PaymentDetails.FromChannelBalance).fees(feerate, isChannelCreation = true)
        return ours + theirs.total
    }

    /** A 2-of-2 the length of a funding script: only its length reaches the weight. */
    private val PLACEHOLDER_FUNDING_SCRIPT: ByteVector = PrivateKey(ByteVector32.One).publicKey().let { key ->
        Script.write(Scripts.multiSig2of2(key, key)).byteVector()
    }

    /**
     * What the receive section says about a first deposit, given what opening its channel costs and the
     * node's liquidity policy -- or null when there is nothing to say: the node already has a channel
     * (a later deposit is a splice and pays mining fees only), the cost is not known yet, or the policy
     * refuses every swap, which Phase 4 says on its own.
     */
    fun firstDeposit(hasChannel: Boolean, openingFee: Satoshi?, policy: LiquidityPolicy?): FirstDeposit? {
        if (hasChannel || openingFee == null) return null
        val auto = policy as? LiquidityPolicy.Auto ?: return null
        if (!auto.skipAbsoluteFeeCheck && openingFee > auto.maxAbsoluteFee) return FirstDeposit(openingFee, minimum = null, overLimit = auto.maxAbsoluteFee)
        // The relative cap is a fraction of the amount, so it sets the smallest deposit that passes;
        // and `Peer` refuses one that leaves less than dust once the fee is paid.
        val relative = (openingFee.toLong() * 10_000 + auto.maxRelativeFeeBasisPoints - 1) / auto.maxRelativeFeeBasisPoints
        return FirstDeposit(openingFee, minimum = maxOf(Satoshi(relative), openingFee + DUST_LIMIT), overLimit = null)
    }

    /** `NodeParams.dustLimit`, which a channel-opening swap-in must clear once its fee is paid. */
    private val DUST_LIMIT = Satoshi(546)

    private val WalletState.AddressState.balance: Satoshi get() = utxos.map { it.amount }.sum()
}

/**
 * A node with no channel opens one with its first swap-in, and pays for it.
 *
 * [overLimit] is the node's fee limit when [fee] is above it: no deposit of any size swaps in until fees
 * fall or the limit is raised. Otherwise [minimum] is the smallest deposit the limit lets through.
 */
data class FirstDeposit(val fee: Satoshi, val minimum: Satoshi?, val overLimit: Satoshi?)

/**
 * Why the node refused to move the ready coins, from lightning-kmp's `LiquidityEvents.Rejected` or the
 * notification `PeerManager` filed for one (docs/personal-swap-in-wallet.md, Phase 4).
 */
sealed interface SwapInRefusal {
    /** The fee to move [amount] was over the policy's absolute cap. */
    data class OverLimit(val fee: Satoshi, val limit: Satoshi, val amount: Satoshi) : SwapInRefusal

    /** The fee was more than the policy's share of the [amount] being moved. */
    data class OverShare(val fee: Satoshi, val amount: Satoshi, val basisPoints: Int) : SwapInRefusal

    /** The policy turns every swap-in down. */
    data object Disabled : SwapInRefusal

    /** Anything else the node gives as a reason: it will try again at the next block. */
    data object Other : SwapInRefusal

    companion object {
        fun of(reason: LiquidityEvents.Rejected.Reason, amount: MilliSatoshi, fee: MilliSatoshi): SwapInRefusal = when (reason) {
            is LiquidityEvents.Rejected.Reason.TooExpensive.OverAbsoluteFee -> OverLimit(fee.truncateToSatoshi(), reason.maxAbsoluteFee, amount.truncateToSatoshi())
            is LiquidityEvents.Rejected.Reason.TooExpensive.OverRelativeFee -> OverShare(fee.truncateToSatoshi(), amount.truncateToSatoshi(), reason.maxRelativeFeeBasisPoints)
            LiquidityEvents.Rejected.Reason.PolicySetToDisabled -> Disabled
            else -> Other
        }

        fun of(notification: Notification.PaymentRejected): SwapInRefusal = when (notification) {
            is Notification.OverAbsoluteFee -> OverLimit(notification.fee.truncateToSatoshi(), notification.maxAbsoluteFee, notification.amount.truncateToSatoshi())
            is Notification.OverRelativeFee -> OverShare(notification.fee.truncateToSatoshi(), notification.amount.truncateToSatoshi(), notification.maxRelativeFeeBasisPoints)
            is Notification.FeePolicyDisabled -> Disabled
            else -> Other
        }
    }
}

/** Why coins that are ready have not moved yet: what the deposits section says above them. */
sealed interface SwapInWaiting {
    /** The node is not connected to the LSP. */
    data object NotConnected : SwapInWaiting

    /** The node asked, and turned its own request down. */
    data class Refused(val refusal: SwapInRefusal) : SwapInWaiting

    /** Connected, with nothing said yet: the node asks at the next block, or any moment. */
    data object ForNode : SwapInWaiting

    companion object {
        /**
         * Null unless a coin is ready to move, since only a ready coin has anything to wait for. The
         * connection comes first: a refusal filed before the node lost the LSP is not why it waits now.
         */
        fun of(coins: List<SwapInCoin>, connected: Boolean, refusal: SwapInRefusal?): SwapInWaiting? = when {
            coins.none { it.status == SwapInCoin.Status.Ready || it.status is SwapInCoin.Status.ExpiringSoon } -> null
            !connected -> NotConnected
            refusal != null -> Refused(refusal)
            else -> ForNode
        }
    }
}
