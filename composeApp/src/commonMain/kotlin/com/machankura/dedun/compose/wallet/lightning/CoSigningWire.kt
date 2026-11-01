package com.machankura.dedun.compose.wallet.lightning

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Features
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelConfig
import fr.acinq.lightning.channel.ChannelFeatures
import fr.acinq.lightning.channel.ChannelFlags
import fr.acinq.lightning.channel.ChannelParams
import fr.acinq.lightning.channel.CommitParams
import fr.acinq.lightning.channel.LocalChannelParams
import fr.acinq.lightning.channel.RemoteChannelParams
import fr.acinq.lightning.crypto.FundingSigner
import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.transactions.DirectedHtlc
import fr.acinq.lightning.transactions.IncomingHtlc
import fr.acinq.lightning.transactions.OutgoingHtlc
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.LiquidityAds
import fr.acinq.lightning.wire.OnionRoutingPacket
import fr.acinq.lightning.wire.UpdateAddHtlc
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.Serializable

/**
 * The messages the operator's node and the members' co-signers exchange (`docs/group-lightning-wallet.md`,
 * Phase 9), as JSON inside Marmot inner events.
 *
 * A signing request carries the transaction and what it is -- the [FundingSigner.SigningContext]
 * lightning-kmp built it from -- but never the hash to sign. A co-signer recomputes the hash from the
 * transaction, and rebuilds the transaction from the context with lightning-kmp's own builders, so an
 * operator that describes one transaction and asks for a signature on another is refused.
 *
 * HTLCs travel as their id, amount, hash, expiry and direction: everything a commitment is built from,
 * and not the 1,366-byte onion that only the next hop reads.
 */
object CoSigningWire {
    @Serializable
    data class Htlc(val incoming: Boolean, val id: Long, val amountMsat: Long, val paymentHash: String, val cltvExpiry: Long)

    @Serializable
    data class Spec(val feeratePerKw: Long, val toLocalMsat: Long, val toRemoteMsat: Long, val htlcs: List<Htlc>)

    @Serializable
    data class Commit(val dustLimitSat: Long, val maxHtlcValueInFlightMsat: Long, val htlcMinimumMsat: Long, val toSelfDelay: Int, val maxAcceptedHtlcs: Int)

    @Serializable
    data class Params(
        val channelId: String,
        val channelConfig: String,
        val channelFeatures: String,
        val localNodeId: String,
        val fundingKeyPath: String,
        val isChannelOpener: Boolean,
        val paysCommitTxFees: Boolean,
        val defaultFinalScriptPubKey: String,
        val localFeatures: String,
        val remoteNodeId: String,
        val revocationBasepoint: String,
        val paymentBasepoint: String,
        val delayedPaymentBasepoint: String,
        val htlcBasepoint: String,
        val remoteFeatures: String,
        val announceChannel: Boolean,
        val nonInitiatorPaysCommitFees: Boolean,
    )

    /**
     * Liquidity bought with a splice, as lightning-kmp's `LiquidityAds.Purchase` says it: [amountSat] of
     * inbound, for [miningFeeSat] (the LSP's inputs) and [serviceFeeSat], paid as [paymentType] names, a
     * BOLT name such as `from_channel_balance`. [feeCreditUsedMsat] is set only when fee credit paid part of it.
     */
    @Serializable
    data class Purchase(
        val amountSat: Long,
        val miningFeeSat: Long,
        val serviceFeeSat: Long,
        val paymentType: String,
        val feeCreditUsedMsat: Long? = null,
    )

    /**
     * One of the four things lightning-kmp says it is signing. [type] is `remote`, `local`, `closing` or
     * `splice`. A commitment names the funding output it is on by [fundingTxIndex]: while a splice is
     * unconfirmed there is one per output, at the same commitment number.
     *
     * A splice is its shared input: the parent funding output [parentFunding], of [parentFundingTxIndex],
     * spent by a transaction whose output [fundingOutputIndex] is the new funding output, of
     * [fundingTxIndex], shared with the counterparty's [newRemoteFundingPubKey]; what each side added
     * ([localContributionSat], [remoteContributionSat]), the [purchase] if it bought liquidity, and the
     * feerate it was built at. A splice has no [spec]: the money in the new output is the first commitment
     * on it, signed before this.
     */
    @Serializable
    data class Context(
        val type: String,
        val params: Params,
        val commit: Commit? = null,
        val commitIndex: Long? = null,
        val remotePerCommitmentPoint: String? = null,
        val spec: Spec? = null,
        val localScript: String? = null,
        val remoteScript: String? = null,
        val fundingTxIndex: Long = 0,
        val parentFundingTxIndex: Long? = null,
        val parentFunding: Input? = null,
        val fundingOutputIndex: Int? = null,
        val newRemoteFundingPubKey: String? = null,
        val localContributionSat: Long? = null,
        val remoteContributionSat: Long? = null,
        val purchase: Purchase? = null,
        val feeratePerKw: Long? = null,
    )

    @Serializable
    data class Input(val txid: String, val index: Long, val amountSat: Long, val script: String)

