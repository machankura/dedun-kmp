package com.machankura.dedun.compose.wallet.wavelength

import co.touchlab.kermit.Logger
import engineering.lightning.wavelength.DescriptorWalletRequest
import engineering.lightning.wavelength.EmbeddedWavelengthClient
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.engine.WalletEngine
import engineering.lightning.wavelength.model.DescriptorWalletOpenResult
import fr.acinq.phoenix.data.WalletId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import com.machankura.dedun.compose.wallet.wavelength.group.GroupDaemonSlot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * What the active profile asks of its wallet. [words] is null for a profile with none.
 * Never logged: [toString] leaves the words out.
 */
class WalletProfile(
    val id: WalletId,
    val words: List<String>?,
    val torEnabled: Boolean,
    /** An nsec profile, whose words are made when it turns the wallet on (D13). */
    val canCreateSeed: Boolean = false,
) {
    override fun toString(): String = "WalletProfile(id=$id, words=${if (words == null) "none" else "***"}, tor=$torEnabled, canCreateSeed=$canCreateSeed)"
}

/** How a first open's recovery scan went. All zeros is a new wallet, and says nothing. */
data class WalletRecovery(val coins: Long, val boardings: Long, val receives: Long) {
    val found: Boolean get() = coins > 0 || boardings > 0 || receives > 0
}

/** Where the active profile's wallet is. */
sealed interface WalletState {
    /** No profile is active. */
    data object None : WalletState

    data class Unavailable(val reason: WalletUnavailability) : WalletState

    data object Opening : WalletState

    /** A first open of this directory, whose recovery scan runs inside the open (D12). */
    data object Recovering : WalletState

    data class Open(
        val engine: WalletEngine,
        val network: Network,
        val recovery: WalletRecovery?,
        /** Every proof of payment this wallet has seen, by payment hash. */
        val preimages: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
    ) : WalletState

    /** It could not open, for a reason a retry might fix: no connection, or the operator. */
    data class Failed(val error: WavelengthException) : WalletState

    /** This device is running [group]'s wallet, which has the one daemon: `docs/group-wavelength-wallet.md`, D13. */
    data class RunByGroup(val group: String) : WalletState

    /**
     * The directory belongs to another identity than these words give. Never a retry and never
     * a wipe: it means something is badly wrong, and its files may hold someone's money.
     */
    data object WrongIdentity : WalletState
}

/**
 * The active profile's wallet, one per process -- `docs/wavelength-wallet.md`, D8.
 *
 * The daemon is a singleton in the process, so every open and close goes through one [Mutex],
 * in the order they were asked for: a switch stops the last profile's daemon before it opens the
 * next, as `IdentityElectrum` does for its clients.
 *
 * The daemon also can't see the app's lifecycle, and left running across a suspend it keeps dead
 * connections, so it is stopped a little after the app leaves the foreground and reopened when it
 * returns (D10). The engine is rebuilt each time, which is why the screens read [state] and not an
 * engine they were handed once. The grace period is for the app's own comings and goings: a chat
 * app is left and returned to all day, and a reopen costs seconds.
 *
 * The opens and closes run under [NonCancellable]: a daemon half-opened when the caller went away
 * would hold the directory with nothing to stop it.
 */
