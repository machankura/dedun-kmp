package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.cancelAndWait
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.wallet.lightning.GroupSwapInState
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Features
import fr.acinq.lightning.blockchain.electrum.WalletState
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
import fr.acinq.lightning.crypto.RemoteIcebergSwapInSigner
import fr.acinq.lightning.crypto.SwapInOnChainKeys
import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.transactions.SwapInProtocol
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Co-signers splicing a depositor's swap-in coin into the group's channel, by rule and without an
 * authorisation (`docs/group-lightning-swap-in.md`, Phase 4, W1-W8).
 *
 * The group is the fixture's four devices on mainnet, where the swap-in protocol has an LSP; the LSP here
 * is a stand-in whose swap-in key the test holds, so its half of each signature can be made too, and every
 * transaction is judged by bitcoin's script interpreter.
 */
class GroupLightningSwapInCoSigningJvmTest {
    private val fixture = GroupLightningFixture(chain = Chain.Mainnet)
    private val lspKey = PrivateKey(Random.nextBytes(32).byteVector32())
    private val lspSwapInMaster = DeterministicWallet.generate(ByteVector(Random.nextBytes(32)))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val defaultXpub = GroupSwapInDerivation.lspSwapInXpub

    init {
        GroupSwapInDerivation.lspSwapInXpub = { lspSwapInMaster.extendedPublicKey.encode(DeterministicWallet.xpub) }
        val base = fixture.dispatch
        fixture.dispatch = { to, event ->
            base(to, event)
            if (event.kind in GroupLightningEvents.CO_SIGNING) GroupLightningCoSigner.process(to.db, to.room, event, to.publicKey)
        }
    }

    @AfterTest
    fun close() {
        GroupSwapInDerivation.lspSwapInXpub = defaultXpub
        scope.cancelAndWait()
        fixture.close()
    }

    private fun key() = PrivateKey(Random.nextBytes(32).byteVector32())

    private inner class Group(val all: List<GroupLightningFixture.Device>, val wallet: GroupLightningWalletManager.Wallet, val term: Long) {
        val depositor get() = all[1]
        val groupKey = PublicKey.fromHex(wallet.statement.icebergKey)
        val keys = GroupSwapInDerivation.keys(fixture.thresholdPublicKey, fixture.chain, 0, wallet.statement.icebergKey, wallet.statement.nodeId)
        /** The LSP's swap-in private key for the group's node, as the LSP would derive it. */
        val serverKey: PrivateKey = lspSwapInMaster.derivePrivateKey(SwapInOnChainKeys.perUserPath(PublicKey.fromHex(wallet.statement.nodeId))).privateKey
    }

