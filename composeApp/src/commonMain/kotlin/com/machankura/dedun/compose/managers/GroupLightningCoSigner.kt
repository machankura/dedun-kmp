package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.IcebergChannelLedger
import com.machankura.dedun.compose.database.model.IcebergEchoRow
import com.machankura.dedun.compose.database.model.IcebergLabelLedger
import com.machankura.dedun.compose.database.model.IcebergNonceRow
import com.machankura.dedun.compose.database.model.IcebergShareRow
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.wallet.lightning.CoSignerPolicy
import com.machankura.dedun.compose.wallet.lightning.CoSigningWire
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.iceberg.NonceContribution
import fr.acinq.bitcoin.crypto.musig2.AggregatedNonce
import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.phoenixd.conf.LSP
import kotlin.time.Clock
import kotlinx.serialization.Serializable

/**
 * The co-signer every Iceberg participant runs: it answers the operator's node for the group, without a
 * person in the loop, under the policy the group signed (`docs/group-lightning-wallet.md`, Phase 9).
 *
 * Four kinds reach it. A **nonce batch** is filed: round one is published ahead of time, for a pool of
 * labels nothing has been assigned to yet. A **signing request** is checked -- the sender is the
 * current operator, the label is one from the pool this device has never answered, the transaction is
 * the one its context rebuilds to, and what it does to the group's money is something the group agreed
 * to -- and, if it passes, *echoed*: the co-signer tells the room the hash it would sign under that
 * label, and will never sign anything else under it. An **echo** is counted; once `n - t + 1`
 * participants have echoed the same hash for a label, this device signs, and once a member sees two
 * hashes echoed for one label it signs neither. A **signature share** is filed for the operator.
 *
 * Why `n - t + 1`: any two sets of that many share `n - 2t + 2` members, which is at least `t` when
 * `n >= 3t - 2`, and so contains an honest member who echoed only one hash. So no two different
 * messages can ever both be signed under one label (decision D7).
 */
object GroupLightningCoSigner {
    private val logger = Logger.withTag("GroupLightningCoSigner")

    /** How many round-one contributions a co-signer keeps published ahead of the operator's latest need. */
    const val POOL_WINDOW: Long = 64

    /** The most a single provision request makes a co-signer publish. */
    const val MAX_BATCH: Long = 128

    /**
     * A swap-in xpub for [LocalKeyManager], which wants one; a group node never swaps in, so any valid one
     * does -- for the network. lightning-kmp checks the prefix: a tpub on mainnet throws, and so would the
     * first key manager every co-signer and the operator's node built. So mainnet takes the mainnet LSP's
     * published one, and every other network this tpub. Nothing derives from either: the node id and
     * every channel key come from the seed.
     */
    internal const val UNUSED_SWAP_IN_TPUB = "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og"

    private fun unusedSwapInXpub(chain: Chain): String = if (chain.isMainnet()) LSP.from(Chain.Mainnet).swapInXpub else UNUSED_SWAP_IN_TPUB

    fun walletKey(chatRoomId: String, chain: Chain, wallet: Long): String = "$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}:$wallet"

    /**
     * The label of pool counter [counter] for the group key [icebergKey]: every member computes the same.
     * The tag stays `bennu/` in Dedun -- a co-signer that hashed under another would label every nonce
     * differently from the rest of its group -- and `DomainTagsPinnedTest` pins it.
     */
    fun poolLabel(icebergKey: HexKey, counter: Long): ByteVector32 =
        Crypto.taggedHash(ByteVector(icebergKey).toByteArray() + Pack.writeInt64BE(counter), "bennu/iceberg/pool/v1")

    /** The key manager a group node runs, and a co-signer rebuilds commitments with. */
    fun keyManagerOf(seed: ByteVector, chain: Chain): LocalKeyManager = LocalKeyManager(seed, chain, unusedSwapInXpub(chain))

    /**
     * What a co-signer stores of a funding output's ledger, in JSON. [consumed] is what its last
     * commitment spent of each authorisation, by payment hash: while a splice is unconfirmed the same
     * payment settles on both outputs' commitments at one number, and spends its budget once.
     */
    @Serializable
    private data class StoredLedger(
        val commitIndex: Long,
        val commitTxId: String,
        val oursMsat: Long,
        val offered: Map<String, List<String>>,
        val incoming: Map<String, Long>,
        val floorMsat: Long,
        val consumed: Map<String, Long> = emptyMap(),
    )

    internal fun store(l: CoSignerPolicy.Ledger, consumed: Map<String, Long> = emptyMap()) = CommonJson.encodeToString(
        StoredLedger.serializer(),
        StoredLedger(l.commitIndex, l.commitTxId, l.oursMsat, l.offered.entries.associate { (id, h) -> id.toString() to listOf(h.paymentHash, h.amountMsat.toString()) }, l.incoming.entries.associate { (id, a) -> id.toString() to a }, l.floorMsat, consumed),
    )

    private fun consumedBy(json: String): Map<String, Long> = CommonJson.decodeFromString(StoredLedger.serializer(), json).consumed