    /** Kind 30358, from the operator. [kind] is a [fr.acinq.lightning.crypto.IcebergRoundTwoRequest.Kind] name. */
    @Serializable
    data class SigningRequest(
        val wallet: String,
        val term: Long,
        val requestId: String,
        val kind: String,
        val label: String,
        val counter: Long,
        val tx: String,
        val input: Input,
        val fundingTxId: String,
        val remoteFundingPubKey: String,
        val commitIndex: Long? = null,
        val contributions: List<String>,
        val cosignerAggregatedNonce: String,
        val context: Context? = null,
        /**
         * What every other input of [tx] spends. A commitment or a close has one input, [input], and this
         * is empty; a splice's shared input is one of several, and its signature commits to all of them.
         */
        val extraUtxos: List<Input> = emptyList(),
    )

    /** Kind 30359: a co-signer saw [messageHash] under [label], and will sign nothing else under it. */
    @Serializable
    data class Echo(val wallet: String, val requestId: String, val label: String, val messageHash: String)

    /** Kind 30360: round-one contributions for consecutive pool counters from [from]. */
    @Serializable
    data class NonceBatch(val wallet: String, val from: Long, val contributions: List<String>)

    /** Kind 30361: one co-signer's round-two answer. */
    @Serializable
    data class SignatureShareMessage(val wallet: String, val requestId: String, val label: String, val share: String)

    private fun hex(bytes: ByteArray) = Hex.encode(bytes)
    private fun hex(v: ByteVector) = v.toHex()

    fun spec(spec: CommitmentSpec): Spec = Spec(
        feeratePerKw = spec.feerate.feerate.sat,
        toLocalMsat = spec.toLocal.toLong(),
        toRemoteMsat = spec.toRemote.toLong(),
        htlcs = spec.htlcs.map { h -> Htlc(h is IncomingHtlc, h.add.id, h.add.amountMsat.toLong(), h.add.paymentHash.toHex(), h.add.cltvExpiry.toLong()) }.sortedWith(compareBy({ it.incoming }, { it.id })),
    )

    /** A placeholder onion: a commitment is built from an HTLC's amount, hash and expiry, never from its onion. */
    private val noOnion = OnionRoutingPacket(0, ByteVector(ByteArray(33) { if (it == 0) 2 else 0 }), ByteVector.empty, ByteVector32.Zeroes)

    fun spec(wire: Spec, channelId: ByteVector32): CommitmentSpec = CommitmentSpec(
        htlcs = wire.htlcs.map { h ->
            val add = UpdateAddHtlc(channelId, h.id, h.amountMsat.msat, ByteVector32(h.paymentHash), CltvExpiry(h.cltvExpiry), noOnion)
            if (h.incoming) IncomingHtlc(add) else OutgoingHtlc(add)
        }.toSet<DirectedHtlc>(),
        feerate = FeeratePerKw(Satoshi(wire.feeratePerKw)),
        toLocal = wire.toLocalMsat.msat,
        toRemote = wire.toRemoteMsat.msat,
    )

    fun commit(c: CommitParams): Commit = Commit(c.dustLimit.sat, c.maxHtlcValueInFlightMsat, c.htlcMinimum.toLong(), c.toSelfDelay.toInt(), c.maxAcceptedHtlcs)

    fun commit(w: Commit): CommitParams = CommitParams(Satoshi(w.dustLimitSat), w.maxHtlcValueInFlightMsat, w.htlcMinimumMsat.msat, CltvExpiryDelta(w.toSelfDelay), w.maxAcceptedHtlcs)

    fun params(p: ChannelParams): Params = Params(
        channelId = p.channelId.toHex(),
        channelConfig = hex(p.channelConfig.toByteArray()),
        channelFeatures = hex(Features(p.channelFeatures.features.associateWith { FeatureSupport.Optional }).toByteArray()),
        localNodeId = p.localParams.nodeId.toString(),
        fundingKeyPath = p.localParams.fundingKeyPath.toString(),
        isChannelOpener = p.localParams.isChannelOpener,
        paysCommitTxFees = p.localParams.paysCommitTxFees,
        defaultFinalScriptPubKey = hex(p.localParams.defaultFinalScriptPubKey),
        localFeatures = hex(p.localParams.features.toByteArray()),
        remoteNodeId = p.remoteParams.nodeId.toString(),
        revocationBasepoint = p.remoteParams.revocationBasepoint.toString(),
        paymentBasepoint = p.remoteParams.paymentBasepoint.toString(),
        delayedPaymentBasepoint = p.remoteParams.delayedPaymentBasepoint.toString(),
        htlcBasepoint = p.remoteParams.htlcBasepoint.toString(),
        remoteFeatures = hex(p.remoteParams.features.toByteArray()),
        announceChannel = p.channelFlags.announceChannel,
        nonInitiatorPaysCommitFees = p.channelFlags.nonInitiatorPaysCommitFees,
    )

