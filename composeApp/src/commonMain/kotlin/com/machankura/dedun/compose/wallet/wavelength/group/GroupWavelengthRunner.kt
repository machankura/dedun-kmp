package com.machankura.dedun.compose.wallet.wavelength.group

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupArkCoSigner
import com.machankura.dedun.compose.managers.GroupArkTransport
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GroupWalletManager
import com.machankura.dedun.compose.repository.GroupWavelengthExitPlan
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Script
import com.machankura.dedun.compose.managers.GroupLightningLines
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.managers.GroupWavelengthDeposits
import com.machankura.dedun.compose.managers.GroupWavelengthPayouts
import com.machankura.dedun.compose.managers.GroupWavelengthReports
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthStopped
import com.machankura.dedun.compose.managers.GroupWavelengthDaemon
import com.machankura.dedun.compose.managers.GroupWavelengthDaemonStatus
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.GroupWavelengthDaemonSource
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import engineering.lightning.wavelength.RuntimeConfig
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The one daemon in the process, which a group's wallet and the person's own take turns with (D13). */
interface GroupDaemonSlot {
    /** Whether the person is in their own wallet's screen, which the group's gives way to. */
    val personalWanted: StateFlow<Boolean>

    /** Stops the person's own daemon and keeps it stopped, or false if they're using it. */
    suspend fun acquire(group: String): Boolean

    /** Hands the daemon back, which reopens the person's own. */
    suspend fun release()
}

/**
 * Runs a group's Wavelength wallet on this device while the group has elected it: `docs/group-wavelength-ui.md`, Phase 3. The way
 * [com.machankura.dedun.compose.wallet.lightning.GroupLightningNodes] does for its nodes, it looks every [CHECK] at what this device
 * has been elected to run. It starts a daemon once the group's devices are ready to sign, and stops it as soon as a later term begins.
 *
 * One daemon at a time, whichever it finds first, since the SDK holds one per process. It holds no key that can move money, and
 * every signature it asks for is the group's to refuse: what this adds is liveness, which is all an operator is trusted for.
 */