    /**
     * What the other funding outputs' commitments at [commitIndex] already spent of each budget: the
     * most any of them spent, since each settles the same payments.
     */
    private fun spentBySiblings(siblings: List<IcebergChannelLedger>, commitIndex: Long): Map<String, Long> =
        siblings.filter { load(it.ledger).commitIndex == commitIndex }.map { consumedBy(it.ledger) }
            .fold(emptyMap()) { acc, m -> (acc.keys + m.keys).associateWith { maxOf(acc[it] ?: 0, m[it] ?: 0) } }

    private fun load(json: String): CoSignerPolicy.Ledger = CommonJson.decodeFromString(StoredLedger.serializer(), json).let { s ->
        CoSignerPolicy.Ledger(s.commitIndex, s.commitTxId, s.oursMsat, s.offered.entries.associate { (id, v) -> id.toLong() to CoSignerPolicy.OfferedHtlc(v[0], v[1].toLong()) }, s.incoming.entries.associate { (id, a) -> id.toLong() to a }, s.floorMsat)
    }

    /**
     * What the last counterparty commitment this device signed for [channelId] left the group, HTLCs it
     * offered excluded, or null when this device has signed none for that channel.
     */
    suspend fun signedOursMsat(database: DedunDatabase, walletKey: String, channelId: String): Long? =
        database.icebergCoSigningDao().channelLedger(walletKey, channelId)?.let { load(it.ledger).oursMsat }

    /** The wallet a message for [walletKey] is about, if this device believes it. */
    internal suspend fun walletOf(database: DedunDatabase, chatRoomId: String, walletKey: String): GroupLightningWalletManager.Wallet? {
        val parts = walletKey.split(":")
        if (parts.size != 3 || parts[0] != chatRoomId) return null
        val chain = GroupWalletStatementEvent.chainNamed(parts[1]) ?: return null
        return GroupLightningWalletManager.walletFor(database, chatRoomId, chain, parts[2].toLongOrNull() ?: return null)
    }

    private fun indexOf(wallet: GroupLightningWalletManager.Wallet, participant: HexKey): Int? =
        wallet.statement.participants.indexOf(participant.lowercase()).takeIf { it >= 0 }?.plus(1)

