package com.machankura.dedun.compose.wallet.lightning

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.FundingContributions
import fr.acinq.lightning.channel.SharedFundingInput
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.wire.LiquidityAds

/**
 * What buying inbound liquidity costs, worked out on a member's own device from what the operator
 * reported (`docs/group-lightning-liquidity.md`, decision L5 and Phase 7): the LSP's rates and the
 * node's feerate. The service fee follows from the rate alone; the mining fee from the feerate, which
 * is the operator's claim, so the authorisation's cap leaves room above it.
 *
 * The arithmetic is lightning-kmp's own, as Phoenix's estimate is: the rate's [LiquidityAds.FundingRate.fees]
 * for the service fee and the LSP's inputs, and [FundingContributions.computeWeightPaid] for our share of
 * the splice, which depends only on the sizes of a taproot funding input and output.
 */
object LiquidityPrice {
    /** Phoenix's amounts (`LiquidityLimits.liquidityOptions`), in sats. */
    val OPTIONS: List<Long> = listOf(100_000, 250_000, 500_000, 1_000_000, 2_000_000, 10_000_000)

    /** How much above the estimate the cap the group signs sits, in percent: room for the feerate to rise before the purchase. */
    const val CAP_HEADROOM_PERCENT = 50

    /** Phoenix warns when the fee reaches this share of the amount, in percent. */
    const val EXPENSIVE_PERCENT = 25

    /** What one purchase costs: our share of the mining fee, the LSP's inputs' share, and the service fee. */
    data class Price(val ourMiningSat: Long, val lspMiningSat: Long, val serviceSat: Long) {
        val miningSat: Long get() = ourMiningSat + lspMiningSat
        val totalSat: Long get() = miningSat + serviceSat
    }

    private fun rateOf(rate: WalletWire.FundingRate) = LiquidityAds.FundingRate(
        Satoshi(rate.minAmountSat), Satoshi(rate.maxAmountSat), rate.fundingWeight, rate.feeProportional, Satoshi(rate.feeBaseSat), Satoshi(rate.channelCreationFeeSat),
    )

    /** The rate that sells [amountSat], as lightning-kmp's `findRate` picks it: the first that covers it. */
    fun rateFor(rates: WalletWire.FundingRates, amountSat: Long): WalletWire.FundingRate? =
        rates.rates.firstOrNull { amountSat in it.minAmountSat..it.maxAmountSat }

    /** Our weight in a splice that only buys liquidity: the shared input and output and the common fields. */
    private val ourWeight: Int by lazy {
        val script = Transactions.makeFundingInputInfo(TxId(ByteArray(32)), 0, Satoshi(0), PrivateKey(ByteArray(32) { 1 }).publicKey(), PrivateKey(ByteArray(32) { 2 }).publicKey(), Transactions.CommitmentFormat.SimpleTaprootChannels).txOut.publicKeyScript
        val shared = SharedFundingInput(Transactions.InputInfo(OutPoint(TxId(ByteArray(32)), 0), TxOut(Satoshi(0), script)), 0, PrivateKey(ByteArray(32) { 2 }).publicKey(), Transactions.CommitmentFormat.SimpleTaprootChannels)
        FundingContributions.computeWeightPaid(isInitiator = true, sharedInput = shared, sharedOutputScript = ByteVector(script.toByteArray()), walletInputs = emptyList(), localOutputs = emptyList())
    }

    /** What buying [amountSat] would cost at [feeratePerByte], or null when no rate sells that amount. */
    fun price(rates: WalletWire.FundingRates, amountSat: Long, feeratePerByte: Long): Price? {
        val rate = rateFor(rates, amountSat)?.let(::rateOf) ?: return null
        val feerate = FeeratePerKw(FeeratePerByte(Satoshi(feeratePerByte)))
        val fees = rate.fees(feerate, Satoshi(amountSat), Satoshi(amountSat), isChannelCreation = false)
        return Price(Transactions.weight2fee(feerate, ourWeight).sat, fees.miningFee.sat, fees.serviceFee.sat)
    }

    /** The fee cap a proposal carries by default: the estimate and [CAP_HEADROOM_PERCENT] more. */
    fun defaultCap(price: Price): Long = price.totalSat + price.totalSat * CAP_HEADROOM_PERCENT / 100

    /** Whether the fee is [EXPENSIVE_PERCENT] of the amount or more, as Phoenix warns. */
    fun expensive(price: Price, amountSat: Long): Boolean = price.totalSat * 100 >= amountSat * EXPENSIVE_PERCENT

    /**
     * The amounts that can be offered for a channel of [capacitySat]: Phoenix's, less those no rate sells
     * and those that would take the channel over [capSat], the most a channel may hold on this network.
     */
    fun offered(rates: WalletWire.FundingRates, capacitySat: Long, capSat: Long?): List<Long> =
        OPTIONS.filter { rateFor(rates, it) != null && (capSat == null || capacitySat + it <= capSat) }

    /** Whether the group can be asked to buy liquidity now, and if not, why not. */
    sealed class Availability {
        data object NoOperator : Availability()
        /** The operator has not reported the wallet, or not what the LSP sells. */
        data object NoPrices : Availability()
        /** No channel to splice: the first payment opens one. */
        data object NoChannel : Availability()
        /** The channel is at the most a channel may hold on this network. */
        data object AtTheCap : Availability()
        /** The LSP sells none of [OPTIONS]. */
        data object NothingSold : Availability()
        data class Available(val channel: WalletWire.ChannelView, val offered: List<Long>, val rates: WalletWire.FundingRates, val feeratePerByte: Long) : Availability()
    }

    /** What [snapshot] lets a member ask for, on a network whose channels hold at most [capSat]. */
    fun availability(snapshot: WalletWire.Snapshot?, hasOperator: Boolean, capSat: Long?): Availability {
        if (!hasOperator) return Availability.NoOperator
        if (snapshot == null) return Availability.NoPrices
        val channel = snapshot.channels.firstOrNull { it.state == NORMAL } ?: return Availability.NoChannel
        val rates = snapshot.fundingRates ?: return Availability.NoPrices
        val feerate = snapshot.feeratePerByte ?: return Availability.NoPrices
        val offered = offered(rates, channel.capacitySat, capSat)
        return when {
            offered.isNotEmpty() -> Availability.Available(channel, offered, rates, feerate)
            offered(rates, channel.capacitySat, null).isNotEmpty() -> Availability.AtTheCap
            else -> Availability.NothingSold
        }
    }

    /** lightning-kmp's name for the state a channel can be spliced in. */
    private const val NORMAL = "Normal"
}
