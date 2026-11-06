package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.IcebergLabelAssignment
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.wallet.wavelength.group.ArkRequest
import com.machankura.dedun.compose.wallet.wavelength.group.ArkResponse
import com.machankura.dedun.compose.wallet.wavelength.group.ArkWire
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkAuth
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkSigner
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.RandomInstance
import engineering.lightning.wavelength.SignerHost
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.iceberg.NonceContribution
import fr.acinq.bitcoin.crypto.iceberg.SignatureShare
import fr.acinq.bitcoin.crypto.musig2.AggregatedNonce
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.utils.Either
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The operator's side of co-signing -- `docs/group-wavelength-wallet.md`, Phases 3 and 6: what the daemon's callback signer
 * talks to on the elected device. The daemon holds no fund key and this holds none either. It holds the wallet's authentication
 * seed, which answers for the keys that sign too often for a quorum, and this device's own share, like any other participant.
 *
 * Every other answer is asked of the room, and comes back only if `n-t+1` members' devices agreed to it. The operator is trusted
 * for liveness only: if this class is lying, members' [com.machankura.dedun.compose.wallet.wavelength.group.ArkPolicy] is what
 * stops it.
 */
class GroupArkTransport(
    private val database: DedunDatabase,
    private val localChatRoom: LocalChatRoom,
    private val userPublicKey: HexKey,
    private val wallet: GroupWavelengthWalletManager.Wallet,
    private val term: Long,
    private val auth: GroupArkAuth,
    /** Below the daemon's own request timeout, so a refusal is an answer rather than a dropped connection. */
    private val timeout: Duration = 60.seconds,
) : SignerHost {
    private val logger = Logger.withTag("GroupArkTransport")
    private val statement = wallet.statement
    private val key = GroupArkCoSigner.walletKey(localChatRoom.chatRoom.id, statement.chain, statement.wallet)
    private val n = statement.participants.size
    private val t = statement.threshold
    private val quorum = 2 * t - 1
    private val groupKey = PublicKey.fromHex(statement.icebergKey)
    private val mutex = Mutex()

    /** One open MuSig2 session: a tree node's, for one tree key, under a label of its own. */
    private class Session(val child: Long, val counter: Long, val signers: List<String>, val scriptRoot: String, val contributions: List<String>, val groupNonce: IndividualNonce, val key: PublicKey) {
        var cosignerNonce: String? = null
    }

    private val sessions = HashMap<String, Session>()

    override fun handle(request: ByteArray): ByteArray = runBlocking {
        val parsed = CommonJson.decodeFromString(ArkRequest.serializer(), request.decodeToString())
        CommonJson.encodeToString(ArkResponse.serializer(), answer(parsed)).encodeToByteArray()
    }

    suspend fun answer(request: ArkRequest): ArkResponse = when (request.kind) {
        ArkRequest.MESSAGE -> if (request.family.toLong() == GroupArkAuth.IDENTITY) identityMessage(request) else plain(request)
        ArkRequest.SHARED_KEY -> sharedKey(request)
        ArkRequest.INPUT -> plain(request)
        ArkRequest.NEXT_TREE_KEY -> nextTreeKey()
        ArkRequest.TREE_KEY -> treeKey(request.index)
        ArkRequest.MUSIG2_CREATE -> createSession(request)
        ArkRequest.MUSIG2_NONCE -> registerNonce(request)
        ArkRequest.MUSIG2_SIGN -> treeSign(request)
        ArkRequest.MUSIG2_CLEANUP -> ArkResponse().also { mutex.withLock { sessions.remove(request.sessionId) } }
        else -> throw IllegalArgumentException("a request of a kind this signer doesn't answer: ${request.kind}")
    }

    /** The wallet's identity signs here and now, for mailbox and swap authentication. It appears in no output script. */
    private fun identityMessage(request: ArkRequest): ArkResponse {
        require(request.schnorr && !request.compact && request.tapTweak.isNullOrEmpty()) { "the identity signs only plain Schnorr messages" }
        val message = ByteVector(request.message!!).toByteArray()
        val digest = when {
            !request.tag.isNullOrEmpty() -> Crypto.taggedHash(message, request.tag)
            request.doubleHash -> ByteVector32(Crypto.hash256(message))
            else -> ByteVector32(Crypto.sha256(message))
        }
        val signature = Crypto.signSchnorr(digest, auth.privateKey(GroupArkAuth.IDENTITY, request.index), null)
        return ArkResponse(signature = signature.toByteArray().toHex())
    }

    private fun sharedKey(request: ArkRequest): ArkResponse {
        require(request.family.toLong() == GroupArkAuth.IDENTITY) { "only the identity computes a shared key here" }
        val secret = Crypto.ecdh(auth.privateKey(GroupArkAuth.IDENTITY, request.index), PublicKey.fromHex(request.peer!!))
        return ArkResponse(sharedKey = secret.toHex())
    }

    // ---- round one, ahead of time ----

    /** Asks the group for round one up to these. Each device publishes it, this one included. */
    suspend fun provision(plainTo: Long, treeTo: Long) = send(ArkWire.SigningBatch(key, term, provisionPlainTo = plainTo, provisionTreeTo = treeTo))

    private suspend fun send(batch: ArkWire.SigningBatch) {
        val queued = GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupWavelengthEvents.SIGNING_REQUEST, CommonJson.encodeToString(ArkWire.SigningBatch.serializer(), batch))
        // The operator holds a share like everyone else, and doesn't read its own messages back.
        GroupArkCoSigner.process(database, localChatRoom, Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, queued.content, ""), userPublicKey)
    }

    private suspend fun contributions(wallet: String, counter: Long): List<String>? =
        database.icebergCoSigningDao().noncesFor(wallet, counter).takeIf { it.size >= quorum }?.sortedBy { it.contribution }?.map { it.contribution }

    /** The next unused counter under [wallet], kept so a restart doesn't hand a label out twice. */
    private suspend fun allocate(wallet: String, purpose: String): Long {
        val dao = database.icebergCoSigningDao()
        val counter = (dao.highestAssignment(wallet) ?: -1L) + 1
        dao.insertAssignment(IcebergLabelAssignment(wallet, counter, "", purpose))
        return counter
    }

    private suspend fun treeKeyAt(child: Long): PublicKey? = GroupArkCoSigner.treeKeys(database, wallet, key)[child]

    private suspend fun nextTreeKey(): ArkResponse = mutex.withLock {
        val child = allocate("$key:tree", "tree")
        val publicKey = treeKeyAt(child) ?: throw IllegalStateException("the group hasn't made tree key $child yet")
        ArkResponse(index = child, pubKey = publicKey.toString())
    }

    private suspend fun treeKey(child: Long): ArkResponse =
        ArkResponse(index = child, pubKey = (treeKeyAt(child) ?: throw IllegalStateException("no tree key $child")).toString())

    // ---- a tree node's MuSig2 session ----

    private suspend fun createSession(request: ArkRequest): ArkResponse = mutex.withLock {
        val child = request.index
        val publicKey = treeKeyAt(child) ?: throw IllegalStateException("no tree key $child")
        val wallet = GroupArkCoSigner.treeKey(key, child)
        val counter = allocate("$wallet:sessions", "session")
        require(counter < GroupArkCoSigner.TREE_LABELS) { "tree key $child has signed more nodes than it has labels for" }
        val contributions = contributions(wallet, counter) ?: throw IllegalStateException("round one isn't in for tree key $child")
        val nonce = groupNonceOf(contributions, publicKey)
        val sessionId = RandomInstance.bytes(32).toHex()
        sessions[sessionId] = Session(child, counter, request.signers, request.scriptRoot.orEmpty(), contributions, nonce, publicKey)
        ArkResponse(sessionId = sessionId, publicNonce = nonce.toByteArray().toHex())
    }

    private fun groupNonceOf(contributions: List<String>, publicKey: PublicKey): IndividualNonce =
        when (val nonce = GroupArkSigner.groupNonce(contributions.map { NonceContribution(it) }, n, t, publicKey)) {
            is Either.Right -> nonce.value
            is Either.Left -> throw nonce.value
        }

    /** The aggregate the operator hands the daemon includes ours, and Iceberg wants everybody else's alone. */
    private suspend fun registerNonce(request: ArkRequest): ArkResponse = mutex.withLock {
        val session = sessions[request.sessionId] ?: throw IllegalStateException("no such session")
        val combined = ByteVector(request.combinedNonce!!).toByteArray()
        val ours = session.groupNonce.toByteArray()
        fun apart(from: Int): ByteArray = (PublicKey(combined.copyOfRange(from, from + 33)) - PublicKey(ours.copyOfRange(from, from + 33))).value.toByteArray()
        session.cosignerNonce = (apart(0) + apart(33)).toHex()
        ArkResponse()
    }

    private suspend fun treeSign(request: ArkRequest): ArkResponse {
        val session = mutex.withLock { sessions[request.sessionId] } ?: throw IllegalStateException("no such session")
        val cosignerNonce = session.cosignerNonce ?: throw IllegalStateException("a session with no nonce yet")
        val requestId = RandomInstance.bytes(16).toHex()
        val item = ArkWire.SigningItem(
            requestId = requestId,
            domain = ArkWire.Domain(session.child),
            counter = session.counter,
            request = request.copy(signers = session.signers, scriptRoot = session.scriptRoot),
            contributions = session.contributions,
            cosignerNonce = cosignerNonce,
        )
        val shares = ask(item)
        return when (val partial = GroupArkSigner.treePartialSignature(shares, n, t)) {
            is Either.Right -> ArkResponse(signature = partial.value.toByteArray().toHex())
            is Either.Left -> throw partial.value
        }
    }

    // ---- a plain signature ----

    private suspend fun plain(request: ArkRequest): ArkResponse {
        val cache = GroupArkCoSigner.cacheOf(wallet, request) ?: throw IllegalArgumentException("a request for no key the group holds")
        val counter = mutex.withLock { allocate(key, "plain") }
        val contributions = contributions(key, counter) ?: throw IllegalStateException("round one isn't in for label $counter")
        val requestId = RandomInstance.bytes(16).toHex()
        val shares = ask(ArkWire.SigningItem(requestId, ArkWire.Domain(), counter, request, contributions))
        val label = GroupArkCoSigner.label(statement.icebergKey, ArkWire.Domain(), counter)
        val message = database.groupArkDao().checked(key, label.toHex())?.message?.let { ByteVector32(it) }
            ?: throw IllegalStateException("a signature this device didn't check")
        val nonce = groupNonceOf(contributions, groupKey)
        return when (val signature = GroupArkSigner.plainSignature(shares, n, t, nonce, cache, message)) {
            is Either.Right -> ArkResponse(signature = signature.value.toByteArray().toHex())
            is Either.Left -> throw signature.value
        }
    }

    /** Sends one signature to the room and waits for the quorum, as long as the deadline allows. A refusal looks like silence. */
    private suspend fun ask(item: ArkWire.SigningItem): List<SignatureShare> {
        send(ArkWire.SigningBatch(key, term, items = listOf(item)))
        val dao = database.icebergCoSigningDao()
        val shares = withTimeoutOrNull(timeout) {
            while (true) {
                val rows = dao.sharesFor(key, item.requestId)
                if (rows.size >= quorum) return@withTimeoutOrNull rows
                delay(POLL)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: throw IllegalStateException("the group didn't answer: fewer than $quorum devices agreed to this in time").also { logger.w(it.message!!) }
        return shares.map { SignatureShare(it.share) }
    }

    private companion object {
        val POLL: Duration = 100.milliseconds
    }
}
