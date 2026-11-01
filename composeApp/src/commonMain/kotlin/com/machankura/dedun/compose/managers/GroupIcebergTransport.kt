package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.IcebergLabelAssignment
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.wallet.lightning.CoSigningWire
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.crypto.iceberg.NonceContribution
import fr.acinq.bitcoin.crypto.iceberg.SignatureShare
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.crypto.FundingSigner
import fr.acinq.lightning.crypto.IcebergGroupTransport
import fr.acinq.lightning.crypto.IcebergRoundTwoRequest
import fr.acinq.lightning.transactions.Transactions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The operator's side of co-signing: what lightning-kmp's `RemoteIcebergFundingSigner` talks to on the
 * operator's device (`docs/group-lightning-wallet.md`, Phases 8 and 9).
 *
 * **Round one is a pool.** Every label the node will use comes from one sequence of pool counters
 * ([GroupLightningCoSigner.poolLabel]), and every participant publishes its contributions for the next
 * [GroupLightningCoSigner.POOL_WINDOW] counters ahead of any need. A verification nonce's funding txid
 * does not exist until the channel is being negotiated, so a label cannot be computed from it in advance;
 * instead each new need is *assigned* the next counter, and the assignment is kept so the same need gets
 * the same label again. The interface lightning-kmp calls for round one is synchronous, so the pool and
 * the assignments are held in memory, loaded at start and refreshed as batches arrive.
 *
 * **Round two is a request.** [roundTwo] queues a signing request for the room, hands it to this
 * device's own co-signer at once -- the operator holds a share like everyone else -- and waits until
 * `2t-1` signature shares are filed: the quorum the scheme wants online for round two, of which
 * aggregation uses as few as `t`.
 *
 * Losing an assignment in a crash costs a verification nonce that cannot be re-derived, which costs a
 * force close's signature, never a share: a counter handed out twice is a label the co-signers' ledgers
 * already hold under another message, and every one of them refuses it.
 */