    fun params(w: Params): ChannelParams = ChannelParams(
        channelId = ByteVector32(w.channelId),
        channelConfig = ChannelConfig(Hex.decode(w.channelConfig)),
        channelFeatures = ChannelFeatures(Features(Hex.decode(w.channelFeatures)).activated.keys),
        localParams = LocalChannelParams(
            nodeId = PublicKey.fromHex(w.localNodeId),
            fundingKeyPath = KeyPath(w.fundingKeyPath),
            isChannelOpener = w.isChannelOpener,
            paysCommitTxFees = w.paysCommitTxFees,
            defaultFinalScriptPubKey = ByteVector(w.defaultFinalScriptPubKey),
            features = Features(Hex.decode(w.localFeatures)),
        ),
        remoteParams = RemoteChannelParams(
            nodeId = PublicKey.fromHex(w.remoteNodeId),
            revocationBasepoint = PublicKey.fromHex(w.revocationBasepoint),
            paymentBasepoint = PublicKey.fromHex(w.paymentBasepoint),
            delayedPaymentBasepoint = PublicKey.fromHex(w.delayedPaymentBasepoint),
            htlcBasepoint = PublicKey.fromHex(w.htlcBasepoint),
            features = Features(Hex.decode(w.remoteFeatures)),
        ),
        channelFlags = ChannelFlags(w.announceChannel, w.nonInitiatorPaysCommitFees),
    )

    fun context(c: FundingSigner.SigningContext): Context? = when (c) {
        FundingSigner.SigningContext.Unknown -> null
        is FundingSigner.SigningContext.RemoteCommitment -> Context("remote", params(c.channelParams), commit(c.commitParams), c.commitIndex, c.remotePerCommitmentPoint.toString(), spec(c.spec), fundingTxIndex = c.fundingTxIndex)
        is FundingSigner.SigningContext.LocalCommitment -> Context("local", params(c.channelParams), commit(c.commitParams), c.commitIndex, null, spec(c.spec), fundingTxIndex = c.fundingTxIndex)
        is FundingSigner.SigningContext.Closing -> Context("closing", params(c.channelParams), null, null, null, spec(c.spec), hex(c.localScript), hex(c.remoteScript))
        is FundingSigner.SigningContext.Splice -> Context(
            type = "splice",
            params = params(c.channelParams),
            fundingTxIndex = c.fundingTxIndex,
            parentFundingTxIndex = c.parentFundingTxIndex,
            parentFunding = input(c.parentFunding),
            fundingOutputIndex = c.fundingOutputIndex,
            newRemoteFundingPubKey = c.remoteFundingPubKey.toString(),
            localContributionSat = c.localContribution.sat,
            remoteContributionSat = c.remoteContribution.sat,
            purchase = c.purchase?.let(::purchase),
            feeratePerKw = c.feerate.feerate.sat,
        )
    }

    fun purchase(p: LiquidityAds.Purchase): Purchase = Purchase(
        amountSat = p.amount.sat,
        miningFeeSat = p.fees.miningFee.sat,
        serviceFeeSat = p.fees.serviceFee.sat,
        paymentType = paymentTypeName(p.paymentDetails.paymentType),
        feeCreditUsedMsat = (p as? LiquidityAds.Purchase.WithFeeCredit)?.feeCreditUsed?.toLong(),
    )

    /** The BOLT name of a liquidity-ads payment type, which the wire carries: a co-signer reads only `from_channel_balance` as one it signs. */
    fun paymentTypeName(t: LiquidityAds.PaymentType): String = when (t) {
        LiquidityAds.PaymentType.FromChannelBalance -> FROM_CHANNEL_BALANCE
        LiquidityAds.PaymentType.FromFutureHtlc -> "from_future_htlc"
        LiquidityAds.PaymentType.FromFutureHtlcWithPreimage -> "from_future_htlc_with_preimage"
        LiquidityAds.PaymentType.FromChannelBalanceForFutureHtlc -> "from_channel_balance_for_future_htlc"
        is LiquidityAds.PaymentType.Unknown -> "unknown_${t.bitIndex}"
    }

    const val FROM_CHANNEL_BALANCE = "from_channel_balance"

    /** What every input of [tx] spends, [input] among them, in input order; null when one is not known. */
    fun spentOutputs(tx: Transaction, input: Transactions.InputInfo, extra: List<Input>): List<TxOut>? {
        val known = extra.map(::inputInfo).associate { it.outPoint to it.txOut } + (input.outPoint to input.txOut)
        return tx.txIn.map { known[it.outPoint] ?: return null }
    }

    fun input(info: Transactions.InputInfo): Input = Input(info.outPoint.txid.toString(), info.outPoint.index, info.txOut.amount.sat, hex(info.txOut.publicKeyScript))

    fun inputInfo(w: Input): Transactions.InputInfo = Transactions.InputInfo(OutPoint(TxId(w.txid), w.index), TxOut(Satoshi(w.amountSat), ByteVector(w.script)))

    fun tx(tx: Transaction): String = hex(Transaction.write(tx))

    fun tx(hex: String): Transaction = Transaction.read(hex)
}
