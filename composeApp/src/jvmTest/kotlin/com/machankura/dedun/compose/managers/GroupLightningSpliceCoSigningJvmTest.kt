package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Features
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelConfig
import fr.acinq.lightning.channel.ChannelFeatures
import fr.acinq.lightning.channel.ChannelFlags
import fr.acinq.lightning.channel.ChannelParams
import fr.acinq.lightning.channel.ChannelSpendSignature
import fr.acinq.lightning.channel.CommitParams
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.channel.LocalChannelParams
import fr.acinq.lightning.channel.RemoteChannelParams
import fr.acinq.lightning.crypto.FundingSigner
import fr.acinq.lightning.crypto.IcebergSigningTimeout
import fr.acinq.lightning.crypto.NonceGenerator
import fr.acinq.lightning.crypto.RemoteIcebergFundingSigner
import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.transactions.IncomingHtlc
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.LiquidityAds
import fr.acinq.lightning.wire.OnionRoutingPacket
import fr.acinq.lightning.wire.UpdateAddHtlc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A liquidity purchase signed by the group for real (`docs/group-lightning-liquidity.md`, Phase 5): the
 * operator's signer asks for the first commitment on the splice's new funding output and then for the
 * shared input, four devices' co-signers check both against the group's `buy-liquidity` and their own
 * ledgers, and the group's signature on the shared input combines with the LSP's into a splice the
 * script interpreter accepts. Then a payment over both live commitments, and each way a splice must be
 * refused.
 *
 * The splice is built here as the LSP would put it together -- its own input first, ours second, its
 * change and the new funding output -- with the amounts Phase 0 measured against eclair.
 */
abstract class GroupLightningSpliceCases(chain: Chain) {
    private val fixture = GroupLightningFixture(chain = chain)
    private val lspKey = PrivateKey(Random.nextBytes(32).byteVector32())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun close() {
        scope.cancel()
        fixture.close()
    }

    init {
        val base = fixture.dispatch
        fixture.dispatch = { to, event ->
            base(to, event)
            if (event.kind in GroupLightningEvents.CO_SIGNING) GroupLightningCoSigner.process(to.db, to.room, event, to.publicKey)
        }
    }

    private fun key() = PrivateKey(Random.nextBytes(32).byteVector32())

    private inner class Group(val all: List<GroupLightningFixture.Device>, val wallet: GroupLightningWalletManager.Wallet, val operatorTerm: Long)

