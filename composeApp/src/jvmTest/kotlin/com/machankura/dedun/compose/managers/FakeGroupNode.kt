package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.machankura.dedun.compose.wallet.lightning.WalletWire
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.OfferManager
import kotlinx.coroutines.channels.Channel

/**
 * A stand-in for the group's lightning-kmp node: no LSP to connect to, channels and payments as a test
 * sets them, and invoices signed with the node key the configuration carries, so they verify as the
 * group's node's.
 */
class FakeGroupNode(private val configuration: GroupLightningNode.NodeConfiguration) : GroupLightningNode.PeerHandle {
    var connected = false
    var disconnected = false
    /** How many times the node has dialled the LSP. */
    var connects = 0
    /** The attempt number each dial carried. */
    val attempts = mutableListOf<Int>()
    /** A reason sent here ends the current connection, as the LSP hanging up would, with that reason. */
    val drops = Channel<String>(Channel.UNLIMITED)
    var channels = listOf<WalletWire.ChannelView>()
    val payments = mutableListOf<WalletWire.PaymentView>()
    val invoices = mutableListOf<Bolt11Invoice>()
    /** Every payment asked of the node, as (invoice, amount). */
    val paid = mutableListOf<Pair<String, Long>>()
    var paymentsSucceed = true
    /** Every close asked of the node, as (channel, script), and every force close, by channel. */
    val closed = mutableListOf<Pair<String, ByteVector>>()
    val forceClosed = mutableListOf<String>()
    var heldOutputs = listOf<GroupSweep.Input>()
    var rates: WalletWire.FundingRates? = null
    var feerate: Long? = 2
    /** Every purchase asked of the node, as (authorisation id, amount, fee cap). */
    val bought = mutableListOf<Triple<String, Long, Long>>()
    /** What a purchase costs here, and so whether it is made: one over the cap is never sent. */
    var purchaseFee = 2_521L

    override suspend fun connect(attempt: Int): Boolean { connected = true; connects++; attempts += attempt; return true }
    override fun disconnect() { disconnected = true }
    override suspend fun awaitDisconnect(): String = drops.receive().also { connected = false }
    override fun commitmentNumbers(): Map<String, Long> = mapOf("ab".repeat(32) to 7L)
    override fun channels(): List<WalletWire.ChannelView> = channels
    override fun payments(): List<WalletWire.PaymentView> = payments.toList()
    override suspend fun createInvoice(amountMsat: Long?, description: String): String =
        Bolt11Invoice.create(
            configuration.chain, amountMsat?.let(::MilliSatoshi), randomBytes32(), configuration.keyManager.nodeKeys.nodeKey.privateKey,
            Either.Left(description), CltvExpiryDelta(144), INVOICE_FEATURES,
        ).also { invoices += it }.write()
    override suspend fun close(channelId: String, scriptPubKey: ByteVector) { closed += channelId to scriptPubKey }
    override suspend fun forceClose(channelId: String) { forceClosed += channelId }
    override fun heldOutputs(): List<GroupSweep.Input> = heldOutputs
    override fun fundingRates(): WalletWire.FundingRates? = rates
    override fun feeratePerByte(): Long? = feerate
    override suspend fun buyLiquidity(id: String, channelId: String, amountSat: Long, maxFeeSat: Long): WalletWire.LiquidityPurchase {
        if (purchaseFee > maxFeeSat) return WalletWire.LiquidityPurchase(id, channelId, amountSat, WalletWire.FAILED, reason = "it would cost $purchaseFee sats, over the $maxFeeSat the group agreed to")
        bought += Triple(id, amountSat, maxFeeSat)
        return WalletWire.LiquidityPurchase(id, channelId, amountSat, WalletWire.PENDING, fundingTxId = "cd".repeat(32), feeSat = purchaseFee)
    }
    override suspend fun hasPaid(paymentHash: String): Boolean = payments.any { !it.incoming && it.paymentHash == paymentHash }
    override suspend fun pay(bolt11: String, amountMsat: Long): WalletWire.PaymentView {
        paid += bolt11 to amountMsat
        val invoice = Bolt11Invoice.read(bolt11).get()
        return WalletWire.PaymentView("out${paid.size}", incoming = false, amountMsat = amountMsat, feesMsat = 1_000, paymentHash = invoice.paymentHash.toHex(), description = invoice.description, completedAt = 1, succeeded = paymentsSucceed)
            .also { payments += it }
    }
}

/** What a Bolt11 invoice must advertise to be read back at all. */
val INVOICE_FEATURES = Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory)