class GroupWavelengthRunner(
    private val database: DedunDatabase,
    private val userPublicKey: HexKey,
    private val scope: CoroutineScope,
    private val slot: GroupDaemonSlot,
    private val daemon: GroupWavelengthDaemon,
    private val enabled: Boolean = GroupWavelengthRollout.ENABLED,
    private val chains: List<Chain> = GroupWavelengthRollout.NETWORKS,
    /** Called with the group's name while a daemon runs, and null once none does. */
    private val onHolding: (String?) -> Unit = {},
    private val poolWait: Duration = POOL_WAIT,
    private val now: () -> Instant = { Clock.System.now() },
    /** When this device last heard from a room. */
    private val lastHeard: suspend (chatRoomId: String) -> Instant? = { database.marmotGroupEventDao().newestSavedAt(it) },
) : GroupWavelengthDaemonSource {
    private val logger = Logger.withTag("GroupWavelengthRunner")
    private val me = userPublicKey.lowercase()
    private val mutex = Mutex()
    private val statuses = MutableStateFlow<Map<String, GroupWavelengthDaemonStatus>>(emptyMap())
    private var job: Job? = null

    private class Elected(val room: LocalChatRoom, val wallet: GroupWavelengthWalletManager.Wallet, val term: Long) {
        val chain: Chain get() = wallet.statement.chain
        val number: Long get() = wallet.statement.wallet
        val name: String get() = room.chatRoom.subject ?: room.chatRoom.id.take(8)
    }

    /** A daemon this device has running. [plainTo] and [treeTo] are how far round one has been asked for. */
    class Running internal constructor(
        val chatRoomId: String,
        val chain: Chain,
        val wallet: GroupWavelengthWalletManager.Wallet,
        val term: Long,
        val transport: GroupArkTransport,
        val config: RuntimeConfig,
        internal val room: LocalChatRoom,
        internal var plainTo: Long,
        internal var treeTo: Long,
    ) {
        internal var lastReport: GroupWavelengthSnapshot? = null
        internal val attempted = HashSet<String>()
        internal val leaving = HashSet<String>()
    }

    private var running: Running? = null

    /** The daemon this device has running, for whatever else acts through it. */
    val current: Running? get() = running

    override fun observe(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupWavelengthDaemonStatus> =
        statuses.map { it[key(chatRoomId, chain, wallet)] ?: GroupWavelengthDaemonStatus.NotOnThisDevice }.distinctUntilChanged()

    private fun key(chatRoomId: String, chain: Chain, wallet: Long) = "$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}:$wallet"

    private fun say(elected: Elected, status: GroupWavelengthDaemonStatus) =
        statuses.update { it + (key(elected.room.chatRoom.id, elected.chain, elected.number) to status) }

    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("Could not look after the group's wallets", e)
                }
                // Sooner if the person opens or leaves their own wallet: they're waiting on it.
                withTimeoutOrNull(CHECK) { slot.personalWanted.drop(1).first() }
            }
        }
    }

    /** Stops the loop and any daemon: the profile is going away. */
    suspend fun stop() {
        job?.cancel()
        mutex.withLock { halt() }
    }

    /** One look at what this device should be running, and one step towards it. */
    suspend fun refresh() = mutex.withLock {
        if (!enabled) return@withLock
        val elected = elected()
        statuses.value = statuses.value.filterKeys { k -> elected.any { key(it.room.chatRoom.id, it.chain, it.number) == k } }

        running?.let { live ->
            val still = elected.firstOrNull { it.room.chatRoom.id == live.chatRoomId && it.chain == live.chain && it.number == live.wallet.statement.wallet && it.term == live.term }
            when {
                still == null -> halt(announce = true)
                slot.personalWanted.value -> {
                    halt()
                    say(still, GroupWavelengthDaemonStatus.PausedForPersonal)
                }
                !daemon.isRunning() -> {
                    logger.w("The daemon for ${still.name}'s wallet has stopped; starting it again")
                    halt()
                }
                else -> {
                    topUp(live)
                    act(live)
                    report(live)
                }
            }
        }

        val runningNow = running
        if (runningNow != null) {
            elected.filter { it.room.chatRoom.id != runningNow.chatRoomId || it.chain != runningNow.chain || it.number != runningNow.wallet.statement.wallet }
                .forEach { say(it, GroupWavelengthDaemonStatus.BusyWithAnother(elected.first { e -> e.room.chatRoom.id == runningNow.chatRoomId }.name)) }
            return@withLock
        }
        val next = elected.firstOrNull() ?: return@withLock
        elected.drop(1).forEach { say(it, GroupWavelengthDaemonStatus.BusyWithAnother(next.name)) }
        open(next)
    }

    /** Every wallet the room's newest statement says this device runs. */
    private suspend fun elected(): List<Elected> {
        val rooms = database.icebergKeySessionDao().getCompletedSessions()
            .filter { it.userPublicKey.lowercase() == me && it.purpose == IcebergKeySession.PURPOSE_WAVELENGTH }
            .map { it.chatRoomId }.toSet()
        return rooms.flatMap { roomId ->
            val room = database.chatRoomDao().findChatRoomById(roomId) ?: return@flatMap emptyList()
            chains.flatMap { chain ->
                GroupWavelengthWalletManager.wallets(database, roomId, chain).mapNotNull { wallet ->
                    val operator = GroupWavelengthOperatorManager.current(database, roomId, chain, wallet.statement.wallet)
                    if (operator?.publicKey == me) Elected(room, wallet, operator.term) else null
                }
            }
        }
    }

    private suspend fun open(elected: Elected) {
        val roomId = elected.room.chatRoom.id
        val heard = lastHeard(roomId)
        if (!GroupWavelengthOperatorManager.mayRunDaemon(database, roomId, elected.chain, userPublicKey, heard, now(), elected.number)) {
            say(elected, GroupWavelengthDaemonStatus.CatchingUp)
            return
        }
        if (!slot.acquire(elected.name)) {
            say(elected, GroupWavelengthDaemonStatus.PausedForPersonal)
            return
        }
        val statement = elected.wallet.statement
        val walletKey = GroupArkCoSigner.walletKey(roomId, elected.chain, elected.number)
        val coSigning = database.icebergCoSigningDao()
        val floor = database.groupArkDao().floor(walletKey)
        val plainFrom = maxOf((coSigning.highestAssignment(walletKey) ?: -1L) + 1, floor?.floor ?: 0)
        val treeFrom = maxOf((coSigning.highestAssignment("$walletKey:tree") ?: -1L) + 1, floor?.treeFloor ?: 0)
        val plainTo = plainFrom + PLAIN_WINDOW
        val treeTo = treeFrom + TREE_WINDOW
        val recovering = !daemon.hasOpened(statement)

        say(elected, GroupWavelengthDaemonStatus.WaitingForSigners)
        val outcome = try {
            daemon.open(database, elected.room, userPublicKey, elected.wallet, if (recovering) recoveryWindowFor(walletKey) else null, heard, now()) { transport ->
                transport.provision(plainTo, treeTo)
                transport.askFloors()
                val ready = transport.awaitPool(plainFrom, treeFrom, poolWait)
                if (ready) say(elected, if (recovering) GroupWavelengthDaemonStatus.Recovering else GroupWavelengthDaemonStatus.Opening)
                ready
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Could not open ${elected.name}'s wallet", e)
            runCatching { daemon.stop() }
            slot.release()
            say(elected, GroupWavelengthDaemonStatus.Failed)
            return
        }
        when (outcome) {
            is GroupWavelengthDaemon.Outcome.Running -> {
                running = Running(roomId, elected.chain, elected.wallet, elected.term, outcome.transport, outcome.config, elected.room, plainTo, treeTo)
                say(elected, GroupWavelengthDaemonStatus.Running(elected.term))
                onHolding(elected.name)
                logger.i { "Running ${elected.name}'s wallet on ${elected.chain.name}, term ${elected.term}" }
                running?.let { report(it) }
            }
            is GroupWavelengthDaemon.Outcome.Refused -> {
                slot.release()
                say(elected, GroupWavelengthDaemonStatus.Refused(outcome.reason))
            }
        }
    }

    /** Keeps round one ahead of what the daemon has used, or a signature it asks for fails where it can't be retried. */
    private suspend fun topUp(live: Running) {
        val walletKey = GroupArkCoSigner.walletKey(live.chatRoomId, live.chain, live.wallet.statement.wallet)
        val coSigning = database.icebergCoSigningDao()
        val plainUsed = coSigning.highestAssignment(walletKey) ?: -1L
        val treeUsed = coSigning.highestAssignment("$walletKey:tree") ?: -1L
        val plainLow = plainUsed + PLAIN_LOW > live.plainTo
        val treeLow = treeUsed + TREE_LOW > live.treeTo
        if (!plainLow && !treeLow) return
        if (plainLow) live.plainTo = plainUsed + PLAIN_WINDOW
        if (treeLow) live.treeTo = treeUsed + TREE_WINDOW
        live.transport.provision(live.plainTo, live.treeTo)
    }

    /** What the group has asked of whoever runs the wallet. Each failure is its own, and none reaches the daemon. */
    private suspend fun act(live: Running) {
        runCatching { GroupWavelengthDeposits.answerOutstanding(database, live.room, userPublicKey, live.wallet, live.term) { daemon.newBoardingAddress() } }
            .onFailure { logger.w(it) { "Couldn't answer a request for a deposit address" } }
        runCatching { payApproved(live) }.onFailure { logger.w(it) { "Couldn't look at what the group approved" } }
        runCatching { leaveIfAsked(live) }.onFailure { logger.w(it) { "Couldn't look at whether to leave" } }
    }

    /**
     * Leaves for the group's onchain wallet: when a quorum has asked, and by itself when a coin has got this close to its deadline
     * with no renewal (D11), which is the one thing here that moves money with nobody asking. It only ever pays the group's own wallet,
     * and a device checks that before signing. Each coin once a run: a failed exit is tried again by whoever runs this next.
     */
    private suspend fun leaveIfAsked(live: Running) {
        val number = live.wallet.statement.wallet
        val nowSeconds = now().epochSeconds
        val asked = GroupWavelengthPayouts.signedExits(database, live.chatRoomId, live.chain, number).any { it.expiresAt > nowSeconds }
        val walletKey = GroupArkCoSigner.walletKey(live.chatRoomId, live.chain, number)
        val tip = live.lastReport?.tip
        val earliest = GroupWavelengthLedger.of(database.groupArkDao().vtxos(walletKey)).earliestExpiryHeight
        val free = GroupWavelengthTerms.of(live.chain)?.freeRefreshWindowBlocks ?: 0
        val urgent = tip != null && WalletExpiry.ofHeight(earliest, tip, free).let { it is WalletExpiry.Urgent || it == WalletExpiry.Expired }
        if (!asked && !urgent) return
        val account = GroupWalletManager.walletFor(database, live.chatRoomId, live.chain, live.wallet.statement.onchainAccount)?.account ?: return
        val script = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript
        val destination = Bitcoin.addressFromPublicKeyScript(live.chain.chainHash, Script.parse(script)).right ?: return
        val coins = daemon.liveCoins().filter { live.leaving.add(it) }
        if (coins.isEmpty()) return
        logger.i { "Leaving ${live.chatRoomId.take(8)}'s wallet: ${if (asked) "the group asked" else "its coins are close to their deadline"}" }
        coins.forEach { outpoint ->
            scope.launch { runCatching { daemon.leave(outpoint, destination) }.onFailure { logger.w(it) { "Leaving with ${outpoint.take(12)} didn't go" } } }
        }
    }

    /** What leaving by itself would involve, on the device that runs [wallet], or null on any other. */
    override suspend fun exitPlan(chatRoomId: String, chain: Chain, wallet: Long): GroupWavelengthExitPlan? = mutex.withLock {
        val live = running?.takeIf { it.chatRoomId == chatRoomId && it.chain == chain && it.wallet.statement.wallet == wallet } ?: return@withLock null
        val coins = daemon.liveCoins()
        val plan = daemon.exitPlan(coins)
        GroupWavelengthExitPlan(coins, plan.canStart, plan.totalFundingShortfallSat, daemon.exitSummary().exits.map { GroupWavelengthExitPlan.Stage(it.outpoint, it.status?.wire.orEmpty(), it.vtxoAmountSat, it.estTotalFeeSat, it.estNetRecoveredSat) })
    }

    /** Starts leaving without the Ark operator, for every coin. */
    override suspend fun forceExit(chatRoomId: String, chain: Chain, wallet: Long) {
        val live = running?.takeIf { it.chatRoomId == chatRoomId && it.chain == chain && it.wallet.statement.wallet == wallet } ?: return
        require(GroupWavelengthRollout.offersUnilateralExit(live.chain)) { "A unilateral exit isn't offered on ${live.chain.name}" }
        daemon.liveCoins().forEach { daemon.forceExit(it) }
    }

    /**
     * Pays what a quorum approved, each once. Only that: an unapproved attempt holds the coin for the transport's 50 s while every device
     * refuses it, and a stale swap can block a real one (`docs/group-wavelength-wallet.md`, "What Lightning found"). It runs apart from
     * this loop, since a payment takes the group's signatures and may take a while. A failed one needs a new invoice, so it isn't retried.
     */
    private suspend fun payApproved(live: Running) {
        val walletKey = GroupArkCoSigner.walletKey(live.chatRoomId, live.chain, live.wallet.statement.wallet)
        val alreadyGone = database.groupArkDao().payments(walletKey).map { it.paymentHash.lowercase() }.toSet()
        val reported = live.lastReport?.activity?.map { it.paymentHash.lowercase() }?.toSet().orEmpty()
        val nowSeconds = now().epochSeconds
        GroupWavelengthPayouts.signed(database, live.chatRoomId, live.chain)
            .filter { it.wallet == live.wallet.statement.wallet && it.expiresAt > nowSeconds }
            .forEach { payment ->
                val hash = payment.paymentHash?.toString()?.lowercase() ?: return@forEach
                if (hash in alreadyGone || hash in reported || !live.attempted.add(hash)) return@forEach
                scope.launch {
                    runCatching { daemon.pay(payment.invoice, payment.maxFeeSat) }
                        .onFailure { logger.w(it) { "The payment for ${hash.take(12)} didn't go" } }
                }
            }
    }

    /** Each coin this device knows of came from a key the wallet has used, so a scan that wide finds them, and some margin finds the rest. */
    private suspend fun recoveryWindowFor(walletKey: String): Long =
        maxOf(RECOVERY_WINDOW, database.groupArkDao().vtxos(walletKey).size + RECOVERY_WINDOW)

    /** Tells the group what the daemon says it holds, when that's changed or it's been a while. Never lets a failure near the daemon. */
    private suspend fun report(live: Running) {
        val walletKey = GroupArkCoSigner.walletKey(live.chatRoomId, live.chain, live.wallet.statement.wallet)
        val snapshot = runCatching { daemon.snapshotOf(walletKey, live.term, now()) }
            .onFailure { logger.i { "Couldn't read the wallet to report it: ${it.message}" } }
            .getOrNull() ?: return
        val last = live.lastReport
        if (last != null && last.sameAs(snapshot) && snapshot.sentAt - last.sentAt < REPORT_AT_LEAST.inWholeSeconds) return
        live.lastReport = snapshot
        runCatching {
            GroupLightningWalletManager.queue(database, live.room, userPublicKey, GroupWavelengthEvents.SNAPSHOT, CommonJson.encodeToString(GroupWavelengthSnapshot.serializer(), snapshot))
            GroupWavelengthReports.receive(database, live.chatRoomId, userPublicKey, snapshot)
        }.onFailure { logger.w(it) { "Couldn't report the wallet to the room" } }
    }

    private suspend fun halt(announce: Boolean = false) {
        val live = running ?: return
        running = null
        if (announce) {
            val walletKey = GroupArkCoSigner.walletKey(live.chatRoomId, live.chain, live.wallet.statement.wallet)
            val inFlight = live.lastReport?.activity?.count { it.status == IN_FLIGHT } ?: 0
            val stopped = GroupWavelengthStopped(walletKey, live.term, runCatching { daemon.exitsUnderWay() }.getOrDefault(0), inFlight)
            runCatching {
                val queued = GroupLightningWalletManager.queue(database, live.room, userPublicKey, GroupWavelengthEvents.STOPPED, CommonJson.encodeToString(GroupWavelengthStopped.serializer(), stopped))
                GroupLightningLines.write(database, live.chatRoomId, queued.id, ChatMessage.TYPE_WAVELENGTH_OPERATOR_STOPPED, userPublicKey, GroupWavelengthReports.linesOfStopped(stopped), queued.createdAt)
            }.onFailure { logger.w(it) { "Couldn't tell the room this device stopped" } }
        }
        runCatching { daemon.stop() }.onFailure { logger.w(it) { "Stopping the group's daemon" } }
        slot.release()
        onHolding(null)
    }

    companion object {
        val CHECK: Duration = 1.minutes

        /** How long a daemon's start waits for the group's devices to publish round one before trying again. */
        val POOL_WAIT: Duration = 2.minutes

        /** How many keys of each family a first open scans. About five group signatures a key, so as few as a young wallet can use (D14). */
        const val RECOVERY_WINDOW = 8L

        /** How far ahead round one is asked for, and how near that it can get before asking for more. A round wants several. */
        const val PLAIN_WINDOW = 127L
        const val PLAIN_LOW = 64L
        const val TREE_WINDOW = 7L
        const val TREE_LOW = 2L

        /** How often the group hears from a wallet that hasn't changed, which is also how it knows the operator is there. */
        val REPORT_AT_LEAST: Duration = 30.minutes

        /** The daemon's word for an entry still on its way. */
        private const val IN_FLIGHT = "pending"
    }
}