    private suspend fun group(): Group {
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lspKey.publicKey().toString()).id)
        fixture.signSession(all, all[0], GroupLightningOperatorManager.nominateSelf(all[0].db, all[0].room, all[0].publicKey, fixture.chain).id)
        val wallet = GroupLightningWalletManager.walletFor(all[0].db, fixture.roomId, fixture.chain)!!
        return Group(all, wallet, GroupLightningOperatorManager.current(all[0].db, fixture.roomId, fixture.chain)!!.term)
    }

    /** A channel of 300,000 sats with the LSP, 100,000 of them the group's: room under the mainnet cap to buy. */
    private inner class Channel(group: Group) {
        val seed = ByteVector(group.wallet.seed!!.seed)
        val keyManager = GroupLightningCoSigner.keyManagerOf(seed, fixture.chain)
        val groupKey = PublicKey.fromHex(group.wallet.statement.icebergKey)
        val remoteFundingKey = key()
        val fundingTxId = TxId(Random.nextBytes(32).byteVector32())
        val input = Transactions.makeFundingInputInfo(fundingTxId, 0, Satoshi(300_000), groupKey, remoteFundingKey.publicKey(), Transactions.CommitmentFormat.SimpleTaprootChannels)
        val params = ChannelParams(
            channelId = Random.nextBytes(32).byteVector32(),
            channelConfig = ChannelConfig.standard,
            channelFeatures = ChannelFeatures(setOf()),
            localParams = LocalChannelParams(
                nodeId = PublicKey.fromHex(group.wallet.statement.nodeId),
                fundingKeyPath = keyManager.newFundingKeyPath(isChannelOpener = false),
                isChannelOpener = false,
                paysCommitTxFees = false,
                defaultFinalScriptPubKey = GroupWalletDerivation.derive(fixture.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript,
                features = Features.empty,
            ),
            remoteParams = RemoteChannelParams(lspKey.publicKey(), key().publicKey(), key().publicKey(), key().publicKey(), key().publicKey(), Features.empty),
            channelFlags = ChannelFlags(announceChannel = false, nonInitiatorPaysCommitFees = false),
        )
        val commitParams = CommitParams(Satoshi(546), 1_000_000_000L, 1.msat, CltvExpiryDelta(144), 30)
        val keys = keyManager.channelKeys(params.localParams.fundingKeyPath).withGroupBasepoints(group.wallet.basepoints.forChannel)
        val spec = CommitmentSpec(setOf(), FeeratePerKw(Satoshi(2_500)), toLocal = 200_000_000.msat, toRemote = 100_000_000.msat)

        /** The counterparty's commitment [index] on [funding], whose other key is [remote]. */
        fun commitment(index: Long, spec: CommitmentSpec, funding: Transactions.InputInfo = input, remote: PublicKey = remoteFundingKey.publicKey(), fundingTxIndex: Long = 0): Pair<Transactions.CommitTx, FundingSigner.SigningContext.RemoteCommitment> {
            val point = key().publicKey()
            val (tx, _) = Commitments.makeRemoteTxs(params, commitParams, keys.remoteCommitmentKeys(params, point), index, groupKey, remote, funding, Transactions.CommitmentFormat.SimpleTaprootChannels, spec)
            return tx to FundingSigner.SigningContext.RemoteCommitment(params, commitParams, index, point, spec, fundingTxIndex)
        }
    }

    /**
     * A purchase of [amount] as the LSP puts it together: its input and ours, its change and the new
     * funding output, with our share of the mining fee leaving the funding output and the purchase's
     * own fees ([miningFee], [serviceFee]) leaving our balance in the first commitment on it.
     */
    private inner class Purchase(
        val channel: Channel,
        val amount: Satoshi = 200_000.sat,
        val localContribution: Satoshi = (-221).sat,
        val miningFee: Satoshi = 200.sat,
        val serviceFee: Satoshi = 2_100.sat,
        newFundingKey: PublicKey? = null,
    ) {
        val lspInputKey = key()
        val lspInput = Transactions.InputInfo(
            OutPoint(TxId(Random.nextBytes(32).byteVector32()), 1),
            TxOut(amount + 50_000.sat, ByteVector(Script.write(Script.pay2tr(lspInputKey.publicKey().xOnly(), Crypto.TaprootTweak.KeyPathTweak)))),
        )
        val newRemoteKey = key()
        val newAmount = channel.input.txOut.amount + localContribution + amount
        private val newScript = Transactions.makeFundingInputInfo(TxId(ByteVector32.Zeroes), 0, newAmount, newFundingKey ?: channel.groupKey, newRemoteKey.publicKey(), Transactions.CommitmentFormat.SimpleTaprootChannels).txOut.publicKeyScript
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(lspInput.outPoint, 0xfffffffdL), TxIn(channel.input.outPoint, 0xfffffffdL)),
            txOut = listOf(TxOut(50_000.sat - 400.sat, ByteVector(Script.write(Script.pay2tr(key().publicKey().xOnly(), Crypto.TaprootTweak.KeyPathTweak)))), TxOut(newAmount, newScript)),
            lockTime = 0,
        )
        val newInput = Transactions.InputInfo(OutPoint(tx.txid, 1), tx.txOut[1])
        val fee: Satoshi get() = -localContribution + miningFee + serviceFee

        /** The first commitment on the new output, at the channel's commitment [index], our balance lower by [taken]. */
        fun firstCommitment(index: Long, taken: Satoshi = fee): Pair<Transactions.CommitTx, FundingSigner.SigningContext.RemoteCommitment> {
            val ours = channel.spec.toRemote - taken.toMilliSatoshi()
            return channel.commitment(index, channel.spec.copy(toRemote = ours, toLocal = newAmount.toMilliSatoshi() - ours), funding = newInput, remote = newRemoteKey.publicKey(), fundingTxIndex = 1)
        }

        fun context(purchase: LiquidityAds.Purchase? = LiquidityAds.Purchase.Standard(amount, LiquidityAds.Fees(miningFee, serviceFee), LiquidityAds.PaymentDetails.FromChannelBalance)) = FundingSigner.SigningContext.Splice(
            channelParams = channel.params, parentFundingTxIndex = 0, parentFunding = channel.input, fundingTxIndex = 1, fundingOutputIndex = 1,
            remoteFundingPubKey = newRemoteKey.publicKey(), localContribution = localContribution, remoteContribution = amount, purchase = purchase,
            feerate = FeeratePerKw(500.sat), spentOutputs = mapOf(channel.input.outPoint to channel.input.txOut, lspInput.outPoint to lspInput.txOut),
        )
    }

    private suspend fun <T> whilePumping(group: Group, timeout: Duration = 20.seconds, block: suspend () -> T): T {
        val result = scope.async { block() }
        withTimeout(timeout) {
            while (!result.isCompleted) {
                fixture.pump(group.all, GroupLightningEvents.CO_SIGNING)
                delay(50)
            }
        }
        return result.await()
    }

    private suspend fun operatorSigner(group: Group, deadline: Duration = 10.seconds): RemoteIcebergFundingSigner {
        val operator = group.all[0]
        val transport = GroupIcebergTransport.create(operator.db, operator.room, operator.publicKey, group.wallet, group.operatorTerm, scope)
        whilePumping(group) { transport.provision(GroupLightningCoSigner.POOL_WINDOW) }
        fixture.pump(group.all, GroupLightningEvents.CO_SIGNING)
        transport.provision(GroupLightningCoSigner.POOL_WINDOW)
        return RemoteIcebergFundingSigner(PublicKey.fromHex(group.wallet.statement.icebergKey), transport, deadline)
    }

    /** The group signs the counterparty's commitment [tx], whose other funding key is [remote]. */
    private suspend fun groupSigns(group: Group, signer: RemoteIcebergFundingSigner, channel: Channel, tx: Transactions.CommitTx, context: FundingSigner.SigningContext, remote: PrivateKey = channel.remoteFundingKey): Either<Throwable, Transaction> {
        val fundingTxId = tx.input.outPoint.txid
        val remoteNonce = NonceGenerator.signingNonce(remote.publicKey(), channel.groupKey, fundingTxId)
        val groupSig = whilePumping(group) { signer.signWithFreshNonce(tx, remote.publicKey(), mapOf(), fundingTxId, remoteNonce.publicNonce, context) }
        if (groupSig is Either.Left) return Either.Left(groupSig.value)
        val ours = (groupSig as Either.Right).value
        val theirs = (tx.partialSign(remote, channel.groupKey, mapOf(), remoteNonce, listOf(remoteNonce.publicNonce, ours.nonce)) as Either.Right<ChannelSpendSignature.PartialSignatureWithNonce>).value
        return tx.aggregateSigs(channel.groupKey, remote.publicKey(), ours, theirs, mapOf())
    }

    /**
     * The group signs the splice's shared input under a published nonce, the LSP adds its half and signs
     * its own input, and the script interpreter judges the whole transaction.
     */
    private suspend fun groupSplices(group: Group, signer: RemoteIcebergFundingSigner, purchase: Purchase, context: FundingSigner.SigningContext): Either<Throwable, Transaction> {
        val channel = purchase.channel
        val extra = mapOf(purchase.lspInput.outPoint to purchase.lspInput.txOut)
        val splice = Transactions.SpliceTx(channel.input, purchase.tx)
        val session = signer.publishedNonceSession(channel.fundingTxId, channel.remoteFundingKey.publicKey())
        val remoteNonce = NonceGenerator.signingNonce(channel.remoteFundingKey.publicKey(), channel.groupKey, channel.fundingTxId)
        val groupSig = whilePumping(group) { signer.signWithPublishedNonce(splice, channel.remoteFundingKey.publicKey(), extra, session, remoteNonce.publicNonce, context) }
        if (groupSig is Either.Left) return Either.Left(groupSig.value)
        val ours = (groupSig as Either.Right).value
        val theirs = (splice.partialSign(channel.remoteFundingKey, channel.groupKey, extra, remoteNonce, listOf(remoteNonce.publicNonce, ours.nonce)) as Either.Right<ChannelSpendSignature.PartialSignatureWithNonce>).value
        val shared = when (val s = splice.aggregateSigs(channel.groupKey, channel.remoteFundingKey.publicKey(), ours, theirs, extra)) {
            is Either.Left -> return s
            is Either.Right -> s.value
        }
        val spent = listOf(purchase.lspInput.txOut, channel.input.txOut)
        val lspSig = Transaction.signInputTaprootKeyPath(purchase.lspInputKey, shared, 0, spent, SigHash.SIGHASH_DEFAULT, null)
        val signed = shared.updateWitness(0, ScriptWitness(listOf(lspSig)))
        Transaction.correctlySpends(signed, mapOf(channel.input.outPoint to channel.input.txOut, purchase.lspInput.outPoint to purchase.lspInput.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        return Either.Right(signed)
    }

    private suspend fun authorise(group: Group, channel: Channel, amountSat: Long = 200_000, maxFeeSat: Long = 5_000): GroupLightningAuthorisationEvent.BuyLiquidity {
        val buy = GroupLightningAuthorisationEvent.BuyLiquidity(fixture.chain, 0, channel.params.channelId.toHex(), amountSat, maxFeeSat, (Clock.System.now() + 1.hours).epochSeconds, Random.nextBytes(8).toHexKey())
        val template = GroupLightningAuthorisationEvent.template(buy)
        // Signed before the wallet paid only members, which every signer refuses now: filed as signed.
        // docs/payouts-only.md, D9.
        fixture.fileSignedAsRoom(group.all, template.kind, template.tags, template.content)
        return buy
    }

    /** A group with a channel whose first commitment it has signed, which pins every co-signer to it. */
    private suspend fun pinned(): Triple<Group, Channel, RemoteIcebergFundingSigner> {
        val group = group()
        val channel = Channel(group)
        val signer = operatorSigner(group, deadline = 3.seconds)
        val (tx, context) = channel.commitment(0, channel.spec)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, tx, context))
        return Triple(group, channel, signer)
    }

    private fun assertRefused(result: Either<Throwable, Transaction>) = assertIs<IcebergSigningTimeout>(assertIs<Either.Left<Throwable>>(result).value)

    private suspend fun refusals(group: Group): List<String> = group.all.drop(1).flatMap { fixture.lines(it, ChatMessage.TYPE_LIGHTNING_REFUSED) }.map { it.content }

    @Test
    fun `the group buys the liquidity it authorised, and the splice spends the funding output`() = runBlocking<Unit> {
        val (group, channel, signer) = pinned()
        authorise(group, channel)
        val purchase = Purchase(channel)
        val (first, firstContext) = purchase.firstCommitment(0)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, first, firstContext, purchase.newRemoteKey), "the first commitment on the new output")
        assertIs<Either.Right<Transaction>>(groupSplices(group, signer, purchase, purchase.context()), "the shared input")

        // Every co-signer now holds a ledger for each output, the new one bound to the purchase.
        val key = GroupLightningCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
        group.all.forEach { d ->
            val ledgers = d.db.icebergCoSigningDao().channelLedgers(key, channel.params.channelId.toHex())
            assertEquals(listOf(0L, 1L), ledgers.map { it.fundingTxIndex }, "device ${d.index}")
            assertEquals("${channel.input.outPoint.txid}:0", ledgers[1].parentOutPoint)
        }
        assertTrue(refusals(group).isEmpty(), refusals(group).toString())

        // A payment arriving while both outputs are live: the group signs one commitment on each, at one number.
        val incoming = IncomingHtlc(UpdateAddHtlc(channel.params.channelId, 0, 10_000_000.msat, Random.nextBytes(32).byteVector32(), CltvExpiry(800_000), OnionRoutingPacket(0, ByteVector(ByteArray(33) { if (it == 0) 2 else 0 }), ByteVector.empty, ByteVector32.Zeroes)))
        val parentSpec = channel.spec.copy(toLocal = channel.spec.toLocal - 10_000_000.msat)
        val (onParent, onParentContext) = channel.commitment(1, parentSpec.copy(htlcs = setOf(fr.acinq.lightning.transactions.OutgoingHtlc(incoming.add))))
        assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, onParent, onParentContext))
        val newSpec = firstContext.spec
        val (onNew, onNewContext) = channel.commitment(1, newSpec.copy(toLocal = newSpec.toLocal - 10_000_000.msat, htlcs = setOf(fr.acinq.lightning.transactions.OutgoingHtlc(incoming.add))), purchase.newInput, purchase.newRemoteKey.publicKey(), 1)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, onNew, onNewContext, purchase.newRemoteKey))
    }

    @Test
    fun `a payment that settles on both live commitments spends its budget once`() = runBlocking<Unit> {
        val (group, channel, signer) = pinned()
        authorise(group, channel)
        val purchase = Purchase(channel)
        val (first, firstContext) = purchase.firstCommitment(0)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, first, firstContext, purchase.newRemoteKey))
        assertIs<Either.Right<Transaction>>(groupSplices(group, signer, purchase, purchase.context()))

        // The group authorises paying 40,000 sats, with a budget of exactly that and its fee cap.
        val preimage = Random.nextBytes(32).byteVector32()
        val invoice = fr.acinq.lightning.payment.Bolt11Invoice.create(fixture.chain, 40_000_000.msat, Crypto.sha256(preimage).byteVector32(), key(), Either.Left("coffee"), CltvExpiryDelta(144), INVOICE_FEATURES)
        val pay = GroupLightningAuthorisations.prepare(group.wallet, invoice.write(), null, 1_000_000).getOrThrow()
            .copy(payout = fixture.payoutFor(group.all, group.all[0]))
        fixture.signSession(group.all, group.all[3], GroupLightningAuthorisations.propose(group.all[3].db, group.all[3].room, group.all[3].publicKey, pay).id)
        val onion = OnionRoutingPacket(0, ByteVector(ByteArray(33) { if (it == 0) 2 else 0 }), ByteVector.empty, ByteVector32.Zeroes)
        val htlc = IncomingHtlc(UpdateAddHtlc(channel.params.channelId, 0, 40_000_000.msat, ByteVector32(pay.paymentHash), CltvExpiry(800_000), onion))
        val newSpec = firstContext.spec

        // Offered on both outputs at commitment 1, then settled on both at commitment 2.
        for ((index, settled) in listOf(1L to false, 2L to true)) {
            val parentSpec = channel.spec.copy(htlcs = if (settled) setOf() else setOf(htlc), toRemote = channel.spec.toRemote - 40_000_000.msat, toLocal = if (settled) channel.spec.toLocal + 40_000_000.msat else channel.spec.toLocal)
            val (onParent, onParentContext) = channel.commitment(index, parentSpec)
            assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, onParent, onParentContext), "the parent's commitment $index")
            val childSpec = newSpec.copy(htlcs = if (settled) setOf() else setOf(htlc), toRemote = newSpec.toRemote - 40_000_000.msat, toLocal = if (settled) newSpec.toLocal + 40_000_000.msat else newSpec.toLocal)
            val (onNew, onNewContext) = channel.commitment(index, childSpec, purchase.newInput, purchase.newRemoteKey.publicKey(), 1)
            assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, onNew, onNewContext, purchase.newRemoteKey), "the new output's commitment $index, after its sibling spent the budget")
        }
        group.all.forEach { d ->
            val mine = GroupLightningWalletManager.walletFor(d.db, fixture.roomId, fixture.chain)!!
            assertEquals(1_000_000L, GroupLightningAuthorisations.budgets(d.db, mine)[pay.paymentHash], "device ${d.index}: only the fee cap is left, spent once")
        }
    }

    @Test
    fun `without an authorisation, the first commitment on a splice's output is never signed`() = runBlocking<Unit> {
        val (group, channel, signer) = pinned()
        val purchase = Purchase(channel)
        val (first, firstContext) = purchase.firstCommitment(0)
        assertRefused(groupSigns(group, signer, channel, first, firstContext, purchase.newRemoteKey))
        assertTrue(refusals(group).all { "has not authorised buying liquidity" in it }, refusals(group).toString())
    }

    @Test
    fun `a splice that takes more than the fee cap from the group is refused`() = runBlocking<Unit> {
        val (group, channel, signer) = pinned()
        authorise(group, channel, maxFeeSat = 2_000)
        val purchase = Purchase(channel)
        val (first, firstContext) = purchase.firstCommitment(0)
        assertRefused(groupSigns(group, signer, channel, first, firstContext, purchase.newRemoteKey))
        assertTrue(refusals(group).all { "over the 2000000 the group agreed to pay" in it }, refusals(group).toString())
    }

    @Test
    fun `the shared input is never signed before the first commitment on the new output`() = runBlocking<Unit> {
        val (group, channel, signer) = pinned()
        authorise(group, channel)
        val purchase = Purchase(channel)
        assertRefused(groupSplices(group, signer, purchase, purchase.context()))
        assertTrue(refusals(group).all { "has not signed the first commitment on the splice's new output" in it }, refusals(group).toString())
    }

    @Test
    fun `a splice whose new output is not the group's key is refused`() = runBlocking<Unit> {
        val (group, channel, signer) = pinned()
        authorise(group, channel)
        val purchase = Purchase(channel, newFundingKey = key().publicKey())
        assertRefused(groupSplices(group, signer, purchase, purchase.context()))
        assertTrue(refusals(group).all { "is not the group's key with the LSP's" in it }, refusals(group).toString())
    }

    @Test
    fun `a splice that buys other than the group authorised, or pays another way, or costs other than it took, is refused`() = runBlocking<Unit> {
        val (group, channel, signer) = pinned()
        authorise(group, channel)
        val purchase = Purchase(channel)
        val (first, firstContext) = purchase.firstCommitment(0)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, first, firstContext, purchase.newRemoteKey))
        val fees = LiquidityAds.Fees(purchase.miningFee, purchase.serviceFee)
        // Another amount.
        assertRefused(groupSplices(group, signer, purchase, purchase.context(LiquidityAds.Purchase.Standard(150_000.sat, fees, LiquidityAds.PaymentDetails.FromChannelBalance))))
        // Paid from future HTLCs.
        assertRefused(groupSplices(group, signer, purchase, purchase.context(LiquidityAds.Purchase.Standard(purchase.amount, fees, LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(Random.nextBytes(32).byteVector32()))))))
        // Fees that do not add up to what the first commitment took.
        assertRefused(groupSplices(group, signer, purchase, purchase.context(LiquidityAds.Purchase.Standard(purchase.amount, LiquidityAds.Fees(purchase.miningFee, 1_000.sat), LiquidityAds.PaymentDetails.FromChannelBalance))))
        val said = refusals(group)
        assertTrue(said.any { "not the 200000 the group authorised" in it }, said.toString())
        assertTrue(said.any { "the group pays only from its balance" in it }, said.toString())
        assertTrue(said.any { "took 2521000 msat from the group, not the 1421000 the purchase costs" in it }, said.toString())
        // And the honest shared input still signs: no label was burned by the refusals.
        assertIs<Either.Right<Transaction>>(groupSplices(group, signer, purchase, purchase.context()))
    }
}

class GroupLightningSpliceCoSigningJvmTest : GroupLightningSpliceCases(Chain.Regtest)

class GroupLightningSpliceCoSigningMainnetJvmTest : GroupLightningSpliceCases(Chain.Mainnet)
