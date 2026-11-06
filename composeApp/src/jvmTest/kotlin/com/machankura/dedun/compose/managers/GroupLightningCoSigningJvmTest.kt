package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.cancelAndWait
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
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
import fr.acinq.lightning.wire.OnionRoutingPacket
import fr.acinq.lightning.wire.UpdateAddHtlc
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A commitment signed by the group for real: an operator's `RemoteIcebergFundingSigner` asks, four
 * devices' co-signers check and echo and answer, and the group's partial signature combines with a stock
 * counterparty's into a transaction that spends the funding output (`docs/group-lightning-wallet.md`,
 * Phase 9). Then the ways a request must fail.
 *
 * The channel is built here from its parameters rather than negotiated with a peer: what is under test is
 * that every member rebuilds the same transaction from the same context and signs it only if it leaves
 * the group what the group agreed to.
 */
abstract class GroupLightningCoSigningCases(chain: Chain) {
    private val fixture = GroupLightningFixture(chain = chain)
    private val lspKey = PrivateKey(Random.nextBytes(32).byteVector32())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun close() {
        scope.cancelAndWait()
        fixture.close()
    }

    init {
        val base = fixture.dispatch
        fixture.dispatch = { to, event ->
            base(to, event)
            if (event.kind in GroupLightningEvents.CO_SIGNING) GroupLightningCoSigner.process(to.db, to.room, event, to.publicKey)
        }
    }

    private inner class Group(val all: List<GroupLightningFixture.Device>, val wallet: GroupLightningWalletManager.Wallet, val operatorTerm: Long)

