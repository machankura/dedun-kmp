package com.machankura.dedun.compose.ui.view.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixGlobal
import com.machankura.dedun.compose.identity.Identity
import fr.acinq.phoenix.data.DecryptSeedResult
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.ui.view.state.RecoveryPhraseUIState

/**
 * Reads back the twelve words this device's keys are made of.
 *
 * The phrase is the whole of the profile: the nostr identity that signs, and the
 * wallet that holds coins, are both derived from this one seed, so a device that
 * loses it loses the account -- there is no server holding a copy. Everything
 * here is therefore about getting the words in front of the user *once*, and
 * recording that they say they wrote them down.
 *
 * The words are never cached: [recoveryPhraseUIState] carries them only while
 * the user is looking at them, and [hideRecoveryPhrase] (or leaving the screen,
 * which clears the view model) drops them.
 */
class RecoveryPhraseViewModel(
    val phoenixGlobal: PhoenixGlobal,
    val activeIdentityStateFlow: StateFlow<Identity?>,
): ViewModel() {

    private val logger = Logger.withTag(TAG)

    // A StateFlow rather than `mutableStateOf`: this view model is built during composition, and
    // the seed read answers from another thread. See SovereignWalletViewModel for the write that
    // was lost when that state belonged to the composition's snapshot.
    private val _recoveryPhraseUIState = MutableStateFlow<RecoveryPhraseUIState>(
        RecoveryPhraseUIState.Hidden
    )
    val recoveryPhraseUIState = _recoveryPhraseUIState.asStateFlow()

    /**
     * The backup bookkeeping, which lives per wallet in its internal prefs so it
     * survives a re-install of the app on the same seed.
     *
     * `null` means "not read yet" rather than `false`, so the screen can wait
     * instead of drawing an unticked box over a ticked preference.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isBackupDone: StateFlow<Boolean?> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.isManualSeedBackupDone ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isDisclaimerRead: StateFlow<Boolean?> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.isSeedLossDisclaimerRead ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True until the user has both written the phrase down and read the disclaimer. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val showBackupNotice: StateFlow<Boolean> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.showSeedBackupNotice ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Decrypts the seed file and pulls out the words of the wallet that is open.
     *
     * The file holds every wallet this device has ever been given, keyed by
     * wallet id, so which one to show is a question only the active wallet can
     * answer -- without it there is nothing to match against, hence
     * [RecoveryPhraseUIState.Error.NoActiveWallet] rather than a guess.
     */
    fun revealRecoveryPhrase() {
        if (_recoveryPhraseUIState.value is RecoveryPhraseUIState.Revealing) return

        val walletId = activeIdentityStateFlow.value?.id
        if (walletId == null) {
            logger.e { "no active wallet, cannot read a recovery phrase" }
            _recoveryPhraseUIState.value = RecoveryPhraseUIState.Error.NoActiveWallet
            return
        }

        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            logger.e("failed to read the seed", throwable)
            _recoveryPhraseUIState.value = RecoveryPhraseUIState.Error.SeedUnreadable
        }) {
            _recoveryPhraseUIState.value = RecoveryPhraseUIState.Revealing

            // Keystore-backed, and it reads a file: it blocks, so it cannot run on the main thread.
            val result = withContext(Dispatchers.IO) {
                loadAndDecryptSeed(phoenixGlobal)
            }

            _recoveryPhraseUIState.value = when (result) {
                is DecryptSeedResult.Success -> {
                    val userWallet = result.userWalletsMap[walletId]
                    if (userWallet == null) {
                        logger.e { "seed file holds no words for wallet=$walletId" }
                        RecoveryPhraseUIState.Error.NoPhraseForThisWallet
                    } else {
                        RecoveryPhraseUIState.Revealed(userWallet.words)
                    }
                }
                is DecryptSeedResult.Failure -> {
                    logger.e { "unable to read the seed: $result" }
                    RecoveryPhraseUIState.Error.SeedUnreadable
                }
            }
        }
    }

    /** Takes the words back off the screen. */
    fun hideRecoveryPhrase() {
        _recoveryPhraseUIState.value = RecoveryPhraseUIState.Hidden
    }

    fun setBackupDone(isDone: Boolean) {
        val internalPrefs = activeIdentityStateFlow.value?.internalPrefs ?: return
        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            logger.e("could not save the backup flag", throwable)
        }) {
            internalPrefs.saveManualSeedBackupDone(isDone)
        }
    }

    fun setDisclaimerRead(isRead: Boolean) {
        val internalPrefs = activeIdentityStateFlow.value?.internalPrefs ?: return
        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            logger.e("could not save the disclaimer flag", throwable)
        }) {
            internalPrefs.saveSeedLossDisclaimerRead(isRead)
        }
    }

    companion object {
        private const val TAG = "RecoveryPhraseViewModel"

        fun factory(
            phoenixGlobal: PhoenixGlobal,
            activeIdentityStateFlow: StateFlow<Identity?>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RecoveryPhraseViewModel(
                    phoenixGlobal = phoenixGlobal,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                )
            }
        }
    }
}