    /** Publishes this device's round-one contributions for every pool counter up to [upTo] it has not published yet. */
    suspend fun provision(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, upTo: Long) {
        val share = wallet.ceremony?.let(IcebergRitualManager::shareOf) ?: return
        val key = walletKey(localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)
        val me = userPublicKey.lowercase()
        val dao = database.icebergCoSigningDao()
        val from = (dao.highestNonce(key, me) ?: -1L) + 1
        val to = minOf(upTo, from + MAX_BATCH - 1)
        if (to < from) return
        val contributions = (from..to).map { counter ->
            Iceberg.generateNonce(share, null, poolLabel(wallet.statement.icebergKey, counter)).toByteArray().toHex()
                .also { dao.insertNonce(IcebergNonceRow(key, counter, me, it)) }
        }
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.NONCE_BATCH, CommonJson.encodeToString(CoSigningWire.NonceBatch.serializer(), CoSigningWire.NonceBatch(key, from, contributions)))
    }

    /** Routes one inbound co-signing message. [nostrPrivateKey] is not needed to sign: the share is. */
    suspend fun process(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event, userPublicKey: HexKey) {
        try {
            when (innerEvent.kind) {
                GroupLightningEvents.NONCE_BATCH -> fileNonces(database, localChatRoom, innerEvent)
                GroupLightningEvents.SIGNING_REQUEST -> handleRequest(database, localChatRoom, innerEvent, userPublicKey)
                GroupLightningEvents.ECHO -> fileEcho(database, localChatRoom, innerEvent, userPublicKey)
                GroupLightningEvents.SIGNATURE_SHARE -> fileShare(database, localChatRoom, innerEvent)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.e("Could not process co-signing kind ${innerEvent.kind}", e)
        }
    }

    private suspend fun fileNonces(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event) {
        val batch = CommonJson.decodeFromString(CoSigningWire.NonceBatch.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, batch.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        if (batch.contributions.size > MAX_BATCH) return
        batch.contributions.forEachIndexed { i, c ->
            database.icebergCoSigningDao().insertNonce(IcebergNonceRow(batch.wallet, batch.from + i, event.pubKey.lowercase(), c))
        }
    }

    private suspend fun fileShare(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event) {
        val message = CommonJson.decodeFromString(CoSigningWire.SignatureShareMessage.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, message.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        database.icebergCoSigningDao().insertShare(IcebergShareRow(message.wallet, message.requestId, event.pubKey.lowercase(), message.share))
    }

    /** The provision request: a signing request whose kind is `PROVISION`, asking for contributions up to its counter. */
    const val PROVISION = "PROVISION"

    /** The context type of a splice's shared input. */
    const val SPLICE = "splice"

    private suspend fun handleRequest(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val request = CommonJson.decodeFromString(CoSigningWire.SigningRequest.serializer(), event.content)
        val roomId = localChatRoom.chatRoom.id
        val wallet = walletOf(database, roomId, request.wallet) ?: return
        if (wallet.ceremony?.share == null || userPublicKey.lowercase() !in wallet.statement.participants) return

        // Check 1: the current operator, in the current term.
        val operator = GroupLightningOperatorManager.current(database, roomId, wallet.statement.chain, wallet.statement.wallet)
        if (operator?.publicKey?.lowercase() != event.pubKey.lowercase() || operator.term != request.term) {
            logger.w("Refusing request ${request.requestId}: ${event.pubKey.take(8)} is not the operator of term ${operator?.term}")
            return
        }
        if (request.kind == PROVISION) {
            provision(database, localChatRoom, userPublicKey, wallet, request.counter)
            return
        }

        val refusal = check(database, roomId, wallet, request)
        if (refusal != null) {
            logger.w("Refusing request ${request.requestId}: $refusal")
            // Its owner is never shown a commitment, so a refusal is the one thing about
            // co-signing a person sees -- and on this device only, which is whose check it was.
            GroupLightningLines.writeFact(
                database, roomId, ChatMessage.TYPE_LIGHTNING_REFUSED, request.requestId, userPublicKey,
                "Your device refused to co-sign for the Lightning node: $refusal.", Clock.System.now(),
            )
            return
        }
        val message = messageOf(request) ?: return

        // Echo, and commit to this hash under this label for good. A label already answered under any
        // other message is never answered again.
        val dao = database.icebergCoSigningDao()
        val existing = dao.ledgerFor(request.wallet, request.label)
        if (existing != null && existing.messageHash != message.toHex()) {
            logger.w("Refusing request ${request.requestId}: label ${request.label.take(16)} was already echoed for another message")
            raiseEquivocation(database, roomId, request.label, event.pubKey)
            return
        }
        commitLedger(database, roomId, wallet, request)
        if (existing == null) {
            dao.insertLedger(IcebergLabelLedger(request.wallet, request.label, request.requestId, message.toHex()))
            dao.insertEcho(IcebergEchoRow(request.wallet, request.label, userPublicKey.lowercase(), message.toHex()))
            GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.ECHO, CommonJson.encodeToString(CoSigningWire.Echo.serializer(), CoSigningWire.Echo(request.wallet, request.requestId, request.label, message.toHex())))
        }
        maybeSign(database, localChatRoom, wallet, request.wallet, request.label, userPublicKey)
    }

    private suspend fun fileEcho(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val echo = CommonJson.decodeFromString(CoSigningWire.Echo.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, echo.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        database.icebergCoSigningDao().insertEcho(IcebergEchoRow(echo.wallet, echo.label, event.pubKey.lowercase(), echo.messageHash))
        maybeSign(database, localChatRoom, wallet, echo.wallet, echo.label, userPublicKey)
    }

    /**
     * The alarm the echo rule exists to raise: the operator's node asked for two messages under one
     * label. Said once per label, in the error tint, attributed to the operator whose node asked.
     */
    private suspend fun raiseEquivocation(database: DedunDatabase, chatRoomId: String, label: String, operator: HexKey) =
        GroupLightningLines.writeFact(
            database, chatRoomId, ChatMessage.TYPE_LIGHTNING_EQUIVOCATION, label, operator,
            "The operator's node asked the group to sign two different things under one label. Nobody will sign either.",
            Clock.System.now(),
        )

    /** Signs under [label] once enough participants echoed this device's hash and nobody echoed another. */
    private suspend fun maybeSign(database: DedunDatabase, localChatRoom: LocalChatRoom, wallet: GroupLightningWalletManager.Wallet, key: String, label: String, userPublicKey: HexKey) {
        val dao = database.icebergCoSigningDao()
        val mine = dao.ledgerFor(key, label) ?: return
        if (mine.signedAt != null) return
        val echoes = dao.echoesFor(key, label)
        if (echoes.any { it.messageHash != mine.messageHash }) {
            logger.e("Label ${label.take(16)} was echoed for two different messages: the operator is equivocating, and nobody signs it")
            val operator = GroupLightningOperatorManager.current(database, localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)
            raiseEquivocation(database, localChatRoom.chatRoom.id, label, operator?.publicKey ?: userPublicKey)
            return
        }
        val n = wallet.statement.participants.size
        val t = wallet.statement.threshold
        if (echoes.map { it.participant }.toSet().size < n - t + 1) return

        val request = requestFor(database, localChatRoom.chatRoom.id, mine.requestId) ?: return
        val share = wallet.ceremony?.let(IcebergRitualManager::shareOf) ?: return
        val groupKey = PublicKey.fromHex(wallet.statement.icebergKey)
        val remoteKey = PublicKey.fromHex(request.remoteFundingPubKey)
        val sortedKeys = Scripts.sort(listOf(groupKey, remoteKey))
        val tweak = Scripts.Taproot.musig2Aggregate(groupKey, remoteKey).tweak(Crypto.TaprootTweak.KeyPathTweak)
        val cache = when (val c = KeyAggCache.create(sortedKeys).second.tweak(tweak, isXonly = true)) {
            is Either.Right -> c.value.first
            is Either.Left -> return
        }
        val signatureShare = Iceberg.partialSign(
            share, null, ByteVector32(label), request.contributions.map { NonceContribution(it) }, groupKey, cache,
            ByteVector32(mine.messageHash), AggregatedNonce(ByteVector(request.cosignerAggregatedNonce)),
        )
        when (signatureShare) {
            is Either.Left -> logger.e("Could not sign under ${label.take(16)}", signatureShare.value)
            is Either.Right -> {
                dao.upsertLedger(mine.copy(signedAt = Clock.System.now()))
                GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.SIGNATURE_SHARE, CommonJson.encodeToString(CoSigningWire.SignatureShareMessage.serializer(), CoSigningWire.SignatureShareMessage(key, mine.requestId, label, signatureShare.value.toByteArray().toHex())))
                dao.insertShare(IcebergShareRow(key, mine.requestId, userPublicKey.lowercase(), signatureShare.value.toByteArray().toHex()))
            }
        }
    }

    private suspend fun requestFor(database: DedunDatabase, chatRoomId: String, requestId: String): CoSigningWire.SigningRequest? =
        database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GroupLightningEvents.SIGNING_REQUEST))
            .mapNotNull { runCatching { CommonJson.decodeFromString(CoSigningWire.SigningRequest.serializer(), it.content) }.getOrNull() }
            .firstOrNull { it.requestId == requestId }

    /**
     * The hash the request's transaction signs: recomputed, never read off the request. It is the input
     * spending [CoSigningWire.SigningRequest.input], over every output the transaction spends, which BIP341
     * commits the signature to. A request that misstates what the other inputs spend gets a signature
     * nobody can mine, never one that moves money; one that omits them gets nothing, since there is no
     * hash to compute. Null then.
     */
    fun messageOf(request: CoSigningWire.SigningRequest): ByteVector32? {
        val tx = CoSigningWire.tx(request.tx)
        val input = CoSigningWire.inputInfo(request.input)
        val index = tx.txIn.indexOfFirst { it.outPoint == input.outPoint }.takeIf { it >= 0 } ?: return null
        val spent = CoSigningWire.spentOutputs(tx, input, request.extraUtxos) ?: return null
        return tx.hashForSigningTaprootKeyPath(index, spent, SigHash.SIGHASH_DEFAULT)
    }

    /**
     * Every check before an echo, as the plan numbers them. Null when the request may be signed;
     * otherwise why not.
     */
    private suspend fun check(database: DedunDatabase, roomId: String, wallet: GroupLightningWalletManager.Wallet, request: CoSigningWire.SigningRequest): String? {
        val statement = wallet.statement
        val dao = database.icebergCoSigningDao()

        // Check 2: a pool label, for the counter it claims, and its contributions are exactly what the
        // participants they name published for it.
        if (poolLabel(statement.icebergKey, request.counter).toHex() != request.label) return "the label is not pool counter ${request.counter}"
        val published = dao.noncesFor(request.wallet, request.counter).associate { it.contribution to it.participant }
        if (request.contributions.size < 2 * statement.threshold - 1) return "round one has fewer than 2t-1 contributions"
        val from = request.contributions.map { published[it] ?: return "a contribution was not published by the participant it names" }
        if (from.toSet().size != from.size) return "a participant's contribution appears twice"
        request.contributions.zip(from).forEach { (c, participant) ->
            if (ByteVector(c)[0].toInt() != indexOf(wallet, participant)) return "a contribution carries another participant's index"
        }

        // Check 3: the funding output is the group key with the counterparty's. A commitment or a close
        // spends it alone; a splice spends it once, beside the counterparty's own inputs.
        val tx = CoSigningWire.tx(request.tx)
        val input = CoSigningWire.inputInfo(request.input)
        val isSplice = request.context?.type == SPLICE
        if (isSplice) {
            if (tx.txIn.count { it.outPoint == input.outPoint } != 1) return "the splice does not spend the funding output once"
        } else if (tx.txIn.size != 1 || tx.txIn[0].outPoint != input.outPoint) {
            return "the transaction does not spend exactly the funding output"
        }
        if (messageOf(request) == null) return "the request does not say what every input of the transaction spends"
        val remoteKey = PublicKey.fromHex(request.remoteFundingPubKey)
        val groupKey = PublicKey.fromHex(statement.icebergKey)
        val expected = Transactions.makeFundingInputInfo(input.outPoint.txid, input.outPoint.index, input.txOut.amount, groupKey, remoteKey, Transactions.CommitmentFormat.SimpleTaprootChannels)
        if (expected.txOut != input.txOut) return "the funding output is not the group's key with ${request.remoteFundingPubKey.take(16)}"

        // Check 4 onwards: what the transaction is.
        val context = request.context ?: return "the operator did not say what this signs"
        val params = CoSigningWire.params(context.params)
        if (params.localParams.nodeId.toString() != statement.nodeId) return "the channel is not the wallet's node's"
        if (params.remoteParams.nodeId.toString() != statement.lspNodeId) return "the channel is not with the wallet's LSP"
        val onchain = GroupWalletManager.walletFor(database, roomId, statement.chain, statement.onchainAccount) ?: return "the onchain wallet is not open"
        val closeTo = GroupWalletDerivation.derive(onchain.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript
        if (params.localParams.defaultFinalScriptPubKey != closeTo) return "the channel does not close to the group's onchain wallet"

        // The channel is pinned to its funding output the first time this device signs for it, and to
        // each output a splice it checked moved the channel to. A request on any other output is the
        // first commitment on a splice's new one, which answers to its parent's ledger, or is refused.
        val ledgers = dao.channelLedgers(request.wallet, params.channelId.toHex())
        val pinned = ledgers.firstOrNull { it.fundingOutPoint == outPointOf(input) }
        if (pinned != null && pinned.remoteFundingPubKey != request.remoteFundingPubKey) return "the channel's funding output changed"
        if (pinned == null && ledgers.isNotEmpty() && context.type != "remote") return "the channel's funding output changed"

        return when (context.type) {
            "remote" -> when {
                pinned == null && ledgers.isNotEmpty() -> when (val opening = openingOf(database, wallet, request, context, params, tx, ledgers)) {
                    is Opening.Refused -> opening.reason
                    is Opening.Opens -> null
                }
                else -> checkRemote(database, wallet, request, context, params, tx, pinned, ledgers - listOfNotNull(pinned).toSet())
            }
            "closing" -> checkClosing(statement, context, tx, closeTo)
            "local" -> checkLocal(database, wallet, request, context, params, tx, pinned)
            SPLICE -> checkSplice(database, wallet, request, context, tx, pinned ?: return "this device has signed for none of the channel's funding outputs", ledgers)
            else -> "an unknown kind of transaction"
        }
    }

    private fun outPointOf(input: Transactions.InputInfo) = "${input.outPoint.txid}:${input.outPoint.index}"

    /** The first commitment on a splice's new funding output: refused, or the ledger it opens. */
    private sealed class Opening {
        data class Refused(val reason: String) : Opening()
        data class Opens(val parent: IcebergChannelLedger, val ledger: CoSignerPolicy.Ledger, val purchase: com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent.BuyLiquidity) : Opening()
    }

    /**
     * Whether a commitment on a funding output this device has no ledger for is the first commitment on
     * the new output of a splice the group authorised (`docs/group-lightning-liquidity.md`, Phase 5).
     * It must follow the newest output this device signed for, by one index; rebuild from its context;
     * and leave the group what the parent's ledger did, at the same number and with the same HTLCs, less
     * at most the fee cap of an open `buy-liquidity` for the channel. That authorisation is bound to the
     * first parent output it was used from: every attempt at the purchase spends that output, so at most
     * one of them can ever confirm.
     */
    private suspend fun openingOf(
        database: DedunDatabase,
        wallet: GroupLightningWalletManager.Wallet,
        request: CoSigningWire.SigningRequest,
        context: CoSigningWire.Context,
        params: fr.acinq.lightning.channel.ChannelParams,
        tx: Transaction,
        ledgers: List<IcebergChannelLedger>,
    ): Opening {
        // The output one index below, and of those -- an earlier splice may have had attempts that never
        // confirmed -- the one still carrying commitments. A splice's own failed attempt sits beside this
        // one, at its index, and is never its parent.
        val parent = ledgers.filter { it.fundingTxIndex == context.fundingTxIndex - 1 }.maxByOrNull { load(it.ledger).commitIndex }
            ?: return Opening.Refused("a commitment on funding output ${context.fundingTxIndex}, which no splice this device checked would make")
        if (ledgers.any { it.fundingTxIndex > context.fundingTxIndex }) return Opening.Refused("a commitment on funding output ${context.fundingTxIndex}, older than one this device has signed for")
        rebuildsRemote(wallet, request, context, params, tx)?.let { return Opening.Refused(it) }
        val commitIndex = context.commitIndex ?: return Opening.Refused("no commitment number")
        val spec = CoSigningWire.spec(context.spec ?: return Opening.Refused("no commitment spec"), params.channelId)
        val channelId = params.channelId.toHex()
        val purchases = GroupLightningAuthorisations.liquidityPurchases(database, wallet).filter { it.channelId == channelId }
            .filter { buy -> ledgers.none { it.purchaseId == buy.id && it.parentOutPoint != parent.fundingOutPoint } }
        if (purchases.isEmpty()) return Opening.Refused("the group has not authorised buying liquidity for this channel")
        var refusal = "the group has not authorised buying liquidity for this channel"
        for (buy in purchases) {
            when (val verdict = CoSignerPolicy.openSplice(load(parent.ledger), commitIndex, tx.txid.toString(), spec, buy.maxFeeSat * 1000)) {
                is CoSignerPolicy.Verdict.Sign -> return Opening.Opens(parent, verdict.ledger, buy)
                is CoSignerPolicy.Verdict.Refuse -> refusal = verdict.reason
            }
        }
        return Opening.Refused(refusal)
    }

    /**
     * A splice's shared input: the funding output this device has pinned, given up for a new one only
     * when the splice is the liquidity purchase the group authorised (`docs/group-lightning-liquidity.md`,
     * Phase 5, rules S1 to S6). Every amount read here is the transaction's own or its spent outputs',
     * which the signature commits to, so a request that misstates one gets nothing a miner would take.
     */
    private suspend fun checkSplice(
        database: DedunDatabase,
        wallet: GroupLightningWalletManager.Wallet,
        request: CoSigningWire.SigningRequest,
        context: CoSigningWire.Context,
        tx: Transaction,
        parent: IcebergChannelLedger,
        ledgers: List<IcebergChannelLedger>,
    ): String? {
        val statement = wallet.statement
        // S2: it spends the output this device pinned, and says so.
        val parentInput = CoSigningWire.inputInfo(request.input)
        if (context.parentFunding?.let(CoSigningWire::inputInfo) != parentInput || context.parentFundingTxIndex != parent.fundingTxIndex) return "the splice does not say it spends the funding output it spends"
        if (context.fundingTxIndex != parent.fundingTxIndex + 1) return "the splice skips a funding index"
        // S3: one new funding output, the group's key with the LSP's new one, under the cap.
        val index = context.fundingOutputIndex?.takeIf { it in tx.txOut.indices } ?: return "the splice names no new funding output"
        val newKey = PublicKey.fromHex(context.newRemoteFundingPubKey ?: return "the splice names no key for the new funding output")
        val newOutput = tx.txOut[index]
        val expected = Transactions.makeFundingInputInfo(tx.txid, index.toLong(), newOutput.amount, PublicKey.fromHex(statement.icebergKey), newKey, Transactions.CommitmentFormat.SimpleTaprootChannels).txOut.publicKeyScript
        if (newOutput.publicKeyScript != expected) return "the splice's new funding output is not the group's key with the LSP's"
        if (tx.txOut.count { it.publicKeyScript == expected } != 1) return "the splice pays the group's funding script twice"
        GroupLightningRollout.channelCapFor(statement.chain)?.let { cap ->
            if (newOutput.amount > cap) return "the splice makes a channel of ${newOutput.amount.sat} sats, over the ${cap.sat} a channel may hold on mainnet for now"
        }
        // S6: this device already approved the first commitment on that output, from this parent.
        val child = ledgers.firstOrNull { it.fundingOutPoint == "${tx.txid}:$index" } ?: return "this device has not signed the first commitment on the splice's new output"
        if (child.parentOutPoint != parent.fundingOutPoint || child.remoteFundingPubKey != newKey.toString()) return "the first commitment this device signed on that output came from another splice"
        // S1: the purchase the group authorised, for the amount it named.
        val purchase = context.purchase ?: return "the splice buys no liquidity, and the group authorised only buying it"
        val buy = GroupLightningAuthorisations.liquidityPurchases(database, wallet).firstOrNull { it.id == child.purchaseId } ?: return "the group's authorisation for this purchase is not open"
        if (purchase.amountSat != buy.amountSat) return "the splice buys ${purchase.amountSat} sats, not the ${buy.amountSat} the group authorised"
        // S5: paid from the channel balance alone, within the cap.
        if (purchase.paymentType != CoSigningWire.FROM_CHANNEL_BALANCE || purchase.feeCreditUsedMsat != null) return "the purchase is paid ${purchase.paymentType}, and the group pays only from its balance"
        val local = context.localContributionSat ?: return "the splice does not say what the group adds"
        val remote = context.remoteContributionSat ?: return "the splice does not say what the LSP adds"
        if (local > 0) return "the splice adds the group's own money, which this device does not sign"
        val fee = -local + purchase.miningFeeSat + purchase.serviceFeeSat
        if (fee > buy.maxFeeSat) return "the purchase costs $fee sats, over the ${buy.maxFeeSat} the group agreed to"
        // S4: the LSP put in what it is paid for, and the new output holds exactly what the two added.
        if (remote < purchase.amountSat) return "the LSP adds $remote sats for a purchase of ${purchase.amountSat}"
        if (newOutput.amount.sat != parentInput.txOut.amount.sat + local + remote) return "the new funding output does not hold the old one plus what each side added"
        // And the first commitment on it took from the group exactly what the purchase costs.
        val taken = load(parent.ledger).valueMsat - load(child.ledger).valueMsat
        if (taken != fee * 1000) return "the splice's first commitment took $taken msat from the group, not the ${fee * 1000} the purchase costs"
        return null
    }

    /**
     * Our own commitment, which the group signs only to force-close: authorised, rebuilt from its context,
     * and no older than the counterparty commitment this device last signed for the channel, less one.
     * The two numbers move together, so a local commitment further behind than that is one this channel
     * has revoked, and publishing it would hand the counterparty everything.
     */
    private suspend fun checkLocal(
        database: DedunDatabase,
        wallet: GroupLightningWalletManager.Wallet,
        request: CoSigningWire.SigningRequest,
        context: CoSigningWire.Context,
        params: fr.acinq.lightning.channel.ChannelParams,
        tx: Transaction,
        pinned: IcebergChannelLedger?,
    ): String? {
        if (!GroupLightningAuthorisations.forceCloseAuthorised(database, wallet.signedEvent.chatRoomId, wallet.statement, params.channelId)) return "the group has not authorised a force close"
        val seed = wallet.seed ?: return "this device does not hold the node's seed, and cannot rebuild the commitment"
        val commitParams = CoSigningWire.commit(context.commit ?: return "no commitment parameters")
        val commitIndex = context.commitIndex ?: return "no commitment number"
        pinned?.let { if (commitIndex < load(it.ledger).commitIndex - 1) return "commitment $commitIndex is older than this channel's current one, and revoked" }
        val keys = keyManagerOf(ByteVector(seed.seed), wallet.statement.chain).channelKeys(params.localParams.fundingKeyPath)
            .withGroupBasepoints(wallet.basepoints.forChannel)
        val (rebuilt, _) = Commitments.makeLocalTxs(
            channelParams = params,
            commitParams = commitParams,
            commitKeys = keys.localCommitmentKeys(params, commitIndex),
            commitTxNumber = commitIndex,
            localFundingPubkey = PublicKey.fromHex(wallet.statement.icebergKey),
            remoteFundingPubKey = PublicKey.fromHex(request.remoteFundingPubKey),
            commitmentInput = CoSigningWire.inputInfo(request.input),
            commitmentFormat = Transactions.CommitmentFormat.SimpleTaprootChannels,
            spec = CoSigningWire.spec(context.spec ?: return "no commitment spec", params.channelId),
        )
        return if (rebuilt.tx != tx) "the commitment its context rebuilds to is not the one it asks to sign" else null
    }

    private suspend fun checkRemote(
        database: DedunDatabase,
        wallet: GroupLightningWalletManager.Wallet,
        request: CoSigningWire.SigningRequest,
        context: CoSigningWire.Context,
        params: fr.acinq.lightning.channel.ChannelParams,
        tx: Transaction,
        pinned: IcebergChannelLedger?,
        siblings: List<IcebergChannelLedger>,
    ): String? {
        rebuildsRemote(wallet, request, context, params, tx)?.let { return it }
        val commitIndex = context.commitIndex ?: return "no commitment number"
        val spec = CoSigningWire.spec(context.spec ?: return "no commitment spec", params.channelId)

        // The first commitment a device signs for a channel is where its floor starts; every later one
        // answers to the ledger of the funding output it is on.
        if (pinned == null) return null
        val budgets = withSiblings(GroupLightningAuthorisations.budgets(database, wallet), spentBySiblings(siblings, commitIndex))
        val closed = GroupLightningAuthorisations.expired(database, wallet)
        return when (val verdict = CoSignerPolicy.checkRemoteCommitment(load(pinned.ledger), commitIndex, tx.txid.toString(), spec, budgets, closed, wallet.statement.policy.inFlightCapSat * 1000)) {
            is CoSignerPolicy.Verdict.Refuse -> verdict.reason
            is CoSignerPolicy.Verdict.Sign -> null
        }
    }

    /** [budgets] with what a sibling commitment at the same number already spent given back, since this one spends it too. */
    private fun withSiblings(budgets: Map<String, Long>, spent: Map<String, Long>): Map<String, Long> =
        (budgets.keys + spent.keys).associateWith { (budgets[it] ?: 0) + (spent[it] ?: 0) }.filterValues { it > 0 }

    /** Why the commitment's context does not rebuild to [tx], or null when it does. */
    private fun rebuildsRemote(
        wallet: GroupLightningWalletManager.Wallet,
        request: CoSigningWire.SigningRequest,
        context: CoSigningWire.Context,
        params: fr.acinq.lightning.channel.ChannelParams,
        tx: Transaction,
    ): String? {
        val seed = wallet.seed ?: return "this device does not hold the node's seed, and cannot rebuild the commitment"
        // On mainnet a channel holds at most the rollout's cap. Refusing its commitments means a bigger
        // channel never opens, and a splice to a bigger one is never signed into.
        GroupLightningRollout.channelCapFor(wallet.statement.chain)?.let { cap ->
            val capacity = CoSigningWire.inputInfo(request.input).txOut.amount
            if (capacity > cap) return "the channel holds ${capacity.sat} sats, over the ${cap.sat} a channel may hold on mainnet for now"
        }
        val commitParams = CoSigningWire.commit(context.commit ?: return "no commitment parameters")
        val commitIndex = context.commitIndex ?: return "no commitment number"
        val perCommitmentPoint = PublicKey.fromHex(context.remotePerCommitmentPoint ?: return "no per-commitment point")
        val spec = CoSigningWire.spec(context.spec ?: return "no commitment spec", params.channelId)
        val keys = keyManagerOf(ByteVector(seed.seed), wallet.statement.chain).channelKeys(params.localParams.fundingKeyPath)
            .withGroupBasepoints(wallet.basepoints.forChannel)
        val (rebuilt, _) = Commitments.makeRemoteTxs(
            channelParams = params,
            commitParams = commitParams,
            commitKeys = keys.remoteCommitmentKeys(params, perCommitmentPoint),
            commitTxNumber = commitIndex,
            localFundingPubkey = PublicKey.fromHex(wallet.statement.icebergKey),
            remoteFundingPubKey = PublicKey.fromHex(request.remoteFundingPubKey),
            commitmentInput = CoSigningWire.inputInfo(request.input),
            commitmentFormat = Transactions.CommitmentFormat.SimpleTaprootChannels,
            spec = spec,
        )
        return if (rebuilt.tx != tx) "the commitment its context rebuilds to is not the one it asks to sign" else null
    }

    private fun checkClosing(statement: com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent.Statement, context: CoSigningWire.Context, tx: Transaction, closeTo: ByteVector): String? {
        val local = ByteVector(context.localScript ?: return "no closing script")
        val remote = ByteVector(context.remoteScript ?: return "no counterparty script")
        if (local != closeTo) return "the close does not pay the group's onchain wallet"
        if (tx.txOut.any { it.publicKeyScript != local && it.publicKeyScript != remote }) return "the close pays a script it did not name"
        val ours = (context.spec ?: return "no commitment spec").toLocalMsat / 1000
        val paid = tx.txOut.filter { it.publicKeyScript == local }.sumOf { it.amount.sat }
        val allowance = statement.policy.commitmentFeeAllowanceSat
        return if (ours > allowance && paid < ours - allowance) "the close pays the group $paid sats of its $ours" else null
    }

    /** Records what this device let the commitment do, at echo time: it is committed to it from here. */
    private suspend fun commitLedger(database: DedunDatabase, roomId: String, wallet: GroupLightningWalletManager.Wallet, request: CoSigningWire.SigningRequest) {
        val context = request.context ?: return
        if (context.type != "remote") return
        val params = CoSigningWire.params(context.params)
        val spec = CoSigningWire.spec(context.spec ?: return, params.channelId)
        val tx = CoSigningWire.tx(request.tx)
        val dao = database.icebergCoSigningDao()
        val channelId = params.channelId.toHex()
        val input = CoSigningWire.inputInfo(request.input)
        val ledgers = dao.channelLedgers(request.wallet, channelId)
        val pinned = ledgers.firstOrNull { it.fundingOutPoint == outPointOf(input) }
        val commitIndex = context.commitIndex ?: return
        val allowance = wallet.statement.policy.commitmentFeeAllowanceSat * 1000
        val row = when {
            ledgers.isEmpty() -> IcebergChannelLedger(request.wallet, channelId, outPointOf(input), request.remoteFundingPubKey, store(CoSignerPolicy.open(commitIndex, tx.txid.toString(), spec, allowance)), fundingTxIndex = context.fundingTxIndex)
            pinned == null -> when (val opening = openingOf(database, wallet, request, context, params, tx, ledgers)) {
                is Opening.Refused -> return
                is Opening.Opens -> IcebergChannelLedger(
                    request.wallet, channelId, outPointOf(input), request.remoteFundingPubKey, store(opening.ledger),
                    fundingTxIndex = context.fundingTxIndex, parentOutPoint = opening.parent.fundingOutPoint, purchaseId = opening.purchase.id,
                )
            }
            else -> {
                val siblings = spentBySiblings(ledgers - pinned, commitIndex)
                val budgets = withSiblings(GroupLightningAuthorisations.budgets(database, wallet), siblings)
                when (val verdict = CoSignerPolicy.checkRemoteCommitment(load(pinned.ledger), commitIndex, tx.txid.toString(), spec, budgets, GroupLightningAuthorisations.expired(database, wallet), wallet.statement.policy.inFlightCapSat * 1000)) {
                    is CoSignerPolicy.Verdict.Refuse -> return
                    is CoSignerPolicy.Verdict.Sign -> {
                        // A sibling at this number already spent its share of each budget; spend only what is new.
                        GroupLightningAuthorisations.consume(database, wallet, verdict.consumed.mapValues { (hash, amount) -> amount - (siblings[hash] ?: 0) }.filterValues { it > 0 })
                        pinned.copy(ledger = store(verdict.ledger, verdict.consumed), updatedAt = Clock.System.now())
                    }
                }
            }
        }
        dao.upsertChannelLedger(row)
        val next = load(row.ledger)
        logger.i("Channel ${channelId.take(16)} in $roomId, funding output ${row.fundingTxIndex}: commitment ${next.commitIndex} leaves the group ${next.valueMsat} msat against a floor of ${next.floorMsat}")
    }
}