    private suspend fun group(): Group {
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lspKey.publicKey().toString()).id)
        fixture.signSession(all, all[0], GroupLightningOperatorManager.nominateSelf(all[0].db, all[0].room, all[0].publicKey, fixture.chain).id)
        fixture.openDeposits(all, all[1])
        val wallet = GroupLightningWalletManager.walletFor(all[0].db, fixture.roomId, fixture.chain)!!
        return Group(all, wallet, GroupLightningOperatorManager.current(all[0].db, fixture.roomId, fixture.chain)!!.term).also {
            assertEquals(it.serverKey.publicKey(), it.keys.serverKey, "the stand-in LSP's key is the one every device derives")
        }
    }

    /** A channel of 300,000 sats with the LSP, 100,000 of them the group's. */
    private inner class Channel(group: Group) {
        val keyManager = GroupLightningCoSigner.keyManagerOf(ByteVector(group.wallet.seed!!.seed), fixture.chain)
        val groupKey = group.groupKey
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

        fun commitment(index: Long, spec: CommitmentSpec, funding: Transactions.InputInfo = input, remote: PublicKey = remoteFundingKey.publicKey(), fundingTxIndex: Long = 0): Pair<Transactions.CommitTx, FundingSigner.SigningContext.RemoteCommitment> {
            val point = key().publicKey()
            val (tx, _) = Commitments.makeRemoteTxs(params, commitParams, keys.remoteCommitmentKeys(params, point), index, groupKey, remote, funding, Transactions.CommitmentFormat.SimpleTaprootChannels, spec)
            return tx to FundingSigner.SigningContext.RemoteCommitment(params, commitParams, index, point, spec, fundingTxIndex)
        }
    }

    /**
     * A splice-in of one coin on [protocol]'s address: the funding output and the coin in, the new funding
     * output alone out, our share of the mining fee [fee] taken from the coin. [credit] is what the first
     * commitment on the new output gives the group; [extraOutput] pays somebody besides.
     */
    private inner class SwapIn(
        val channel: Channel,
        val protocol: SwapInProtocol,
        val coinAmount: Satoshi = 50_000.sat,
        val fee: Satoshi = 300.sat,
        credit: Satoshi? = null,
        extraOutput: TxOut? = null,
    ) {
        val coinTx = Transaction(2, listOf(TxIn(OutPoint(TxId(Random.nextBytes(32).byteVector32()), 0), 0)), listOf(TxOut(coinAmount, protocol.pubkeyScript)), 0)
        val coin = OutPoint(coinTx, 0)
        val local: Satoshi = coinAmount - fee
        val credit: Satoshi = credit ?: local
        val newRemoteKey = key()
        val newAmount = channel.input.txOut.amount + local
        private val newScript = Transactions.makeFundingInputInfo(TxId(ByteVector32.Zeroes), 0, newAmount, channel.groupKey, newRemoteKey.publicKey(), Transactions.CommitmentFormat.SimpleTaprootChannels).txOut.publicKeyScript
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(channel.input.outPoint, 0xfffffffdL), TxIn(coin, 0xfffffffdL)),
            txOut = listOfNotNull(TxOut(newAmount, newScript), extraOutput),
            lockTime = 0,
        )
        val newInput = Transactions.InputInfo(OutPoint(tx.txid, 0), tx.txOut[0])
        val spentOutputs = listOf(channel.input.txOut, coinTx.txOut[0])

        fun firstCommitment(index: Long): Pair<Transactions.CommitTx, FundingSigner.SigningContext.RemoteCommitment> {
            val ours = channel.spec.toRemote + credit.toMilliSatoshi()
            return channel.commitment(index, channel.spec.copy(toRemote = ours, toLocal = newAmount.toMilliSatoshi() - ours), funding = newInput, remote = newRemoteKey.publicKey(), fundingTxIndex = 1)
        }

        fun context(remoteContribution: Satoshi = 0.sat) = FundingSigner.SigningContext.Splice(
            channelParams = channel.params, parentFundingTxIndex = 0, parentFunding = channel.input, fundingTxIndex = 1, fundingOutputIndex = 0,
            remoteFundingPubKey = newRemoteKey.publicKey(), localContribution = local, remoteContribution = remoteContribution, purchase = null,
            feerate = FeeratePerKw(500.sat), spentOutputs = mapOf(channel.input.outPoint to channel.input.txOut, coin to coinTx.txOut[0]),
        )

        /** What the depositor's swap-in wallet holds: the coin on [index] of [member]'s branch. */
        fun walletState(group: Group, member: GroupLightningFixture.Device, index: Int): GroupSwapInState {
            val branch = DepositBranch.of(fixture.roomId, member.publicKey)
            val utxo = WalletState.Utxo(coinTx.txid, 0, 900_000, coinTx, WalletState.AddressMeta.Derived(index))
            val address = group.keys.address(branch, index.toLong()).address
            val state = WalletState(mapOf(address to WalletState.AddressState(WalletState.AddressMeta.Derived(index), alreadyUsed = true, utxos = listOf(utxo))))
            return GroupSwapInState(group.keys, 900_010, mapOf(member.publicKey.lowercase() to GroupSwapInState.Depositor(member.publicKey.lowercase(), branch, 0, state)))
        }
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

    private inner class Signers(val funding: RemoteIcebergFundingSigner, val swapIn: RemoteIcebergSwapInSigner)

    private suspend fun signers(group: Group, swapIns: MutableStateFlow<GroupSwapInState?>, deadline: Duration = 3.seconds): Signers {
        val operator = group.all[0]
        val transport = GroupIcebergTransport.create(operator.db, operator.room, operator.publicKey, group.wallet, group.term, scope, swapIns)
        whilePumping(group) { transport.provision(GroupLightningCoSigner.POOL_WINDOW) }
        fixture.pump(group.all, GroupLightningEvents.CO_SIGNING)
        transport.provision(GroupLightningCoSigner.POOL_WINDOW)
        return Signers(RemoteIcebergFundingSigner(group.groupKey, transport, deadline), RemoteIcebergSwapInSigner(group.groupKey, transport, deadline))
    }

    private suspend fun groupSigns(group: Group, signer: RemoteIcebergFundingSigner, channel: Channel, tx: Transactions.CommitTx, context: FundingSigner.SigningContext, remote: PrivateKey = channel.remoteFundingKey): Either<Throwable, Transaction> {
        val fundingTxId = tx.input.outPoint.txid
        val remoteNonce = NonceGenerator.signingNonce(remote.publicKey(), channel.groupKey, fundingTxId)
        val groupSig = whilePumping(group) { signer.signWithFreshNonce(tx, remote.publicKey(), mapOf(), fundingTxId, remoteNonce.publicNonce, context) }
        if (groupSig is Either.Left) return Either.Left(groupSig.value)
        val ours = (groupSig as Either.Right).value
        val theirs = (tx.partialSign(remote, channel.groupKey, mapOf(), remoteNonce, listOf(remoteNonce.publicNonce, ours.nonce)) as Either.Right<ChannelSpendSignature.PartialSignatureWithNonce>).value
        return tx.aggregateSigs(channel.groupKey, remote.publicKey(), ours, theirs, mapOf())
    }

    /** The shared input, as `groupSplices` in the liquidity test signs it, returning the transaction with its witness. */
    private suspend fun groupSplicesShared(group: Group, signer: RemoteIcebergFundingSigner, swapIn: SwapIn, context: FundingSigner.SigningContext): Either<Throwable, Transaction> {
        val channel = swapIn.channel
        val extra = mapOf(swapIn.coin to swapIn.coinTx.txOut[0])
        val splice = Transactions.SpliceTx(channel.input, swapIn.tx)
        val session = signer.publishedNonceSession(channel.fundingTxId, channel.remoteFundingKey.publicKey())
        val remoteNonce = NonceGenerator.signingNonce(channel.remoteFundingKey.publicKey(), channel.groupKey, channel.fundingTxId)
        val groupSig = whilePumping(group) { signer.signWithPublishedNonce(splice, channel.remoteFundingKey.publicKey(), extra, session, remoteNonce.publicNonce, context) }
        if (groupSig is Either.Left) return Either.Left(groupSig.value)
        val ours = (groupSig as Either.Right).value
        val theirs = (splice.partialSign(channel.remoteFundingKey, channel.groupKey, extra, remoteNonce, listOf(remoteNonce.publicNonce, ours.nonce)) as Either.Right<ChannelSpendSignature.PartialSignatureWithNonce>).value
        return splice.aggregateSigs(channel.groupKey, channel.remoteFundingKey.publicKey(), ours, theirs, extra)
    }

    /** The swap-in input: the group's half through its signer, the LSP's own, combined into the input's witness. */
    private suspend fun groupSwapsIn(group: Group, signer: RemoteIcebergSwapInSigner, swapIn: SwapIn, tx: Transaction): Either<Throwable, Transaction> {
        val session = signer.nonceSession(swapIn.coin, swapIn.protocol)
        val (serverSecret, serverNonce) = Musig2.generateNonce(Random.nextBytes(32).byteVector32(), Either.Left(group.serverKey), listOf(group.groupKey, group.serverKey.publicKey()), null, null)
        val ours = whilePumping(group) { signer.sign(tx, 1, swapIn.spentOutputs, swapIn.protocol, session, serverNonce) }
        if (ours is Either.Left) return Either.Left(ours.value)
        val theirs = (swapIn.protocol.signSwapInputServer(tx, 1, swapIn.spentOutputs, group.serverKey, serverSecret, session.publicNonce, serverNonce) as Either.Right).value
        val witness = (swapIn.protocol.witness(tx, 1, swapIn.spentOutputs, session.publicNonce, serverNonce, (ours as Either.Right).value, theirs) as Either.Right).value
        return Either.Right(tx.updateWitness(1, witness))
    }

    /** A group with a channel it has signed the first commitment of, and a depositor with a coin on their address 0. */
    private suspend fun ready(swapIn: (Group, Channel) -> SwapIn = { g, c -> SwapIn(c, g.keys.address(DepositBranch.of(fixture.roomId, g.depositor.publicKey), 0).protocol) }): Triple<Group, SwapIn, Signers> {
        val group = group()
        val channel = Channel(group)
        val made = swapIn(group, channel)
        val signers = signers(group, MutableStateFlow(made.walletState(group, group.depositor, 0)))
        val (tx, context) = channel.commitment(0, channel.spec)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signers.funding, channel, tx, context))
        return Triple(group, made, signers)
    }

    private fun assertRefused(result: Either<Throwable, Transaction>) = assertIs<IcebergSigningTimeout>(assertIs<Either.Left<Throwable>>(result).value)

    private suspend fun refusals(group: Group): List<String> = group.all.drop(1).flatMap { fixture.lines(it, ChatMessage.TYPE_LIGHTNING_REFUSED) }.map { it.content }

    @Test
    fun `the group splices a depositor's coin in, signing the shared input and the swap-in input, and bitcoin accepts it`() = runBlocking<Unit> {
        val (group, swapIn, signers) = ready()
        val (first, firstContext) = swapIn.firstCommitment(0)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signers.funding, swapIn.channel, first, firstContext, swapIn.newRemoteKey), "the first commitment on the new output, without an authorisation")
        val shared = assertIs<Either.Right<Transaction>>(groupSplicesShared(group, signers.funding, swapIn, swapIn.context()), "the shared input").value
        val signed = assertIs<Either.Right<Transaction>>(groupSwapsIn(group, signers.swapIn, swapIn, shared), "the swap-in input").value
        Transaction.correctlySpends(signed, mapOf(swapIn.channel.input.outPoint to swapIn.channel.input.txOut, swapIn.coin to swapIn.coinTx.txOut[0]), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        val key = GroupLightningCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
        group.all.forEach { d ->
            val ledgers = d.db.icebergCoSigningDao().channelLedgers(key, swapIn.channel.params.channelId.toHex())
            assertEquals(listOf(0L, 1L), ledgers.map { it.fundingTxIndex }, "device ${d.index}")
            assertEquals(GroupLightningCoSigner.SWAP_IN_OPENING, ledgers[1].purchaseId, "device ${d.index} opened the new output as a swap-in's")
        }
        assertTrue(refusals(group).isEmpty(), refusals(group).toString())
    }

    @Test
    fun `a swap-in that costs the group more than its fee cap is refused`() = runBlocking<Unit> {
        val (group, swapIn, signers) = ready { g, c -> SwapIn(c, g.keys.address(DepositBranch.of(fixture.roomId, g.depositor.publicKey), 0).protocol, fee = Satoshi(g.wallet.statement.policy.maxLiquidityFeeSat + 1)) }
        val (first, firstContext) = swapIn.firstCommitment(0)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signers.funding, swapIn.channel, first, firstContext, swapIn.newRemoteKey))
        assertRefused(groupSplicesShared(group, signers.funding, swapIn, swapIn.context()))
        assertTrue(refusals(group).any { "over the" in it && "agreed to" in it }, refusals(group).toString())
    }

    @Test
    fun `a coin on an address that is no depositor's is never spliced in`() = runBlocking<Unit> {
        // Device 2 has no depositor statement: its branch's address is the group's in form, and nobody's.
        val (group, swapIn, signers) = ready { g, c -> SwapIn(c, g.keys.address(DepositBranch.of(fixture.roomId, g.all[2].publicKey), 0).protocol) }
        val (first, firstContext) = swapIn.firstCommitment(0)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signers.funding, swapIn.channel, first, firstContext, swapIn.newRemoteKey))
        assertRefused(groupSplicesShared(group, signers.funding, swapIn, swapIn.context()))
    }

    @Test
    fun `a swap-in that pays somebody, or that the LSP adds to, is refused`() = runBlocking<Unit> {
        val (group, swapIn, signers) = ready { g, c ->
            SwapIn(c, g.keys.address(DepositBranch.of(fixture.roomId, g.depositor.publicKey), 0).protocol, extraOutput = TxOut(1_000.sat, Script.pay2tr(key().publicKey().xOnly(), Crypto.TaprootTweak.KeyPathTweak)))
        }
        val (first, firstContext) = swapIn.firstCommitment(0)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signers.funding, swapIn.channel, first, firstContext, swapIn.newRemoteKey))
        assertRefused(groupSplicesShared(group, signers.funding, swapIn, swapIn.context()))
        assertRefused(groupSplicesShared(group, signers.funding, swapIn, swapIn.context(remoteContribution = 1_000.sat)))
        assertTrue(refusals(group).any { "pays out" in it }, refusals(group).toString())
    }

    @Test
    fun `a first commitment that credits the group other than the coins add is caught at the shared input`() = runBlocking<Unit> {
        val (group, swapIn, signers) = ready { g, c -> SwapIn(c, g.keys.address(DepositBranch.of(fixture.roomId, g.depositor.publicKey), 0).protocol, credit = 10_000.sat) }
        val (first, firstContext) = swapIn.firstCommitment(0)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signers.funding, swapIn.channel, first, firstContext, swapIn.newRemoteKey), "more for the group is never refused by itself")
        assertRefused(groupSplicesShared(group, signers.funding, swapIn, swapIn.context()))
        assertTrue(refusals(group).any { "its coins add" in it }, refusals(group).toString())
    }

    @Test
    fun `the swap-in input is never signed for a splice whose shared input fails its checks`() = runBlocking<Unit> {
        val (group, swapIn, signers) = ready { g, c -> SwapIn(c, g.keys.address(DepositBranch.of(fixture.roomId, g.depositor.publicKey), 0).protocol, credit = 10_000.sat) }
        val (first, firstContext) = swapIn.firstCommitment(0)
        assertIs<Either.Right<Transaction>>(groupSigns(group, signers.funding, swapIn.channel, first, firstContext, swapIn.newRemoteKey))
        assertRefused(groupSplicesShared(group, signers.funding, swapIn, swapIn.context()))
        // Asked for the coin anyway, under the same splice: the same checks run again, and fail again.
        assertRefused(groupSwapsIn(group, signers.swapIn, swapIn, swapIn.tx))
    }
}
