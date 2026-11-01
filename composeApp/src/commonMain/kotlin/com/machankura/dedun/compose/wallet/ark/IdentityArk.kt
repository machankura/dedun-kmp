package com.machankura.dedun.compose.wallet.ark

import co.touchlab.kermit.Logger
import fr.acinq.phoenix.data.WalletId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the active profile asks of its Ark wallet. [words] is null for a profile with no
 * seed. Never logged: [toString] leaves the words out.
 */
class ArkProfile(val id: WalletId, val words: List<String>?, val torEnabled: Boolean) {
    override fun toString(): String = "ArkProfile(id=$id, words=${if (words == null) "none" else "***"}, tor=$torEnabled)"
}

/** Where the active profile's Ark wallet is. */
sealed interface ArkWalletState {
    /** No profile is active. */
    data object None : ArkWalletState

    data class Unavailable(val reason: ArkUnavailability) : ArkWalletState

    data object Opening : ArkWalletState

    /** Opening a datadir that did not exist: the mailbox scan runs inside the open. */
    data object Recovering : ArkWalletState

    data class Open(val wallet: ArkWallet, val recovery: ArkRecovery) : ArkWalletState

    data class Failed(val error: ArkError) : ArkWalletState
}

/**
 * The active profile's Ark wallet, one per process -- `docs/bark-wallet.md`, D2.
 *
 * Bark lets a datadir be open once, even within one process, so every open and every
 * close goes through one [Mutex], in the order they were asked for: a switch closes
 * the last profile's wallet -- its daemon stopped and waited for -- before it opens the
 * next. Held for the app's life by the nav host, as `IdentityElectrum` is, and for the
 * same reason: a `remember` keyed on the identity would build the next before disposing
 * the last.
 *
 * The open runs under [NonCancellable], as Bark asks: a wallet half-opened when the
 * caller went away would hold the datadir with nothing to close it.
 */
class IdentityArk(
    private val scope: CoroutineScope,
    private val files: ArkFiles,
    private val appVersion: String,
    private val platformSupported: Boolean = ArkPlatform.supported,
    private val network: ArkNetwork = ArkWalletRollout.NETWORK,
    private val rolloutEnabled: Boolean = ArkWalletRollout.ENABLED,
    private val opener: suspend (ArkConfig, String, String) -> ArkWallet = ArkPlatform::open,
) {
    private val logger = Logger.withTag("IdentityArk")
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ArkWalletState>(ArkWalletState.None)
    val state: StateFlow<ArkWalletState> = _state.asStateFlow()

    private var profile: ArkProfile? = null
    private var wallet: ArkWallet? = null

    /** Makes [next] the active profile: closes the last one's wallet, then opens this one's, if it can. */
    fun activate(next: ArkProfile?) {
        scope.launch { mutex.withLock { switchTo(next) } }
    }

    /** The first-open sheet was accepted for the active profile: record it, and open. */
    fun consent() {
        scope.launch {
            mutex.withLock {
                val current = profile ?: return@withLock
                files.recordConsent(current.id)
                switchTo(current, force = true)
            }
        }
    }

    /** Tries the active profile's open again, after a failure. */
    fun retry() {
        scope.launch { mutex.withLock { profile?.let { switchTo(it, force = true) } } }
    }

    /** Closes whatever is open. The nav host calls it as the app goes away. */
    suspend fun stop() = mutex.withLock {
        closeCurrent()
        profile = null
        _state.value = ArkWalletState.None
    }

    /**
     * Runs [block] with [id]'s wallet if it is the one open, for the work that must not
     * open a second copy -- the background renewal, Phase 7. Null if it is not open.
     */
    suspend fun <T> withOpenWallet(id: WalletId, block: suspend (ArkWallet) -> T): T? = mutex.withLock {
        val open = wallet?.takeIf { profile?.id == id } ?: return@withLock null
        block(open)
    }

    private suspend fun switchTo(next: ArkProfile?, force: Boolean = false) {
        if (!force && next?.id == profile?.id && next?.torEnabled == profile?.torEnabled && wallet != null) return
        closeCurrent()
        profile = next
        if (next == null) {
            _state.value = ArkWalletState.None
            return
        }
        val setup = ArkSetup.resolve(
            hasSeed = next.words != null,
            platformSupported = platformSupported,
            torEnabled = next.torEnabled,
            consented = files.consented(next.id),
            appVersion = appVersion,
            network = network,
            rolloutEnabled = rolloutEnabled,
        )
        when (setup) {
            is ArkSetup.Unavailable -> _state.value = ArkWalletState.Unavailable(setup.reason)
            is ArkSetup.Ready -> open(next, setup.config)
        }
    }

    private suspend fun open(next: ArkProfile, config: ArkConfig) {
        val datadir = files.datadir(config.network, next.id)
        val creating = !files.exists(datadir)
        _state.value = if (creating) ArkWalletState.Recovering else ArkWalletState.Opening
        try {
            val opened = withContext(NonCancellable) { opener(config, next.words!!.joinToString(" "), datadir) }
            wallet = opened
            _state.value = ArkWalletState.Open(opened, opened.recovery())
            logger.i { "opened the Ark wallet for ${next.id} on ${config.network}" }
        } catch (e: ArkError) {
            logger.w { "could not open the Ark wallet for ${next.id}: ${e.message}" }
            _state.value = ArkWalletState.Failed(e)
        }
    }

    private suspend fun closeCurrent() {
        val open = wallet ?: return
        wallet = null
        try {
            withContext(NonCancellable) { open.close() }
        } catch (e: ArkError) {
            logger.w { "closing the Ark wallet: ${e.message}" }
        }
    }
}