    private suspend fun group(): Group {
        val all = fixture.group()
        fixture.runCeremony(all)
        val session = GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lspKey.publicKey().toString())
        fixture.signSession(all, all[0], session.id)
        val elected = GroupLightningOperatorManager.nominateSelf(all[0].db, all[0].room, all[0].publicKey, fixture.chain)
        fixture.signSession(all, all[0], elected.id)
        val wallet = GroupLightningWalletManager.walletFor(all[0].db, fixture.roomId, fixture.chain)!!
        return Group(all, wallet, GroupLightningOperatorManager.current(all[0].db, fixture.roomId, fixture.chain)!!.term)
    }

    /** A channel with the LSP, as the operator's node would hold it. */
    private inner class Channel(group: Group, ours: Long, theirs: Long, capacity: Satoshi = Satoshi(1_000_000)) {
        val seed = ByteVector(group.wallet.seed!!.seed)
        val keyManager = GroupLightningCoSigner.keyManagerOf(seed, fixture.chain)
        val groupKey = PublicKey.fromHex(group.wallet.statement.icebergKey)
        val remoteFundingKey = PrivateKey(Random.nextBytes(32).byteVector32())
        val fundingTxId = TxId(Random.nextBytes(32).byteVector32())
        val input = Transactions.makeFundingInputInfo(fundingTxId, 0, capacity, groupKey, remoteFundingKey.publicKey(), Transactions.CommitmentFormat.SimpleTaprootChannels)
        val onchain = fixture.account
        val params = ChannelParams(
            channelId = Random.nextBytes(32).byteVector32(),
            channelConfig = ChannelConfig.standard,
            channelFeatures = ChannelFeatures(setOf()),
            localParams = LocalChannelParams(
                nodeId = PublicKey.fromHex(group.wallet.statement.nodeId),
                fundingKeyPath = keyManager.newFundingKeyPath(isChannelOpener = false),
                isChannelOpener = false,
                paysCommitTxFees = false,
                defaultFinalScriptPubKey = GroupWalletDerivation.derive(onchain, GroupWalletDerivation.RECEIVE, 0).pubkeyScript,
                features = Features.empty,
            ),
            remoteParams = RemoteChannelParams(
                nodeId = lspKey.publicKey(),
                revocationBasepoint = PrivateKey(Random.nextBytes(32).byteVector32()).publicKey(),
                paymentBasepoint = PrivateKey(Random.nextBytes(32).byteVector32()).publicKey(),
                delayedPaymentBasepoint = PrivateKey(Random.nextBytes(32).byteVector32()).publicKey(),
                htlcBasepoint = PrivateKey(Random.nextBytes(32).byteVector32()).publicKey(),
                features = Features.empty,
            ),
            channelFlags = ChannelFlags(announceChannel = false, nonInitiatorPaysCommitFees = false),
        )
        val commitParams = CommitParams(Satoshi(546), 1_000_000_000L, 1.msat, CltvExpiryDelta(144), 30)
        val keys = keyManager.channelKeys(params.localParams.fundingKeyPath).withGroupBasepoints(group.wallet.basepoints.forChannel)
        var spec = CommitmentSpec(setOf(), FeeratePerKw(Satoshi(2_500)), toLocal = theirs.msat, toRemote = ours.msat)

        fun commitment(index: Long, spec: CommitmentSpec, point: PublicKey = PrivateKey(Random.nextBytes(32).byteVector32()).publicKey()): Pair<Transactions.CommitTx, FundingSigner.SigningContext.RemoteCommitment> {
            val (tx, _) = Commitments.makeRemoteTxs(params, commitParams, keys.remoteCommitmentKeys(params, point), index, groupKey, remoteFundingKey.publicKey(), input, Transactions.CommitmentFormat.SimpleTaprootChannels, spec)
            return tx to FundingSigner.SigningContext.RemoteCommitment(params, commitParams, index, point, spec)
        }
    }

    /** Runs [block] on the operator while every device's messages are pumped, until it returns. */
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

    private suspend fun operatorSigner(group: Group, deadline: Duration = 10.seconds): Pair<RemoteIcebergFundingSigner, GroupIcebergTransport> {
        val operator = group.all[0]
        val transport = GroupIcebergTransport.create(operator.db, operator.room, operator.publicKey, group.wallet, group.operatorTerm, scope)
        whilePumping(group) { transport.provision(GroupLightningCoSigner.POOL_WINDOW) }
        fixture.pump(group.all, GroupLightningEvents.CO_SIGNING)
        transport.provision(GroupLightningCoSigner.POOL_WINDOW)
        return RemoteIcebergFundingSigner(PublicKey.fromHex(group.wallet.statement.icebergKey), transport, deadline) to transport
    }

    /** Signs [tx] as the group and the stock counterparty would, and checks it spends the funding output. */
    private suspend fun groupSigns(group: Group, signer: RemoteIcebergFundingSigner, channel: Channel, tx: Transactions.CommitTx, context: FundingSigner.SigningContext): Either<Throwable, Transaction> {
        val remoteNonce = NonceGenerator.signingNonce(channel.remoteFundingKey.publicKey(), channel.groupKey, channel.fundingTxId)
        val groupSig = whilePumping(group) { signer.signWithFreshNonce(tx, channel.remoteFundingKey.publicKey(), mapOf(), channel.fundingTxId, remoteNonce.publicNonce, context) }
        if (groupSig is Either.Left) return Either.Left(groupSig.value)
        val ours = (groupSig as Either.Right).value
        val theirs = (tx.partialSign(channel.remoteFundingKey, channel.groupKey, mapOf(), remoteNonce, listOf(remoteNonce.publicNonce, ours.nonce)) as Either.Right<ChannelSpendSignature.PartialSignatureWithNonce>).value
        return tx.aggregateSigs(channel.groupKey, channel.remoteFundingKey.publicKey(), ours, theirs, mapOf())
    }

    /** Our own commitment [index], signed as the group would sign it to force-close, with the counterparty's half. */
    private suspend fun localSigns(group: Group, signer: RemoteIcebergFundingSigner, channel: Channel, index: Long, spec: CommitmentSpec): Either<Throwable, Transaction> {
        val (tx, _) = Commitments.makeLocalTxs(channel.params, channel.commitParams, channel.keys.localCommitmentKeys(channel.params, index), index, channel.groupKey, channel.remoteFundingKey.publicKey(), channel.input, Transactions.CommitmentFormat.SimpleTaprootChannels, spec)
        val context = FundingSigner.SigningContext.LocalCommitment(channel.params, channel.commitParams, index, spec)
        val id = FundingSigner.VerificationNonceId(channel.fundingTxId, channel.remoteFundingKey.publicKey(), index)
        val ourNonce = signer.verificationNonce(id)
        val remoteNonce = NonceGenerator.signingNonce(channel.remoteFundingKey.publicKey(), channel.groupKey, channel.fundingTxId)
        val groupSig = whilePumping(group) { signer.signWithVerificationNonce(tx, channel.remoteFundingKey.publicKey(), mapOf(), id, remoteNonce.publicNonce, context) }
        if (groupSig is Either.Left) return Either.Left(groupSig.value)
        val ours = (groupSig as Either.Right).value
        val theirs = (tx.partialSign(channel.remoteFundingKey, channel.groupKey, mapOf(), remoteNonce, listOf(remoteNonce.publicNonce, ourNonce)) as Either.Right<ChannelSpendSignature.PartialSignatureWithNonce>).value
        return tx.aggregateSigs(channel.groupKey, channel.remoteFundingKey.publicKey(), ours, theirs, mapOf())
    }

    @Test
    fun `our own commitment is signed only once the group authorises a force close, and never a revoked one`() = runBlocking<Unit> {
        val group = group()
        val channel = Channel(group, ours = 400_000_000, theirs = 600_000_000)
        val (signer, _) = operatorSigner(group, deadline = 3.seconds)
        for (index in 0L..2L) {
            val (tx, context) = channel.commitment(index, channel.spec)
            assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, tx, context))
        }
        val ours = channel.spec.copy(toLocal = 400_000_000.msat, toRemote = 600_000_000.msat)
        assertIs<IcebergSigningTimeout>(assertIs<Either.Left<Throwable>>(localSigns(group, signer, channel, 2, ours)).value, "nothing is authorised")

        val action = GroupLightningAuthorisationEvent.ChannelAction(GroupLightningAuthorisationEvent.FORCE_CLOSE, fixture.chain, 0, channel.params.channelId.toHex(), (kotlin.time.Clock.System.now() + 1.hours).epochSeconds)
        val template = GroupLightningAuthorisationEvent.template(action)
        fixture.sign(group.all, group.all[2], template.kind, template.tags, template.content)
        assertEquals(listOf(action), GroupLightningAuthorisations.channelActions(group.all[3].db, fixture.roomId, group.wallet.statement))

        assertIs<Either.Left<Throwable>>(localSigns(group, signer, channel, 0, ours), "commitment 0 was revoked long ago")
        val signed = assertIs<Either.Right<Transaction>>(localSigns(group, signer, channel, 2, ours)).value
        Transaction.correctlySpends(signed, mapOf(channel.input.outPoint to channel.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun `the group signs the counterparty's commitment, and it spends the funding output`() = runBlocking<Unit> {
        val group = group()
        val channel = Channel(group, ours = 400_000_000, theirs = 600_000_000)
        val (signer, _) = operatorSigner(group)
        val (tx, context) = channel.commitment(0, channel.spec)
        val signed = assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, tx, context)).value
        Transaction.correctlySpends(signed, mapOf(channel.input.outPoint to channel.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        // And a payment arriving: the next commitment, with more for the group, is signed as well.
        val (next, nextContext) = channel.commitment(1, channel.spec.copy(toLocal = 550_000_000.msat, toRemote = 450_000_000.msat))
        val signedNext = assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, next, nextContext)).value
        Transaction.correctlySpends(signedNext, mapOf(channel.input.outPoint to channel.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun `on mainnet no member signs for a channel over the rollout's cap`() = runBlocking<Unit> {
        val cap = GroupLightningRollout.channelCapFor(fixture.chain) ?: return@runBlocking
        val group = group()
        val (signer, _) = operatorSigner(group, deadline = 3.seconds)
        val atCap = Channel(group, ours = 400_000_000, theirs = cap.sat * 1000 - 400_000_000, capacity = cap)
        val (fits, fitsContext) = atCap.commitment(0, atCap.spec)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signer, atCap, fits, fitsContext), "a channel of exactly the cap opens")
        val over = Channel(group, ours = 400_000_000, theirs = cap.sat * 1000, capacity = cap + Satoshi(400_000))
        val (big, bigContext) = over.commitment(0, over.spec)
        assertIs<IcebergSigningTimeout>(assertIs<Either.Left<Throwable>>(groupSigns(group, signer, over, big, bigContext)).value, "a bigger one never gets a signature")
    }

    @Test
    fun `a transaction its context does not rebuild to is refused by every member`() = runBlocking<Unit> {
        val group = group()
        val channel = Channel(group, ours = 400_000_000, theirs = 600_000_000)
        val (signer, _) = operatorSigner(group, deadline = 3.seconds)
        // The context says the group keeps 400,000 sats; the transaction gives it 100,000.
        val (_, honestContext) = channel.commitment(0, channel.spec)
        val (lying, _) = channel.commitment(0, channel.spec.copy(toLocal = 900_000_000.msat, toRemote = 100_000_000.msat), honestContext.remotePerCommitmentPoint)
        val result = groupSigns(group, signer, channel, lying, honestContext)
        assertIs<IcebergSigningTimeout>(assertIs<Either.Left<Throwable>>(result).value)
        // And each member's device says so, once, to its own owner and nobody else.
        group.all.drop(1).forEach { d ->
            val refused = fixture.lines(d, ChatMessage.TYPE_LIGHTNING_REFUSED)
            assertEquals(1, refused.size, "device ${d.index}")
            assertEquals(
                "Your device refused to co-sign for the Lightning node: the commitment its context rebuilds to is not the one it asks to sign.",
                refused.single().content,
            )
            assertTrue(refused.single().isUserMessage)
        }
    }

    @Test
    fun `an outgoing payment the group did not authorise is refused`() = runBlocking<Unit> {
        val group = group()
        val channel = Channel(group, ours = 400_000_000, theirs = 600_000_000)
        val (signer, _) = operatorSigner(group, deadline = 3.seconds)
        val (first, firstContext) = channel.commitment(0, channel.spec)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, first, firstContext))
        // The operator offers an HTLC for a payment nobody approved.
        val onion = OnionRoutingPacket(0, ByteVector(ByteArray(33) { if (it == 0) 2 else 0 }), ByteVector.empty, ByteVector32.Zeroes)
        val htlc = IncomingHtlc(UpdateAddHtlc(channel.params.channelId, 0, 50_000_000.msat, Random.nextBytes(32).byteVector32(), CltvExpiry(800_000), onion))
        val (paying, payingContext) = channel.commitment(1, channel.spec.copy(htlcs = setOf(htlc), toRemote = 350_000_000.msat))
        assertIs<Either.Left<Throwable>>(groupSigns(group, signer, channel, paying, payingContext))
    }

    @Test
    fun `nobody answers a member who is not the operator`() = runBlocking<Unit> {
        val group = group()
        val channel = Channel(group, ours = 400_000_000, theirs = 600_000_000)
        val impostor = group.all[1]
        val transport = GroupIcebergTransport.create(impostor.db, impostor.room, impostor.publicKey, group.wallet, group.operatorTerm, scope)
        whilePumping(group) { transport.provision(GroupLightningCoSigner.POOL_WINDOW) }
        val signer = RemoteIcebergFundingSigner(PublicKey.fromHex(group.wallet.statement.icebergKey), transport, 3.seconds)
        val (tx, context) = channel.commitment(0, channel.spec)
        val result = runCatching { groupSigns(group, signer, channel, tx, context) }
        assertTrue(result.isFailure || result.getOrNull() is Either.Left, "a member who was not elected got a signature")
    }

    @Test
    fun `with too few members online, nothing is signed`() = runBlocking<Unit> {
        val group = group()
        val channel = Channel(group, ours = 400_000_000, theirs = 600_000_000)
        val (signer, _) = operatorSigner(group, deadline = 3.seconds)
        // n - t + 1 = 3 echoes are needed; with two devices offline only the operator and one other echo.
        val online = group.all.take(2)
        val (tx, context) = channel.commitment(0, channel.spec)
        val remoteNonce = NonceGenerator.signingNonce(channel.remoteFundingKey.publicKey(), channel.groupKey, channel.fundingTxId)
        val result = scope.async { signer.signWithFreshNonce(tx, channel.remoteFundingKey.publicKey(), mapOf(), channel.fundingTxId, remoteNonce.publicNonce, context) }
        withTimeout(20.seconds) {
            while (!result.isCompleted) {
                fixture.pump(online, GroupLightningEvents.CO_SIGNING)
                delay(50)
            }
        }
        assertIs<IcebergSigningTimeout>(assertIs<Either.Left<Throwable>>(result.await()).value)
        val key = GroupLightningCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
        online.forEach { d -> assertEquals(0, d.db.icebergCoSigningDao().sharesFor(key, "any").size) }
        online.forEach { d ->
            val signedLabels = d.db.marmotInnerEventDao().getByChatRoomAndKinds(fixture.roomId, listOf(GroupLightningEvents.SIGNATURE_SHARE)).filter { it.publicKey == d.publicKey }
            assertTrue(signedLabels.isEmpty(), "device ${d.index} signed without enough echoes")
        }
    }

    @Test
    fun `a label is never answered under a second message`() = runBlocking<Unit> {
        val group = group()
        val channel = Channel(group, ours = 400_000_000, theirs = 600_000_000)
        val (signer, transport) = operatorSigner(group)
        val (tx, context) = channel.commitment(0, channel.spec)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signer, channel, tx, context))
        // The operator reuses the label it was just answered under, for another commitment.
        val key = GroupLightningCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
        val answered = group.all[1].db.icebergCoSigningDao().ledgerFor(key, GroupLightningCoSigner.poolLabel(group.wallet.statement.icebergKey, transport.highestAssigned).toHex())
        assertTrue(answered?.signedAt != null)
        val reusing = object : fr.acinq.lightning.crypto.IcebergGroupTransport by transport {
            override fun nextFreshLabel(fundingTxId: TxId): ByteVector32 = GroupLightningCoSigner.poolLabel(group.wallet.statement.icebergKey, transport.highestAssigned)
        }
        val again = RemoteIcebergFundingSigner(PublicKey.fromHex(group.wallet.statement.icebergKey), reusing, 3.seconds)
        val (other, otherContext) = channel.commitment(1, channel.spec.copy(toLocal = 550_000_000.msat, toRemote = 450_000_000.msat))
        assertIs<Either.Left<Throwable>>(groupSigns(group, again, channel, other, otherContext))
        // Which every member that answered the first is told, once per label, in words.
        group.all.drop(1).forEach { d ->
            val alarm = fixture.lines(d, ChatMessage.TYPE_LIGHTNING_EQUIVOCATION)
            assertEquals(1, alarm.size, "device ${d.index}")
            assertEquals(group.all[0].publicKey, alarm.single().senderPublicKey, "attributed to the operator whose node asked")
        }
    }
}

/** The cases on regtest, the network every other four-device test runs on. */
class GroupLightningCoSigningJvmTest : GroupLightningCoSigningCases(Chain.Regtest)
