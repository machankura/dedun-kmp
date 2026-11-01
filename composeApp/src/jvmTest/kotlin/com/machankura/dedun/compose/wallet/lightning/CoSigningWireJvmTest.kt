package com.machankura.dedun.compose.wallet.lightning

import com.machankura.dedun.compose.managers.GroupLightningCoSigner
import com.machankura.dedun.compose.network.serialization.CommonJson
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Features
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
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.LiquidityAds
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The co-signing wire with a splice on it (`docs/group-lightning-liquidity.md`, Phase 3): every context
 * survives the trip, a request from a build before it still reads, and the hash a co-signer recomputes
 * for a splice's shared input is the one lightning-kmp signs.
 */
class CoSigningWireJvmTest {
    private fun key() = PrivateKey(Random.nextBytes(32).byteVector32())
    private fun p2tr() = ByteVector(Script.write(Script.pay2tr(key().publicKey().xOnly(), fr.acinq.bitcoin.Crypto.TaprootTweak.KeyPathTweak)))

    private val params = ChannelParams(
        channelId = Random.nextBytes(32).byteVector32(),
        channelConfig = ChannelConfig.standard,
        channelFeatures = ChannelFeatures(setOf()),
        localParams = LocalChannelParams(key().publicKey(), fr.acinq.bitcoin.KeyPath("m/1/2"), isChannelOpener = false, paysCommitTxFees = false, defaultFinalScriptPubKey = ByteVector("0014" + "11".repeat(20)), features = Features.empty),
        remoteParams = RemoteChannelParams(key().publicKey(), key().publicKey(), key().publicKey(), key().publicKey(), key().publicKey(), Features.empty),
        channelFlags = ChannelFlags(announceChannel = false, nonInitiatorPaysCommitFees = false),
    )
    private val commitParams = CommitParams(Satoshi(546), 1_000_000_000L, 1.msat, CltvExpiryDelta(144), 30)
    private val spec = CommitmentSpec(setOf(), FeeratePerKw(Satoshi(2_500)), toLocal = 600_000_000.msat, toRemote = 400_000_000.msat)

    private val groupKey = key().publicKey()
    private val oldLspKey = key().publicKey()
    private val newLspKey = key().publicKey()
    private val parent = Transactions.makeFundingInputInfo(TxId(Random.nextBytes(32).byteVector32()), 1, Satoshi(1_000_000), groupKey, oldLspKey, Transactions.CommitmentFormat.SimpleTaprootChannels)
    private val lspInput = Transactions.InputInfo(OutPoint(TxId(Random.nextBytes(32).byteVector32()), 0), TxOut(Satoshi(300_000), p2tr()))
    private val lspInput2 = Transactions.InputInfo(OutPoint(TxId(Random.nextBytes(32).byteVector32()), 3), TxOut(Satoshi(50_000), p2tr()))

    /** A liquidity purchase as the LSP would put it together: its two inputs around ours, its change, the new funding output. */
    private val newFunding = Transactions.makeFundingInputInfo(TxId(ByteArray(32)), 0, Satoshi(1_199_700), groupKey, newLspKey, Transactions.CommitmentFormat.SimpleTaprootChannels).txOut
    private val spliceTx = Transaction(
        version = 2,
        txIn = listOf(TxIn(lspInput.outPoint, 0xfffffffdL), TxIn(parent.outPoint, 0xfffffffdL), TxIn(lspInput2.outPoint, 0xfffffffdL)),
        txOut = listOf(TxOut(Satoshi(149_000), p2tr()), newFunding),
        lockTime = 0,
    )
    private val purchase = LiquidityAds.Purchase.Standard(Satoshi(200_000), LiquidityAds.Fees(Satoshi(200), Satoshi(2_100)), LiquidityAds.PaymentDetails.FromChannelBalance)
    private val splice = FundingSigner.SigningContext.Splice(
        channelParams = params,
        parentFundingTxIndex = 0,
        parentFunding = parent,
        fundingTxIndex = 1,
        fundingOutputIndex = 1,
        remoteFundingPubKey = newLspKey,
        localContribution = Satoshi(-300),
        remoteContribution = Satoshi(200_000),
        purchase = purchase,
        feerate = FeeratePerKw(Satoshi(500)),
        spentOutputs = mapOf(parent.outPoint to parent.txOut, lspInput.outPoint to lspInput.txOut, lspInput2.outPoint to lspInput2.txOut),
    )

    private fun request(context: FundingSigner.SigningContext, input: Transactions.InputInfo, tx: Transaction, extra: Map<OutPoint, TxOut> = emptyMap()) = CoSigningWire.SigningRequest(
        wallet = "room:mainnet:0", term = 1, requestId = "r", kind = "PUBLISHED", label = "00".repeat(32), counter = 0,
        tx = CoSigningWire.tx(tx), input = CoSigningWire.input(input), fundingTxId = input.outPoint.txid.toString(), remoteFundingPubKey = oldLspKey.toString(),
        contributions = emptyList(), cosignerAggregatedNonce = "", context = CoSigningWire.context(context),
        extraUtxos = extra.map { (o, t) -> CoSigningWire.input(Transactions.InputInfo(o, t)) },
    )

