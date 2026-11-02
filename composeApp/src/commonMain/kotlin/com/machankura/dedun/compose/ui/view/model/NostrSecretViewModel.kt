package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.ark.ArkProcess
import fr.acinq.phoenix.utils.getApplicationFilesDirectoryPath
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.DecryptNostrCredentialsResult
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.security.NostrCredential
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.extensions.hexToNsecHrp
import com.machankura.dedun.compose.identity.ForgetIdentity
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.NostrSecretUIState

/**
 * Reads back the nostr secret key an identity was signed in with.
 *
 * The counterpart of [RecoveryPhraseViewModel] for the identity kind that has a key and
 * no phrase. There is no wallet behind such an identity, so the key is the whole of the
 * backup, and everything here is about getting it in front of the user once and
 * recording that they say they stored it.
 *
 * The key is read from the file at reveal time, not off the active identity, so the
 * screen's contract -- nothing secret held longer than it is shown -- is the same as the
 * phrase screen's. The backup flags are the same per-identity preferences the phrase
 * screen writes; they mean "this identity's secret is not backed up" for whichever
 * secret it is.
 *
 * It also owns the inverse of an import: [forgetKey], which removes the key from the
 * device and the account with it, through [ForgetIdentity]. Only for a bare key; a
 * mnemonic identity's key is in the seed, and removing a seed is a wallet question this
 * does not answer.
 */
class NostrSecretViewModel(
    val phoenixGlobal: PhoenixGlobal,
    val activeIdentityStateFlow: StateFlow<Identity?>,
    private val nostrRepository: NostrRepository,
    private val forgetNostrCredential: suspend (Identity) -> IdentityWriter.ForgetNostrCredentialResult,
    private val hideIdentityMetadata: suspend (Identity) -> Unit,
) : ViewModel() {

    private val logger = Logger.withTag(TAG)

    private val _uiState = MutableStateFlow<NostrSecretUIState>(NostrSecretUIState.Hidden)
    val uiState = _uiState.asStateFlow()

    private val _forgetting = MutableStateFlow<NostrSecretUIState.Forgetting>(NostrSecretUIState.Forgetting.Idle)
    val forgetting = _forgetting.asStateFlow()

    /** Whether the active identity is one whose key this screen can show and forget. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isBareKeyIdentity: StateFlow<Boolean> = activeIdentityStateFlow
        .flatMapLatest { flowOf(it?.kind == IdentityKind.NostrSecret) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isBackupDone: StateFlow<Boolean?> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.isManualSeedBackupDone ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isDisclaimerRead: StateFlow<Boolean?> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.isSeedLossDisclaimerRead ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val showBackupNotice: StateFlow<Boolean> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.showSeedBackupNotice ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun revealNostrSecret() {
        if (_uiState.value is NostrSecretUIState.Revealing) return

        val identity = activeIdentityStateFlow.value
        if (identity == null) {
            logger.e { "no active identity, cannot read a nostr key" }
            _uiState.value = NostrSecretUIState.Error.NoActiveIdentity
            return
        }

        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            logger.e("failed to read the nostr keys", throwable)
            _uiState.value = NostrSecretUIState.Error.KeysUnreadable
        }) {
            _uiState.value = NostrSecretUIState.Revealing

            // Keystore-backed, and it reads a file: it blocks, so it cannot run on the main thread.
            val result = withContext(Dispatchers.IO) {
                NostrCredentialManager.loadAndDecrypt(phoenixGlobal)
            }

            _uiState.value = when (result) {
                is DecryptNostrCredentialsResult.Success -> {
                    // A public entry is not a key either: this screen is not offered to a
                    // read-only identity, and if it were reached there is nothing to show.
                    val credential = result.credentials[identity.nostrPublicKey] as? NostrCredential.Secret
                    if (credential == null) {
                        logger.e { "credentials file holds no secret for identity=${identity.id}" }
                        NostrSecretUIState.Error.NoKeyForThisIdentity
                    } else {
                        NostrSecretUIState.Revealed(credential.privateKey.value.toHex().hexToNsecHrp())
                    }
                }
                is DecryptNostrCredentialsResult.Failure.FileNotFound -> {
                    logger.e { "no credentials file, so no key for identity=${identity.id}" }
                    NostrSecretUIState.Error.NoKeyForThisIdentity
                }
                is DecryptNostrCredentialsResult.Failure -> {
                    logger.e { "unable to read the nostr credentials: $result" }
                    NostrSecretUIState.Error.KeysUnreadable
                }
            }
        }
    }

    fun hideNostrSecret() {
        _uiState.value = NostrSecretUIState.Hidden
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

    fun askToForgetKey() {
        _forgetting.value = NostrSecretUIState.Forgetting.Confirming
    }

    fun cancelForgetKey() {
        _forgetting.value = NostrSecretUIState.Forgetting.Idle
    }

    /**
     * Key out of the file, preferences deleted, metadata hidden, account rows gone -- in
     * that order, for the reason [ForgetIdentity] gives. [onForgotten] runs on the main
     * thread once the device no longer holds the key; the caller clears the active
     * identity and re-lists.
     */
    fun forgetKey(onForgotten: () -> Unit) {
        val identity = activeIdentityStateFlow.value ?: return
        if (_forgetting.value is NostrSecretUIState.Forgetting.InProgress) return
        _forgetting.value = NostrSecretUIState.Forgetting.InProgress
        _uiState.value = NostrSecretUIState.Hidden

        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            logger.e("could not forget the key", throwable)
            _forgetting.value = NostrSecretUIState.Forgetting.Failed
        }) {
            // The sequence is ForgetIdentity's, shared with the two exits a read-only
            // identity has; only the state it drives is this screen's.
            val hasArkWallet: suspend (Identity) -> Boolean = { candidate ->
                ArkProcess.seeds(getApplicationFilesDirectoryPath(phoenixGlobal.ctx)).has(candidate.id)
            }
            when (ForgetIdentity.forget(identity, nostrRepository, forgetNostrCredential, hideIdentityMetadata, hasArkWallet)) {
                is ForgetIdentity.Outcome.Forgotten -> {
                    _forgetting.value = NostrSecretUIState.Forgetting.Idle
                    withContext(Dispatchers.Main) { onForgotten() }
                }
                is ForgetIdentity.Outcome.ArkWalletAttached -> {
                    _forgetting.value = NostrSecretUIState.Forgetting.ArkWalletAttached
                }
                is ForgetIdentity.Outcome.WalletAttached,
                is ForgetIdentity.Outcome.CannotForget -> {
                    _forgetting.value = NostrSecretUIState.Forgetting.Failed
                }
            }
        }
    }

    companion object {
        private const val TAG = "NostrSecretViewModel"

        fun factory(
            phoenixGlobal: PhoenixGlobal,
            activeIdentityStateFlow: StateFlow<Identity?>,
            nostrRepository: NostrRepository,
            forgetNostrCredential: suspend (Identity) -> IdentityWriter.ForgetNostrCredentialResult,
            hideIdentityMetadata: suspend (Identity) -> Unit,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NostrSecretViewModel(
                    phoenixGlobal = phoenixGlobal,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    nostrRepository = nostrRepository,
                    forgetNostrCredential = forgetNostrCredential,
                    hideIdentityMetadata = hideIdentityMetadata,
                )
            }
        }
    }
}
