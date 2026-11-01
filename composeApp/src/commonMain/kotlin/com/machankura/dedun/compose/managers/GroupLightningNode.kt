package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupChainClients
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.wallet.lightning.GroupNodeKeyManager
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.machankura.dedun.compose.wallet.lightning.WalletWire
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.PaymentEvents
import fr.acinq.lightning.PeerConnected
import fr.acinq.lightning.WalletParams
import fr.acinq.lightning.blockchain.Feerates
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.ChannelCommand
import kotlinx.coroutines.flow.update
import com.machankura.dedun.compose.wallet.lightning.CoSigningWire
import fr.acinq.lightning.channel.ChannelFundingResponse
import fr.acinq.lightning.channel.GroupHeldOutputs
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.channel.states.Closed
import fr.acinq.lightning.channel.states.Closing
import fr.acinq.lightning.crypto.RemoteIcebergFundingSigner
import fr.acinq.lightning.db.Databases
import fr.acinq.lightning.db.LightningIncomingPayment
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.db.WalletPayment
import fr.acinq.lightning.io.PaymentSent
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.WrappedChannelCommand
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.Init
import fr.acinq.phoenix.utils.extensions.isOnion
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The group's Lightning node, on the operator's device (`docs/group-lightning-wallet.md`, Phase 8).
 *
 * A second lightning-kmp node beside the identity's own, built from the wallet's seed, with the group's
 * funding key, basepoints and closing address ([GroupNodeKeyManager]), and a remote Iceberg signer whose
 * transport ([GroupIcebergTransport]) reaches the members through the room. It runs only while this
 * device is the elected operator and has heard from the room recently ([GroupLightningOperatorManager.mayRunNode]),
 * and it stops itself the moment a newer term names somebody else -- telling the room where its
 * channels were as it goes (kind 30366).
 *
 * The node itself is behind [PeerHandle], so the life cycle can be tested without an LSP to connect to.
 */