    private fun roundTrip(r: CoSigningWire.SigningRequest) =
        CommonJson.decodeFromString(CoSigningWire.SigningRequest.serializer(), CommonJson.encodeToString(CoSigningWire.SigningRequest.serializer(), r))

    @Test
    fun `every context survives the trip, and a commitment names its funding output`() {
        val point = key().publicKey()
        val remote = assertNotNull(roundTrip(request(FundingSigner.SigningContext.RemoteCommitment(params, commitParams, 7, point, spec, fundingTxIndex = 1), parent, spliceTx)).context)
        assertEquals("remote", remote.type)
        assertEquals(1, remote.fundingTxIndex)
        assertEquals(7, remote.commitIndex)
        assertEquals(spec, CoSigningWire.spec(assertNotNull(remote.spec), params.channelId))
        assertEquals(params, CoSigningWire.params(remote.params))

        val local = assertNotNull(roundTrip(request(FundingSigner.SigningContext.LocalCommitment(params, commitParams, 7, spec, fundingTxIndex = 2), parent, spliceTx)).context)
        assertEquals("local", local.type)
        assertEquals(2, local.fundingTxIndex)

        val closing = assertNotNull(roundTrip(request(FundingSigner.SigningContext.Closing(params, ByteVector("51"), ByteVector("52"), spec), parent, spliceTx)).context)
        assertEquals("closing", closing.type)
        assertEquals("51", closing.localScript)

        val read = assertNotNull(roundTrip(request(splice, parent, spliceTx, splice.spentOutputs - parent.outPoint)).context)
        assertEquals(GroupLightningCoSigner.SPLICE, read.type)
        assertNull(read.spec, "a splice carries no commitment")
        assertEquals(0, read.parentFundingTxIndex)
        assertEquals(1, read.fundingTxIndex)
        assertEquals(parent, CoSigningWire.inputInfo(assertNotNull(read.parentFunding)))
        assertEquals(1, read.fundingOutputIndex)
        assertEquals(newLspKey.toString(), read.newRemoteFundingPubKey)
        assertEquals(-300, read.localContributionSat)
        assertEquals(200_000, read.remoteContributionSat)
        assertEquals(500, read.feeratePerKw)
        assertEquals(CoSigningWire.Purchase(200_000, 200, 2_100, CoSigningWire.FROM_CHANNEL_BALANCE), read.purchase)
    }

    @Test
    fun `fee credit and other payment types are named, so a co-signer can refuse them`() {
        val withCredit = LiquidityAds.Purchase.WithFeeCredit(Satoshi(200_000), LiquidityAds.Fees(Satoshi(200), Satoshi(2_100)), 1_500_000.msat, LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(Random.nextBytes(32).byteVector32())))
        assertEquals(CoSigningWire.Purchase(200_000, 200, 2_100, "from_future_htlc", feeCreditUsedMsat = 1_500_000), CoSigningWire.purchase(withCredit))
    }

    @Test
    fun `a request from a build before splices still reads, as a commitment on the first funding output`() {
        val old = request(FundingSigner.SigningContext.RemoteCommitment(params, commitParams, 3, key().publicKey(), spec), parent, spliceTx)
        val json = CommonJson.encodeToString(CoSigningWire.SigningRequest.serializer(), old)
            .replace(",\"fundingTxIndex\":0", "").replace(",\"extraUtxos\":[]", "")
        val read = CommonJson.decodeFromString(CoSigningWire.SigningRequest.serializer(), json)
        assertEquals(0, read.context!!.fundingTxIndex)
        assertEquals(emptyList(), read.extraUtxos)
    }

    @Test
    fun `the hash a co-signer recomputes for a splice's shared input is the one lightning-kmp signs`() {
        val extra = splice.spentOutputs - parent.outPoint
        val expected = Transactions.SpliceTx(parent, spliceTx).taprootSighash(extra)
        assertEquals(expected, GroupLightningCoSigner.messageOf(request(splice, parent, spliceTx, extra)), "the shared input is input 1 of 3, over all three spent outputs")
        // Without what the other inputs spend there is no hash, and so nothing to sign.
        assertNull(GroupLightningCoSigner.messageOf(request(splice, parent, spliceTx, extra - lspInput2.outPoint)))
        // A commitment is unchanged: one input, no extras.
        val commitTx = Transaction(2, listOf(TxIn(parent.outPoint, 0)), listOf(TxOut(Satoshi(999_000), p2tr())), 0)
        assertEquals(commitTx.hashForSigningTaprootKeyPath(0, listOf(parent.txOut), fr.acinq.bitcoin.SigHash.SIGHASH_DEFAULT), GroupLightningCoSigner.messageOf(request(splice, parent, commitTx)))
    }
}