class IdentityWavelength(
    private val client: EmbeddedWavelengthClient?,
    private val files: WalletFiles,
    private val scope: CoroutineScope,
    private val network: Network = WavelengthWalletRollout.NETWORK,
    private val offered: Boolean = WavelengthWalletRollout.OFFERED,
    private val backgroundGrace: Duration = 1.minutes,
    /** Makes and stores words for a profile with none: `WalletSeedStore.create` (D13). */
    private val createSeed: (WalletId) -> List<String> = { error("no seed store") },
    private val configFor: (Network, String) -> RuntimeConfig = WavelengthWalletRollout::configFor,
) : GroupDaemonSlot {
    private val logger = Logger.withTag("IdentityWavelength")
    private val mutex = Mutex()

    private val _state = MutableStateFlow<WalletState>(WalletState.None)
    val state: StateFlow<WalletState> = _state.asStateFlow()

    private var profile: WalletProfile? = null
    private var engine: WalletEngine? = null
    private var engineScope: CoroutineScope? = null
    private var foreground = true
    private var leaving: Job? = null

    /** The group whose wallet has the daemon, or null when it's the profile's own: nothing here touches the daemon while it's set. */
    private var heldBy: String? = null

    private val _personalWanted = MutableStateFlow(false)
    override val personalWanted: StateFlow<Boolean> = _personalWanted.asStateFlow()

    /** Bumped by anything the app asks for, so the background renewal gives way at once. */
    private val asked = MutableStateFlow(0)

    override suspend fun acquire(group: String): Boolean = mutex.withLock {
        if (_personalWanted.value) return@withLock false
        if (heldBy == null) stopDaemon()
        heldBy = group
        _state.value = WalletState.RunByGroup(group)
        true
    }

    override suspend fun release() = mutex.withLock {
        if (heldBy == null) return@withLock
        heldBy = null
        val current = profile
        when {
            current == null -> _state.value = WalletState.None
            foreground -> switchTo(current, force = true)
            else -> _state.value = WalletState.Opening
        }
    }

    /**
     * The person is in their own wallet's screen, or has left it: while they are, the group's gives way (D13). The group's runner sees
     * this and hands the daemon back, and takes it again once they're done.
     */
    fun wantPersonal(wanted: Boolean) {
        asked.value++
        _personalWanted.value = wanted
    }

    /** Makes [next] the active profile: stops the last one's daemon, then opens this one's, if it can. */
    fun activate(next: WalletProfile?) {
        asked.value++
        scope.launch { mutex.withLock { switchTo(next) } }
    }

    /** The first-open sheet was accepted for the active profile: record it, and open. */
    fun consent() {
        asked.value++
        scope.launch {
            mutex.withLock {
                var current = profile ?: return@withLock
                if (current.words == null && current.canCreateSeed) {
                    current = WalletProfile(current.id, createSeed(current.id), current.torEnabled, canCreateSeed = true)
                }
                files.recordConsent(current.id)
                switchTo(current, force = true)
            }
        }
    }

    /** Tries the active profile's open again, after a failure. */
    fun retry() {
        asked.value++
        scope.launch { mutex.withLock { profile?.let { switchTo(it, force = true) } } }
    }

    /**
     * Opens the wallet again with a recovery scan as wide as [WavelengthWalletRollout.FULL_RECOVERY_WINDOW],
     * for someone restoring a wallet that was used for a while. Slow: it takes minutes, and the wallet
     * is not usable until it is done.
     */
    fun lookForMoreCoins() {
        asked.value++
        scope.launch {
            mutex.withLock {
                val current = profile ?: return@withLock
                if (heldBy != null || setupFor(current) !is WalletSetup.Ready) return@withLock
                stopDaemon()
                open(current, recoveryWindow = WavelengthWalletRollout.FULL_RECOVERY_WINDOW)
            }
        }
    }

    /** The app came to the foreground: cancels a pending stop, or reopens what was stopped. */
    fun enteredForeground() {
        asked.value++
        scope.launch {
            leaving?.cancel()
            leaving = null
            mutex.withLock {
                foreground = true
                val current = profile ?: return@withLock
                if (engine == null) switchTo(current, force = true)
            }
        }
    }

    /** The app left the foreground: stops the daemon, unless it comes back within the grace period. */
    fun enteredBackground() {
        leaving?.cancel()
        leaving = scope.launch {
            delay(backgroundGrace)
            mutex.withLock {
                foreground = false
                if (heldBy != null) return@withLock
                stopDaemon()
                if (profile != null) _state.value = WalletState.Opening
            }
        }
    }

    /** Stops whatever is running, and forgets the profile. */
    suspend fun stop() = mutex.withLock {
        profile = null
        if (heldBy != null) return@withLock
        stopDaemon()
        _state.value = WalletState.None
    }

    /**
     * The background renewal -- Phase 8. Opens [background]'s wallet, gives the daemon's own
     * maintenance up to [stayOpenFor], and stops it, all while holding the lock, so a profile
     * switch or the app's return gets a clean stop and never races it for the directory. It ends
     * early as soon as the app asks for anything, so nobody waits on a renewal to see their
     * wallet. Does nothing while the app is using the daemon. Returns whether it ran.
     */
    suspend fun maintainInBackground(background: WalletProfile, stayOpenFor: Duration): Boolean = mutex.withLock {
        if (foreground && profile != null) return@withLock false
        if (engine != null || heldBy != null) return@withLock false
        val previous = profile
        val previousState = _state.value
        profile = background
        val seen = asked.value
        val ran = when (setupFor(background)) {
            is WalletSetup.Ready -> {
                open(background)
                val opened = engine != null
                if (opened) withTimeoutOrNull(stayOpenFor) { asked.first { it != seen } }
                opened
            }
            is WalletSetup.Unavailable -> false
        }
        stopDaemon()
        profile = previous
        _state.value = previousState
        ran
    }

    private fun setupFor(next: WalletProfile): WalletSetup = WalletSetup.resolve(
        hasSeed = next.words != null,
        platformSupported = client != null,
        torEnabled = next.torEnabled,
        consented = files.consented(next.id),
        offered = offered,
        canCreateSeed = next.canCreateSeed,
    )

    /**
     * Whether the active wallet could hold anything, for the forget guard: true unless it is open
     * and the daemon says it holds nothing, has nothing on its way, and has no exit under way. A
     * wallet that can't be read is assumed to hold money.
     */
    suspend fun mayHoldMoney(id: WalletId): Boolean = mutex.withLock {
        val running = engine?.takeIf { profile?.id == id } ?: return@withLock true
        try {
            val client = running.client
            val balance = client.balance()
            val empty = balance.confirmedSat == 0L && balance.pendingInSat == 0L && balance.pendingOutSat == 0L &&
                balance.creditAvailableSat == 0L && balance.creditReservedSat == 0L
            !empty || client.exitSummary().exits.isNotEmpty()
        } catch (e: WavelengthException) {
            true
        }
    }

    private suspend fun switchTo(next: WalletProfile?, force: Boolean = false) {
        val unchanged = next?.id == profile?.id && next?.torEnabled == profile?.torEnabled
        if (!force && unchanged && engine != null) return
        heldBy?.let { group ->
            profile = next
            _state.value = WalletState.RunByGroup(group)
            return
        }
        stopDaemon()
        profile = next
        if (next == null) {
            _state.value = WalletState.None
            return
        }
        when (val setup = setupFor(next)) {
            is WalletSetup.Unavailable -> _state.value = WalletState.Unavailable(setup.reason)
            is WalletSetup.Ready -> open(next)
        }
    }

    private suspend fun open(next: WalletProfile, recoveryWindow: Long? = null) {
        val client = client ?: return
        val pinned = files.pinnedIdentity(network, next.id)
        val first = pinned == null
        val window = recoveryWindow ?: if (first) WavelengthWalletRollout.FIRST_OPEN_RECOVERY_WINDOW else null
        _state.value = if (window != null) WalletState.Recovering else WalletState.Opening
        val config = configFor(network, files.dataDir(network, next.id))
        try {
            val result = withContext(NonCancellable) {
                client.startDescriptorWallet(
                    config,
                    DescriptorWalletRequest(
                        descriptors = WalletKeys.descriptors(next.words!!, network),
                        birthday = WavelengthWalletRollout.EARLIEST_BIRTHDAY,
                        expectedIdentityPubKey = pinned,
                        recoverState = window != null,
                        recoveryWindow = window,
                    ),
                )
            }
            if (first) files.pinIdentity(network, next.id, result.identityPubKey)
            val owned = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            val opened = WalletEngine(client, owned, config)
            // The daemon is running, so this reads its state rather than starting another.
            opened.start()
            engine = opened
            engineScope = owned
            _state.value = WalletState.Open(opened, network, result.recovery(), retainPreimages(opened, owned, next.id))
            logger.i { "opened the wallet for ${next.id} on ${network.wire}" }
        } catch (e: WavelengthException.InvalidDescriptor) {
            // The message names keys only by their public identity, but it stays out of the log.
            logger.w { "the wallet directory for ${next.id} does not match its words" }
            stopQuietly()
            _state.value = WalletState.WrongIdentity
        } catch (e: WavelengthException) {
            logger.w { "could not open the wallet for ${next.id}: ${e.code}" }
            stopQuietly()
            _state.value = WalletState.Failed(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // This runs on the process's own scope, where anything uncaught ends the app. And only
            // the type is logged: the failure's message may have been built around these words.
            logger.w { "could not open the wallet for ${next.id}: ${e::class.simpleName}" }
            stopQuietly()
            _state.value = WalletState.Failed(WavelengthException.Daemon("could not open the wallet"))
        }
    }

    /**
     * The daemon reports a payment's preimage once, on the stream, and the engine holds it in
     * memory, while the daemon stops and reopens all day here. So each one is written down as it
     * goes past.
     */
    private fun retainPreimages(opened: WalletEngine, owned: CoroutineScope, id: WalletId): StateFlow<Map<String, String>> {
        val known = MutableStateFlow(files.preimages(network, id))
        owned.launch {
            opened.snapshot.collect { snapshot ->
                snapshot.activity.mapNotNull { it.progress }.forEach { progress ->
                    if (progress.preimage.isNotEmpty() && progress.paymentHash !in known.value) {
                        files.recordPreimage(network, id, progress.paymentHash, progress.preimage)
                        known.value = known.value + (progress.paymentHash to progress.preimage)
                    }
                }
            }
        }
        return known
    }

    private fun DescriptorWalletOpenResult.recovery(): WalletRecovery? =
        if (recoveryRan) WalletRecovery(recoveredVtxos, recoveredBoardingUtxos, recoveredOorRecipientEvents) else null

    private suspend fun stopDaemon() {
        val running = engine
        engine = null
        if (running != null) {
            withContext(NonCancellable) {
                try {
                    running.stop()
                } catch (e: WavelengthException) {
                    logger.w { "stopping the wallet: ${e.code}" }
                }
            }
            running.close()
        }
        engineScope = null
        stopQuietly()
    }

    /** `stop` is idempotent, and a failed open may have left nothing to stop. */
    private suspend fun stopQuietly() {
        withContext(NonCancellable) {
            try {
                client?.stop()
            } catch (e: WavelengthException) {
                logger.w { "stopping the daemon: ${e.code}" }
            }
        }
    }
}