class GroupLightningNode(
    private val database: DedunDatabase,
    private val localChatRoom: LocalChatRoom,
    private val userPublicKey: HexKey,
    val wallet: GroupLightningWalletManager.Wallet,
    private val scope: CoroutineScope,
    private val peerFactory: suspend (NodeConfiguration) -> PeerHandle,
    private val now: () -> kotlin.time.Instant = { Clock.System.now() },
    private val handoverCheck: Duration = HANDOVER_CHECK,
    private val reportEvery: Duration = REPORT_EVERY,
    /** How many pool counters must hold round one before the node connects; 0 for a test with no co-signers. */
    private val minimumPool: Int = MINIMUM_POOL,
    private val poolWait: Duration = POOL_WAIT,
    /** How long to wait before redialling the LSP after [failures] attempts in a row that did not hold. */
    private val reconnectAfter: (failures: Int) -> Duration = ::reconnectDelay,
) {
    private val logger = Logger.withTag("GroupLightningNode")
    private val chain = wallet.statement.chain

    /** What a node needs to be built, gathered here so a [peerFactory] can build a real one or a fake. */
    class NodeConfiguration(
        val keyManager: GroupNodeKeyManager,
        val chain: Chain,
        val lspNodeId: PublicKey,
        val transport: GroupIcebergTransport,
        /** The most the group agreed to pay the LSP for the liquidity a payment needs: its statement's policy. */
        val maxLiquidityFee: Satoshi = Satoshi(GroupLightningWalletStatementEvent.Policy.DEFAULT.maxLiquidityFeeSat),
    )

    /** What this class needs of a running node. */
    interface PeerHandle {
        /**
         * Dials the LSP; true once the handshake is done, false when it could not be. [attempt] counts the tries
         * in a row, from 1, for the timeouts to grow with as Phoenix's do.
         */
        suspend fun connect(attempt: Int = 1): Boolean
        fun disconnect()
        /** Suspends until the connection to the LSP is down -- at once, when it is down already -- and says why. */
        suspend fun awaitDisconnect(): String
        /** Each channel's local commitment number, by channel id: what the stopped message reports. */
        fun commitmentNumbers(): Map<String, Long>
        /** The node's channels, as a snapshot reports them. */
        fun channels(): List<WalletWire.ChannelView>
        /** The payments the node has made or received while running, oldest first. */
        fun payments(): List<WalletWire.PaymentView>
        /** A Bolt11 invoice paying the node, for [amountMsat] or any amount. */
        suspend fun createInvoice(amountMsat: Long?, description: String): String
        /** Whether the node has tried to pay [paymentHash] before, in this run or an earlier one. */
        suspend fun hasPaid(paymentHash: String): Boolean
        /** Pays [bolt11] [amountMsat], through the LSP, and says how it went. */
        suspend fun pay(bolt11: String, amountMsat: Long): WalletWire.PaymentView
        /** Starts a mutual close of [channelId] to [scriptPubKey]. */
        suspend fun close(channelId: String, scriptPubKey: ByteVector)
        /** Publishes our own commitment of [channelId]. */
        suspend fun forceClose(channelId: String)
        /** The outputs closing channels pay to the group's basepoints, for the group to sweep. */
        fun heldOutputs(): List<GroupSweep.Input>
        /** The LSP's liquidity rates, as its `init` sent them, or null before it has. */
        fun fundingRates(): WalletWire.FundingRates?
        /** The feerate the node would splice at, in sat/vbyte, or null while it has none. */
        fun feeratePerByte(): Long?
        /**
         * Buys [amountSat] of inbound liquidity for [channelId] from the LSP, by splicing, if its estimate is
         * at most [maxFeeSat], and says what came of it, under the authorisation [id].
         */
        suspend fun buyLiquidity(id: String, channelId: String, amountSat: Long, maxFeeSat: Long): WalletWire.LiquidityPurchase
    }

    sealed class State {
        data object Stopped : State()
        data class Running(val term: Long) : State()
        data class Refused(val reason: String) : State()
    }

    /** Tor is on and [chain]'s LSP has no onion address: the node will not leave Tor to reach it. */
    class TorWithoutOnion(chain: Chain) : IllegalStateException("Tor is on, and the LSP has no onion address on ${chain.name}")

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state

    private var peer: PeerHandle? = null
    private var transport: GroupIcebergTransport? = null
    private var watching: Job? = null
    private var connecting: Job? = null
    private var waking: Job? = null
    /** Held while the node acts on what the room asked of it, so no two callers act on one request at once. */
    private val acting = Mutex()
    private var lastReported: Triple<List<WalletWire.ChannelView>, List<WalletWire.PaymentView>, List<GroupSweep.Input>>? = null
    private var lastPurchases: List<WalletWire.LiquidityPurchase> = emptyList()
    /** What came of each liquidity purchase this node took on, by authorisation id. */
    private val purchases = MutableStateFlow<Map<String, WalletWire.LiquidityPurchase>>(emptyMap())
    private var lastReportedAt: kotlin.time.Instant? = null

    /** Starts the node if this device may run it now; otherwise says why not. */
    suspend fun start(): State {
        if (_state.value is State.Running) return _state.value
        val roomId = localChatRoom.chatRoom.id
        val operator = GroupLightningOperatorManager.current(database, roomId, chain, wallet.statement.wallet)
        val lastHeard = database.marmotGroupEventDao().newestSavedAt(roomId)
        if (!GroupLightningOperatorManager.mayRunNode(database, roomId, chain, userPublicKey, lastHeard, now(), wallet.statement.wallet)) {
            return State.Refused(
                if (operator?.publicKey != userPublicKey) "this device is not the operator" else "this device has not heard from the room recently enough to be sure it still is"
            ).also { _state.value = it }
        }
        val seed = wallet.seed ?: return State.Refused("this device does not hold the wallet's seed").also { _state.value = it }
        val term = operator!!.term

        val transport = GroupIcebergTransport.create(database, localChatRoom, userPublicKey, wallet, term, scope).also { it.start() }
        transport.provision(GroupLightningCoSigner.POOL_WINDOW)
        // Round one is served synchronously from the pool, so the pool has to be there before the LSP can
        // ask for anything: a channel that needs a label the pool lacks is aborted.
        if (!transport.awaitPool(minimumPool, poolWait)) {
            transport.stop()
            return State.Refused("the group has not sent enough round one to sign with yet").also { _state.value = it }
        }
        val onchain = GroupWalletManager.walletFor(database, roomId, chain, wallet.statement.onchainAccount)
            ?: return State.Refused("the onchain wallet it closes to is not open").also { _state.value = it }
        val keyManager = GroupNodeKeyManager(
            delegate = GroupLightningCoSigner.keyManagerOf(ByteVector(seed.seed), chain),
            fundingSigner = RemoteIcebergFundingSigner(PublicKey.fromHex(wallet.statement.icebergKey), transport, SIGNING_DEADLINE),
            basepoints = wallet.basepoints.forChannel,
            finalScriptPubKey = GroupWalletDerivation.derive(onchain.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript,
        )
        check(keyManager.nodeKeys.nodeKey.publicKey.toString() == wallet.statement.nodeId) { "the seed does not produce the statement's node" }

        val handle = peerFactory(NodeConfiguration(keyManager, chain, PublicKey.fromHex(wallet.statement.lspNodeId), transport, Satoshi(wallet.statement.policy.maxLiquidityFeeSat)))
        this.transport = transport
        peer = handle
        _state.value = State.Running(term)
        stayConnected(handle, runCatching { handle.connect(attempt = 1) })
        lastReported = null
        report(term, force = true)
        watchForHandover(term)
        wakeOnRequests()
        logger.i("The group's node ${wallet.statement.nodeId.take(16)} is running, term $term")
        return _state.value
    }

    /** Stops the node and tells the room where its channels were. Safe to call when it is not running. */
    suspend fun stop() {
        watching?.cancel()
        halt()
    }

    /** [stop], minus cancelling the handover watch: the watch calls this itself, and must finish saying so. */
    private suspend fun halt() {
        val running = _state.value as? State.Running ?: return
        val numbers = peer?.commitmentNumbers().orEmpty()
        // First, or the disconnect below would read as a drop and be redialled.
        connecting?.cancel()
        connecting = null
        waking?.cancel()
        waking = null
        peer?.disconnect()
        transport?.stop()
        peer = null
        transport = null
        _state.value = State.Stopped
        GroupLightningOperatorManager.announceStopped(database, localChatRoom, userPublicKey, chain, wallet.statement.wallet, running.term, numbers)
        logger.i("The group's node stopped at the end of term ${running.term}")
    }

    private val walletKey get() = GroupLightningCoSigner.walletKey(localChatRoom.chatRoom.id, chain, wallet.statement.wallet)

    /**
     * [payments] with each incoming one joined to the invoice request it paid, by hash: the
     * operator's word on whose deposit it is (`docs/member-deposits.md`, D6). A payment no invoice
     * of this node's explains -- an offer's, from before offers were off -- joins nothing.
     */
    private suspend fun withRequests(payments: List<WalletWire.PaymentView>): List<WalletWire.PaymentView> = payments.map { payment ->
        val hash = payment.paymentHash?.takeIf { payment.incoming } ?: return@map payment
        val invoice = database.groupLightningRecordsDao().invoicesByHash(walletKey, hash).singleOrNull() ?: return@map payment
        payment.copy(requestId = invoice.requestId, requester = invoice.requester)
    }

    /** Sends the room a snapshot when the channels or payments have changed, or when the last is [reportEvery] old. */
    internal suspend fun report(term: Long, force: Boolean = false) {
        val handle = peer ?: return
        val now = now()
        val current = Triple(handle.channels(), handle.payments(), handle.heldOutputs())
        val bought = purchasesFor(current.first)
        val due = force || current != lastReported || bought != lastPurchases || lastReportedAt.let { it == null || now - it >= reportEvery }
        if (!due) return
        val snapshot = WalletWire.Snapshot(
            wallet = walletKey, term = term, nodeId = wallet.statement.nodeId, sentAt = now.epochSeconds,
            channels = current.first, payments = withRequests(current.second.takeLast(REPORTED_PAYMENTS)),
            assignments = transport?.assignments().orEmpty(),
            heldOutputs = current.third,
            fundingRates = handle.fundingRates(),
            feeratePerByte = handle.feeratePerByte(),
            purchases = bought,
        )
        GroupLightningReports.publishSnapshot(database, localChatRoom, userPublicKey, snapshot)
        lastReported = current
        lastPurchases = bought
        lastReportedAt = now
    }

    /** Makes an invoice for every request in the room that has none yet. */
    internal suspend fun answerInvoiceRequests() = acting.withLock {
        val handle = peer ?: return@withLock
        GroupLightningReports.unanswered(database, localChatRoom.chatRoom.id, walletKey).forEach { pending ->
            val bolt11 = handle.createInvoice(pending.request.amountMsat, pending.request.description)
            GroupLightningReports.answer(database, localChatRoom, userPublicKey, pending, bolt11)
        }
    }

    private val paying = mutableSetOf<String>()

    /**
     * Pays every payment the group has authorised and this node has not tried yet, each once. A payment
     * that fails is not tried again: the group signs a new authorisation for another attempt, and the
     * co-signers' budgets are what stop either from paying twice.
     */
    internal suspend fun payAuthorised() = acting.withLock {
        val handle = peer ?: return@withLock
        GroupLightningAuthorisations.toPay(database, wallet, now()).forEach { pay ->
            if (pay.paymentHash in paying || handle.hasPaid(pay.paymentHash)) return@forEach
            paying += pay.paymentHash
            scope.launch {
                runCatching { handle.pay(pay.invoice, pay.amountMsat) }
                    .onSuccess { logger.i("Paid ${pay.paymentHash.take(16)}: ${if (it.succeeded) "sent" else "not sent"}") }
                    .onFailure { logger.e("Paying ${pay.paymentHash.take(16)} failed", it) }
            }
        }
    }

    /** Every purchase this node took on, a pending one read as locked once its channel is on that output alone. */
    private fun purchasesFor(channels: List<WalletWire.ChannelView>): List<WalletWire.LiquidityPurchase> = purchases.value.values.map { p ->
        val channel = channels.firstOrNull { it.channelId == p.channelId }
        if (p.state == WalletWire.PENDING && channel != null && channel.fundingTxId == p.fundingTxId && channel.liveFundingOutputs == 1) p.copy(state = WalletWire.LOCKED) else p
    }.sortedBy { it.id }

    /**
     * Buys the inbound liquidity the group has authorised, once per authorisation
     * (`docs/group-lightning-liquidity.md`, Phase 6). Not while the channel has a splice that has not
     * locked: eclair refuses another until it does, so the purchase waits, and is tried at the next check
     * after. A purchase that fails is not tried again by itself; the group signs a new one for another
     * attempt, and every co-signer holds the fee to the cap whichever it is.
     */
    internal suspend fun buyAuthorised() = acting.withLock {
        val handle = peer ?: return@withLock
        val channels = handle.channels().associateBy { it.channelId }
        GroupLightningAuthorisations.liquidityPurchases(database, wallet, now()).forEach { buy ->
            if (purchases.value[buy.id]?.state.let { it != null && it != WalletWire.WAITING }) return@forEach
            val channel = channels[buy.channelId] ?: return@forEach
            if (channel.liveFundingOutputs > 1) {
                purchases.update { it + (buy.id to WalletWire.LiquidityPurchase(buy.id, buy.channelId, buy.amountSat, WalletWire.WAITING, fundingTxId = channel.fundingTxId)) }
                return@forEach
            }
            purchases.update { it + (buy.id to WalletWire.LiquidityPurchase(buy.id, buy.channelId, buy.amountSat, WalletWire.PENDING)) }
            scope.launch {
                val outcome = runCatching { handle.buyLiquidity(buy.id, buy.channelId, buy.amountSat, buy.maxFeeSat) }
                    .getOrElse { WalletWire.LiquidityPurchase(buy.id, buy.channelId, buy.amountSat, WalletWire.FAILED, reason = it.message ?: it::class.simpleName) }
                purchases.update { it + (buy.id to outcome) }
                logger.i("Liquidity purchase ${buy.id}: ${outcome.state}${outcome.reason?.let { r -> ", $r" }.orEmpty()}")
            }
        }
    }

    private val closing = mutableSetOf<String>()

    /**
     * Closes every channel the group has authorised closing, once per authorisation this run: mutually to
     * the onchain wallet's first address, which is where the channel's own closing script already
     * points, or by force.
     */
    internal suspend fun closeAuthorised() = acting.withLock {
        val handle = peer ?: return@withLock
        val open = handle.channels().map { it.channelId }.toSet()
        val actions = GroupLightningAuthorisations.channelActions(database, localChatRoom.chatRoom.id, wallet.statement, now())
        if (actions.isEmpty()) return@withLock
        val onchain = GroupWalletManager.walletFor(database, localChatRoom.chatRoom.id, chain, wallet.statement.onchainAccount) ?: return@withLock
        val closeTo = GroupWalletDerivation.derive(onchain.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript
        actions.filter { it.channelId in open }.forEach { action ->
            if (!closing.add("${action.action}:${action.channelId}")) return@forEach
            when (action.action) {
                GroupLightningAuthorisationEvent.FORCE_CLOSE -> handle.forceClose(action.channelId)
                else -> handle.close(action.channelId, closeTo)
            }
            logger.i("${action.action} of ${action.channelId.take(16)}, as the group authorised")
        }
    }

    /**
     * Redials the LSP each time the connection ends, logging why it ended, until the node stops. lightning-kmp
     * does not reconnect by itself, and the identity's own node has Phoenix's connection daemon to do it; this
     * one had nothing, so a single drop left it offline for the rest of the term.
     *
     * A connection that ends inside [STABLE_CONNECTION] counts against the backoff like one that never came
     * up: an LSP that completes the handshake and hangs up on reading our features would otherwise be redialled
     * every few seconds for as long as the term lasts.
     */
    private fun stayConnected(handle: PeerHandle, first: Result<Boolean>) {
        connecting?.cancel()
        connecting = scope.launch {
            var attempt = first
            var since = now()
            var failures = 0
            while (isActive) {
                val reason = attempt.fold({ handle.awaitDisconnect() }, { it.message ?: it::class.simpleName.orEmpty() })
                val connected = attempt.getOrDefault(false)
                val lasted = now() - since
                failures = if (connected && lasted >= STABLE_CONNECTION) 1 else failures + 1
                val wait = reconnectAfter(failures)
                logger.w(
                    if (connected) "The group's node lost the LSP after $lasted: $reason. Reconnecting in $wait"
                    else "The group's node could not reach the LSP: $reason. Trying again in $wait"
                )
                delay(wait)
                since = now()
                attempt = runCatching { handle.connect(attempt = failures + 1) }
            }
        }
    }

    /** Stops the node as soon as the room's newest operator statement names a later term. */
    private fun watchForHandover(term: Long) {
        watching?.cancel()
        watching = scope.launch {
            while (isActive) {
                delay(handoverCheck)
                val current = GroupLightningOperatorManager.current(database, localChatRoom.chatRoom.id, chain, wallet.statement.wallet)
                if (current == null || current.term != term || current.publicKey != userPublicKey) {
                    logger.i("Term $term is over; stopping the group's node")
                    halt()
                    return@launch
                }
                runCatching { report(term) }.onFailure { logger.e("Could not report the wallet to the room", it) }
                act()
            }
        }
    }

    /**
     * Answers invoice requests, and makes the payments and channel actions the group has authorised. Each
     * takes [acting] itself, so the poll, a wakeup and a direct call never pay or answer the same thing twice.
     */
    private suspend fun act() {
        runCatching { answerInvoiceRequests() }.onFailure { logger.e("Could not answer an invoice request", it) }
        runCatching { payAuthorised() }.onFailure { logger.e("Could not make an authorised payment", it) }
        runCatching { closeAuthorised() }.onFailure { logger.e("Could not close an authorised channel", it) }
        if (GroupLightningRollout.LIQUIDITY_ENABLED) runCatching { buyAuthorised() }.onFailure { logger.e("Could not buy authorised liquidity", it) }
    }

    /**
     * Acts the moment the room's invoice requests or authorisations change, rather than at the next
     * [handoverCheck]: on the first mainnet run that poll was most of the wait for an invoice and for a
     * payment to leave once the group had signed it. The poll still runs, for expiries and snapshots.
     */
    private fun wakeOnRequests() {
        waking?.cancel()
        val roomId = localChatRoom.chatRoom.id
        waking = scope.launch {
            combine(
                database.marmotInnerEventDao().observeCountByChatRoomAndKinds(roomId, listOf(GroupLightningEvents.INVOICE_REQUEST)),
                database.groupSignedEventDao().observeCountByChatRoomIdAndKind(roomId, GroupLightningAuthorisationEvent.KIND),
            ) { requests, authorisations -> requests to authorisations }
                .distinctUntilChanged()
                .conflate()
                .collect { act() }
        }
    }

    companion object {
        /** A lightning-kmp payment as the room hears of it. */
        internal fun paymentView(payment: WalletPayment, incoming: Boolean) = WalletWire.PaymentView(
            id = payment.id.toString(),
            incoming = incoming,
            amountMsat = payment.amount.msat,
            feesMsat = payment.fees.msat,
            paymentHash = (payment as? LightningIncomingPayment)?.paymentHash?.toHex() ?: (payment as? LightningOutgoingPayment)?.paymentHash?.toHex(),
            description = ((payment as? LightningOutgoingPayment)?.details as? LightningOutgoingPayment.Details.Normal)?.paymentRequest?.description,
            completedAt = payment.completedAt,
            succeeded = payment.succeededAt != null,
        )

        /** How long a signature may take the group. Phase 0 was to measure what the LSP tolerates; until it is, a minute. */
        val SIGNING_DEADLINE: Duration = 60.seconds
        val HANDOVER_CHECK: Duration = 10.seconds
        /** Pool counters with round one in hand before the node connects: enough to open a channel and pay. */
        const val MINIMUM_POOL = 8
        /** How long a starting node waits for them before refusing; it is tried again at the next refresh. */
        val POOL_WAIT: Duration = 30.seconds

        val FEERATES_REFRESH: Duration = 10.minutes
        val FEERATES_RETRY: Duration = 30.seconds
        private val REGTEST_FEERATES = FeeratePerByte(Satoshi(2)).let { Feerates(it, it, it, it, it) }

        /** How stale a snapshot may grow while nothing changes. */
        val REPORT_EVERY: Duration = 5.minutes
        /** How many of the newest payments a snapshot carries; members keep every one they have seen. */
        const val REPORTED_PAYMENTS = 50

        /** How long a connection to the LSP has to last to reset the backoff. */
        val STABLE_CONNECTION: Duration = 1.minutes
        val RECONNECT_FIRST: Duration = 2.seconds
        val RECONNECT_MAX: Duration = 5.minutes
        /** How long a drop's reason waits for the LSP's init, which can reach the node after the socket closes. */
        val INIT_GRACE: Duration = 1.seconds

        /** [RECONNECT_FIRST], doubling with each attempt in a row that did not hold, up to [RECONNECT_MAX]. */
        fun reconnectDelay(failures: Int): Duration =
            if (failures >= 20) RECONNECT_MAX else (RECONNECT_FIRST * (1 shl (failures - 1).coerceAtLeast(0))).coerceAtMost(RECONNECT_MAX)

        /**
         * Why a connection to the LSP ended, from the socket's reason and the features each side sent. The
         * features come first when they explain it: an LSP that does not offer a feature we require hangs up
         * after the Init exchange, and the socket only says it was closed.
         */
        internal fun dropReason(ours: Features, theirs: Features?, closed: Connection.CLOSED): String {
            val socket = closed.reason?.message ?: "closed by this node"
            if (theirs == null) return "$socket, before the LSP sent its features"
            val missing = ours.activated.filter { (feature, support) -> support == FeatureSupport.Mandatory && !theirs.hasFeature(feature) }.keys.map { it.rfcName }
            val unknownToUs = theirs.unknown.filter { it.bitIndex % 2 == 0 }.map { "bit ${it.bitIndex}" }
            return when {
                missing.isNotEmpty() -> "$socket: the LSP does not offer ${missing.joinToString()}, which this node requires"
                unknownToUs.isNotEmpty() -> "$socket: the LSP requires ${unknownToUs.joinToString()}, which this node does not know"
                else -> socket
            }
        }

        /**
         * [dropReason], with the cause the screen says it by (`docs/lsp-connection-status.md`, D4). lightning-kmp
         * closes with no reason both when we disconnect and when the handshake fails or times out; the loop
         * that asks this has cancelled itself before any disconnect of ours, so no reason here is the handshake.
         */
        internal fun drop(ours: Features, theirs: Features?, closed: Connection.CLOSED): LspDrop {
            val mismatch = theirs != null && (
                ours.activated.any { (feature, support) -> support == FeatureSupport.Mandatory && !theirs.hasFeature(feature) } ||
                    theirs.unknown.any { it.bitIndex % 2 == 0 }
                )
            val cause = when {
                mismatch -> LspDrop.Cause.FEATURE_MISMATCH
                closed.reason is TcpSocket.IOException.ConnectionRefused -> LspDrop.Cause.UNREACHABLE
                closed.reason == null && theirs == null -> LspDrop.Cause.HANDSHAKE
                closed.reason is TcpSocket.IOException.ConnectionClosed -> LspDrop.Cause.CLOSED_BY_LSP
                else -> LspDrop.Cause.OTHER
            }
            return LspDrop(cause, dropReason(ours, theirs, closed))
        }

        /** A dial that threw rather than failed: the Tor check, or something nobody expected. */
        internal fun drop(error: Throwable): LspDrop = LspDrop(
            if (error is TorWithoutOnion) LspDrop.Cause.TOR_WITHOUT_ONION else LspDrop.Cause.OTHER,
            error.message ?: error::class.simpleName.orEmpty(),
        )

        /**
         * Phoenix's connect and handshake timeouts for the [attempt]th try in a row (`AppConnectionsDaemon`),
         * three times as long over Tor.
         */
        fun connectTimeouts(attempt: Int, torIsEnabled: Boolean): Pair<Duration, Duration> = when {
            attempt <= 1 -> 1.seconds to 2.seconds
            attempt <= 3 -> 2.seconds to 4.seconds
            attempt <= 6 -> 4.seconds to 7.seconds
            attempt <= 10 -> 7.seconds to 10.seconds
            else -> 10.seconds to 15.seconds
        }.let { (connect, handshake) -> if (torIsEnabled) connect * 3 to handshake * 3 else connect to handshake }

        /**
         * Builds real lightning-kmp nodes, set up as Phoenix sets up its own (`NodeParamsManager`, `PeerManager`,
         * `AppConnectionsDaemon`): lightning-kmp's default features, zero-conf with the LSP alone, the LSP reached
         * at its onion address while the identity has Tor on, on the chain's Electrum connection, persisted in
         * the databases [databasesFor] opens for the node's id.
         *
         * Simple taproot channels stay optional, as in Phoenix: lightning-kmp asks for that channel type in every
         * channel it opens, and the co-signers rebuild every funding output and commitment as taproot, so the
         * group never signs for another kind. Requiring the feature in `init` gained nothing and cost the
         * connection: ACINQ's LSP does not list it in its own `init`, and a peer must hang up on a required
         * feature it does not offer.
         */
        fun lightningKmpPeers(
            clients: GroupChainClients,
            databasesFor: (nodeId: PublicKey, chain: Chain) -> Databases,
            walletParamsFor: (chain: Chain, torIsEnabled: Boolean) -> WalletParams,
            loggerFactory: LoggerFactory,
            scope: CoroutineScope,
            /** Whether the identity reaches the network over Tor, read as each node starts, as Phoenix reads it as its peer is made. */
            torIsEnabled: suspend () -> Boolean = { false },
            /**
             * Whether the node answers requests for its offers. Never in the app: an offer can be paid by
             * anybody any number of times, so no payment to one is any member's deposit
             * (`docs/member-deposits.md`, D7). The regtest fixture turns it on, since public eclair can't
             * pay the node's BOLT11 route hint, and attributes what arrives with a signed attribution.
             */
            answerOfferRequests: Boolean = false,
        ): suspend (NodeConfiguration) -> PeerHandle = { configuration ->
            val base = NodeParams(configuration.chain, loggerFactory, configuration.keyManager)
            val nodeParams = base.copy(
                answerOfferRequests = answerOfferRequests,
                zeroConfPeers = setOf(configuration.lspNodeId),
                // Phoenix's default policy, except that the most one payment's liquidity may cost is the group's,
                // from the statement it signed, rather than one member's setting.
                liquidityPolicy = MutableStateFlow(
                    LiquidityPolicy.Auto(
                        inboundLiquidityTarget = null,
                        maxAbsoluteFee = configuration.maxLiquidityFee,
                        maxRelativeFeeBasisPoints = 50_00,
                        skipAbsoluteFeeCheck = false,
                        maxAllowedFeeCredit = 0.msat,
                    )
                ),
            )
            val tor = torIsEnabled()
            val walletParams = walletParamsFor(configuration.chain, tor)
            require(walletParams.trampolineNode.id == configuration.lspNodeId) { "the wallet's LSP is not the node it would connect to" }
            val client = clients.clientFor(configuration.chain)
            val watcher = ElectrumWatcher(client, scope, loggerFactory)
            val databases = databasesFor(nodeParams.nodeId, configuration.chain)
            val peer = Peer(nodeParams, walletParams, client, watcher, databases, TcpSocket.Builder(), scope)
            // The peer learns the chain's height only from header notifications, and a shared client that
            // subscribed long ago sends the next one at the next block: until then the node can answer no
            // invoice request and take no payment. So it is told the current height as it starts.
            scope.launch {
                runCatching { peer.currentTipFlow.value = client.startHeaderSubscription().blockHeight }
            }
            // lightning-kmp asks for feerates once, as Electrum connects, which suits a phone app that runs
            // for minutes. The operator's node runs for days, and without feerates it cannot close a channel
            // at all -- a force close waits for them, silently. So it asks again, often while it has none.
            // Regtest has nothing to estimate from; there it takes a flat rate.
            scope.launch {
                while (isActive) {
                    val feerates = runCatching { client.getFeerates() }.getOrNull()
                        ?: REGTEST_FEERATES.takeIf { configuration.chain == Chain.Regtest }
                    feerates?.let { peer.onChainFeeratesFlow.value = OnChainFeerates(it) }
                    delay(if (feerates == null) FEERATES_RETRY else FEERATES_REFRESH)
                }
            }
            val payments = mutableListOf<WalletWire.PaymentView>()
            // The LSP's features as it sent them on the current connection, for saying why one ended.
            val lspInit = MutableStateFlow<Init?>(null)
            scope.launch {
                nodeParams.nodeEvents.collect { event ->
                    when (event) {
                        is PeerConnected -> lspInit.value = event.theirInit
                        is PaymentEvents.PaymentReceived -> payments += paymentView(event.payment, incoming = true)
                        is PaymentEvents.PaymentSent -> payments += paymentView(event.payment, incoming = false)
                        else -> {}
                    }
                }
            }
            object : PeerHandle {
                override suspend fun connect(attempt: Int): Boolean {
                    // As Phoenix refuses to: over Tor, a clearnet address would leave Tor for the one connection
                    // that says the most about the group's money.
                    if (tor && !walletParams.trampolineNode.isOnion) throw TorWithoutOnion(configuration.chain)
                    lspInit.value = null
                    val (connectTimeout, handshakeTimeout) = connectTimeouts(attempt, tor)
                    return peer.connect(connectTimeout = connectTimeout, handshakeTimeout = handshakeTimeout)
                }
                override fun disconnect() = peer.disconnect()
                override suspend fun awaitDisconnect(): String {
                    val closed = peer.connectionState.first { it is Connection.CLOSED } as Connection.CLOSED
                    // An LSP that hangs up on our features does so milliseconds after its own init, and the
                    // socket reads as closed before the peer's event for that init has reached us: wait for it.
                    val theirs = withTimeoutOrNull(INIT_GRACE) { lspInit.filterNotNull().first() }
                    return dropReason(nodeParams.features.initFeatures(), theirs?.features, closed)
                }
                override fun commitmentNumbers(): Map<String, Long> = peer.channelsFlow.value.mapNotNull { (id, state) ->
                    (state as? ChannelStateWithCommitments)?.let { id.toHex() to it.commitments.localCommitIndex }
                }.toMap()
                override fun channels(): List<WalletWire.ChannelView> = peer.channelsFlow.value.mapNotNull { (id, state) ->
                    (state as? ChannelStateWithCommitments)?.commitments?.latest?.let { latest ->
                        WalletWire.ChannelView(
                            channelId = id.toHex(),
                            state = state::class.simpleName ?: "",
                            capacitySat = latest.fundingAmount.sat,
                            oursMsat = latest.localCommit.spec.toLocal.msat,
                            theirsMsat = latest.localCommit.spec.toRemote.msat,
                            fundingTxId = latest.fundingTxId.toString(),
                            fundingIndex = latest.fundingInput.index,
                            commitIndex = latest.localCommit.index,
                            liveFundingOutputs = state.commitments.active.size,
                        )
                    }
                }.sortedBy { it.channelId }
                override fun payments(): List<WalletWire.PaymentView> = payments.toList()
                override suspend fun createInvoice(amountMsat: Long?, description: String): String =
                    peer.createInvoice(Lightning.randomBytes32(), amountMsat?.let(::MilliSatoshi), Either.Left(description)).write()
                override suspend fun hasPaid(paymentHash: String): Boolean =
                    databases.payments.listLightningOutgoingPayments(ByteVector32(paymentHash)).isNotEmpty()
                override suspend fun close(channelId: String, scriptPubKey: ByteVector) {
                    peer.mutualClose(ByteVector32(channelId), scriptPubKey, peer.onChainFeeratesFlow.value?.mutualCloseFeerate ?: FeeratePerKw(FeeratePerByte(Satoshi(2))))
                }
                override fun heldOutputs(): List<GroupSweep.Input> = peer.channelsFlow.value.values.mapNotNull { state ->
                    when (state) {
                        is Closing -> state
                        is Closed -> state.state
                        else -> null
                    }
                }.flatMap { closing ->
                    val latest = closing.commitments.latest
                    val keys = configuration.keyManager.channelKeys(closing.commitments.channelParams.localParams.fundingKeyPath)
                    buildList {
                        closing.localCommitPublished?.let { published ->
                            GroupHeldOutputs.inLocalCommit(keys, latest, published.commitTx)?.let(::add)
                            // Our HTLC transactions, once they spend our commitment: their delayed outputs are the group's too.
                            published.irrevocablySpent.values.filter { tx -> tx.txIn.any { it.outPoint.txid == published.commitTx.txid } }
                                .forEach { htlcTx -> GroupHeldOutputs.inHtlcTx(keys, latest, htlcTx)?.let(::add) }
                        }
                        closing.remoteCommitPublished?.let { GroupHeldOutputs.inRemoteCommit(keys, latest, latest.remoteCommit.remotePerCommitmentPoint, it.commitTx)?.let(::add) }
                        closing.nextRemoteCommitPublished?.let { published ->
                            latest.nextRemoteCommit?.let { next -> GroupHeldOutputs.inRemoteCommit(keys, latest, next.remotePerCommitmentPoint, published.commitTx)?.let(::add) }
                        }
                    }
                }.map(GroupSweep::inputOf).distinctBy { it.txid to it.index }
                override fun fundingRates(): WalletWire.FundingRates? = peer.remoteFundingRates.value?.let { rates ->
                    WalletWire.FundingRates(
                        rates.fundingRates.map { WalletWire.FundingRate(it.minAmount.sat, it.maxAmount.sat, it.fundingWeight, it.feeProportional, it.feeBase.sat, it.channelCreationFee.sat) },
                        rates.paymentTypes.map(CoSigningWire::paymentTypeName),
                    )
                }
                override fun feeratePerByte(): Long? = peer.onChainFeeratesFlow.value?.fundingFeerate?.let { FeeratePerByte(it).feerate.sat }
                override suspend fun buyLiquidity(id: String, channelId: String, amountSat: Long, maxFeeSat: Long): WalletWire.LiquidityPurchase {
                    fun failed(reason: String) = WalletWire.LiquidityPurchase(id, channelId, amountSat, WalletWire.FAILED, reason = reason)
                    val amount = Satoshi(amountSat)
                    val rate = peer.remoteFundingRates.value?.findRate(amount) ?: return failed("the LSP sells no liquidity in that amount")
                    val feerate = peer.onChainFeeratesFlow.value?.fundingFeerate ?: return failed("the node has no feerate yet")
                    // As Phoenix does: estimate, then buy at the feerate the estimate raised to, not the target.
                    val (actual, fees) = peer.estimateFeeForInboundLiquidity(amount, feerate, rate) ?: return failed("the channel is not ready")
                    if (fees.total.sat > maxFeeSat) return failed("it would cost ${fees.total.sat} sats, over the $maxFeeSat the group agreed to")
                    return when (val response = peer.requestInboundLiquidity(amount, actual, rate)) {
                        null -> failed("the channel is not ready")
                        is ChannelFundingResponse.Success -> WalletWire.LiquidityPurchase(id, channelId, amountSat, WalletWire.PENDING, response.fundingTxId.toString(), fees.total.sat).also {
                            payments += WalletWire.PaymentView(
                                id = "liquidity:${response.fundingTxId}", incoming = false, amountMsat = fees.total.sat * 1000, feesMsat = fees.total.sat * 1000,
                                completedAt = Clock.System.now().toEpochMilliseconds(), succeeded = true, kind = WalletWire.LIQUIDITY, liquiditySat = amountSat,
                            )
                        }
                        is ChannelFundingResponse.Failure -> failed(response::class.simpleName ?: "the splice failed")
                    }
                }
                override suspend fun forceClose(channelId: String) {
                    peer.send(WrappedChannelCommand(ByteVector32(channelId), ChannelCommand.Close.ForceClose))
                }
                override suspend fun pay(bolt11: String, amountMsat: Long): WalletWire.PaymentView {
                    val invoice = Bolt11Invoice.read(bolt11).get()
                    return when (val result = peer.payInvoice(MilliSatoshi(amountMsat), invoice)) {
                        // A payment that went is reported through the node's events, like every other.
                        is PaymentSent -> paymentView(result.payment, incoming = false)
                        else -> WalletWire.PaymentView(
                            id = result.request.paymentId.toString(), incoming = false, amountMsat = amountMsat, feesMsat = 0,
                            paymentHash = invoice.paymentHash.toHex(), description = invoice.description,
                            completedAt = Clock.System.now().toEpochMilliseconds(), succeeded = false,
                        ).also { payments += it }
                    }
                }
            }
        }
    }
}