class GroupIcebergTransport private constructor(
    private val database: DedunDatabase,
    private val localChatRoom: LocalChatRoom,
    private val userPublicKey: HexKey,
    private val wallet: GroupLightningWalletManager.Wallet,
    private val term: Long,
    private val scope: CoroutineScope,
) : IcebergGroupTransport {
    private val logger = Logger.withTag("GroupIcebergTransport")
    private val key = GroupLightningCoSigner.walletKey(localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)

    override val n: Int = wallet.statement.participants.size
    override val t: Int = wallet.statement.threshold

    private val labelsByCounter = mutableMapOf<Long, ByteVector32>()
    private val countersByLabel = mutableMapOf<ByteVector32, Long>()
    private val assignments = mutableMapOf<String, Long>()
    private val pool = mutableMapOf<Long, List<NonceContribution>>()
    private var nextCounter = 0L
    private var refresher: Job? = null

    /** The highest counter whose label has been handed out, for the snapshot and for provisioning. */
    val highestAssigned: Long get() = nextCounter - 1

    /** Every need given a label so far, by purpose: what a snapshot hands the next operator. */
    fun assignments(): Map<String, Long> = assignments.toMap()

    private fun label(counter: Long): ByteVector32 = labelsByCounter.getOrPut(counter) {
        GroupLightningCoSigner.poolLabel(wallet.statement.icebergKey, counter).also { countersByLabel[it] = counter }
    }

    private suspend fun reload() {
        val dao = database.icebergCoSigningDao()
        dao.assignments(key).forEach { a ->
            assignments[a.purpose] = a.counter
            label(a.counter)
        }
        nextCounter = maxOf(nextCounter, (dao.highestAssignment(key) ?: -1) + 1)
        val upTo = nextCounter + GroupLightningCoSigner.POOL_WINDOW
        // Every counter already handed out stays loaded too: a verification nonce is signed under long
        // after it was assigned, at a force close.
        for (counter in assignments.values + (nextCounter..upTo)) {
            val rows = dao.noncesFor(key, counter)
            if (rows.size >= 2 * t - 1) pool[counter] = rows.sortedBy { it.contribution }.map { NonceContribution(it.contribution) }
        }
    }

    private fun assign(purpose: String?): ByteVector32 {
        purpose?.let { p -> assignments[p]?.let { return label(it) } }
        val counter = nextCounter++
        purpose?.let { assignments[it] = counter }
        val assigned = label(counter)
        scope.launch {
            runCatching { database.icebergCoSigningDao().insertAssignment(IcebergLabelAssignment(key, counter, assigned.toHex(), purpose ?: "fresh:$counter")) }
                .onFailure { logger.e("Could not record pool counter $counter", it) }
        }
        maybeProvision()
        return assigned
    }

    /** Asks the group for more round one when the pool is running out ahead of the counters handed out. */
    private fun maybeProvision() {
        val ready = pool.keys.filter { it >= nextCounter }.size
        if (ready > GroupLightningCoSigner.POOL_WINDOW / 3) return
        scope.launch { provision(nextCounter + GroupLightningCoSigner.POOL_WINDOW) }
    }

    /** Sends the room a provision request up to [upTo], and publishes this device's own contributions. */
    suspend fun provision(upTo: Long) {
        val request = CoSigningWire.SigningRequest(
            wallet = key, term = term, requestId = RandomInstance.bytes(16).toHex(), kind = GroupLightningCoSigner.PROVISION,
            label = "", counter = upTo, tx = "", input = CoSigningWire.Input("", 0, 0, ""), fundingTxId = "", remoteFundingPubKey = "",
            contributions = emptyList(), cosignerAggregatedNonce = "",
        )
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.SIGNING_REQUEST, CommonJson.encodeToString(CoSigningWire.SigningRequest.serializer(), request))
        GroupLightningCoSigner.provision(database, localChatRoom, userPublicKey, wallet, upTo)
        reload()
    }

    /**
     * Waits until round one is in hand for the next [counters] pool counters, reloading as batches land,
     * for at most [timeout]. False when the group has not sent enough: a node that connected now could be
     * asked to sign before it could.
     */
    suspend fun awaitPool(counters: Int, timeout: kotlin.time.Duration): Boolean {
        if (counters <= 0) return true
        val ready = { (nextCounter until nextCounter + counters).all { it in pool } }
        return withTimeoutOrNull(timeout) {
            while (true) {
                reload()
                if (ready()) return@withTimeoutOrNull true
                delay(POLL)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
    }

    override fun contributions(label: ByteVector32): List<NonceContribution>? = countersByLabel[label]?.let { pool[it] }

    override fun verificationLabel(id: FundingSigner.VerificationNonceId): ByteVector32 =
        assign("verification:${id.fundingTxId}:${id.remoteFundingPubKey}:${id.commitIndex}")

    override fun nextFreshLabel(fundingTxId: TxId): ByteVector32 = assign(null)

    override suspend fun roundTwo(request: IcebergRoundTwoRequest): Either<Throwable, List<SignatureShare>> {
        val counter = countersByLabel[request.label] ?: return Either.Left(IllegalStateException("label ${request.label} is not from this wallet's pool"))
        val requestId = RandomInstance.bytes(16).toHex()
        val wire = CoSigningWire.SigningRequest(
            wallet = key,
            term = term,
            requestId = requestId,
            kind = request.kind.name,
            label = request.label.toHex(),
            counter = counter,
            tx = CoSigningWire.tx(request.tx.tx),
            input = CoSigningWire.input(request.tx.input),
            fundingTxId = request.fundingTxId.toString(),
            remoteFundingPubKey = request.remoteFundingPubKey.toString(),
            commitIndex = request.commitIndex,
            contributions = request.contributions.map { it.toByteArray().toHex() },
            cosignerAggregatedNonce = request.cosignerAggregatedNonce.data.toHex(),
            context = CoSigningWire.context(request.context),
            extraUtxos = request.extraUtxos.map { (outPoint, txOut) -> CoSigningWire.input(Transactions.InputInfo(outPoint, txOut)) },
        )
        val content = CommonJson.encodeToString(CoSigningWire.SigningRequest.serializer(), wire)
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.SIGNING_REQUEST, content)
        // This device is a co-signer too, and answers its own request the way the inbound path would.
        GroupLightningCoSigner.process(database, localChatRoom, Event("", userPublicKey, Clock.System.now().epochSeconds, GroupLightningEvents.SIGNING_REQUEST, emptyArray(), content, ""), userPublicKey)

        val wanted = minOf(n, 2 * t - 1)
        while (true) {
            val shares = database.icebergCoSigningDao().sharesFor(key, requestId)
            if (shares.size >= wanted) return Either.Right(shares.map { SignatureShare(it.share) })
            delay(POLL)
        }
    }

    /** Re-reads the pool every [REFRESH], so batches filed by the inbound path reach the synchronous round one. */
    fun start() {
        refresher?.cancel()
        refresher = scope.launch {
            while (isActive) {
                runCatching { reload() }.onFailure { logger.e("Could not reload the round-one pool", it) }
                delay(REFRESH)
            }
        }
    }

    fun stop() {
        refresher?.cancel()
    }

    companion object {
        private val POLL = 100.milliseconds
        private val REFRESH = 2.seconds

        /**
         * The transport for [wallet], with its pool and assignments loaded. The first counter it hands out
         * is past every one this device has recorded assigning.
         */
        suspend fun create(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, term: Long, scope: CoroutineScope): GroupIcebergTransport =
            GroupIcebergTransport(database, localChatRoom, userPublicKey, wallet, term, scope).also { it.reload() }
    }
}
