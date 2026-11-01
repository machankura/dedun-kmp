package com.machankura.dedun.compose.ui.view.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.identity.ForgetIdentity
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.repository.NostrRepository

/**
 * Signing out of a read-only identity, which is forgetting it: there is nothing on the
 * device to lose, so it is the first real sign out in the app. The general case -- a
 * seed, with funds behind it -- stays pending, for the reason docs/nsec-sign-in.md gives.
 *
 * Two screens drive this with the same state: the profile tab's *sign out*, and the
 * not-found screen's *use a different key*, which is the same act reached from the other
 * end of the identity's life. The sequence is [ForgetIdentity]'s; this owns only the
 * confirmation and the in-flight state, the way `NostrSecretViewModel` does for an nsec.
 */
class SignOutViewModel(
    private val activeIdentityStateFlow: StateFlow<Identity?>,
    private val nostrRepository: NostrRepository,
    private val forgetNostrCredential: suspend (Identity) -> IdentityWriter.ForgetNostrCredentialResult,
    private val hideIdentityMetadata: suspend (Identity) -> Unit,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Confirming : State
        data object InProgress : State
        data object Failed : State
    }

    private val logger = Logger.withTag(TAG)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    /** The npub the confirmation names, so the user sees which identity is leaving. */
    val nostrPublicKey: String? get() = activeIdentityStateFlow.value?.nostrPublicKey

    fun askToSignOut() {
        if (_state.value is State.InProgress) return
        _state.value = State.Confirming
    }

    fun cancel() {
        if (_state.value is State.InProgress) return
        _state.value = State.Idle
    }

    /** [onSignedOut] runs on the main thread once the device holds nothing for the identity. */
    fun signOut(onSignedOut: () -> Unit) {
        val identity = activeIdentityStateFlow.value ?: return
        if (_state.value is State.InProgress) return
        _state.value = State.InProgress

        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            logger.e("could not sign out", throwable)
            _state.value = State.Failed
        }) {
            when (ForgetIdentity.forget(identity, nostrRepository, forgetNostrCredential, hideIdentityMetadata)) {
                is ForgetIdentity.Outcome.Forgotten -> {
                    _state.value = State.Idle
                    withContext(Dispatchers.Main) { onSignedOut() }
                }
                is ForgetIdentity.Outcome.WalletAttached,
                is ForgetIdentity.Outcome.CannotForget -> {
                    _state.value = State.Failed
                }
            }
        }
    }

    companion object {
        private const val TAG = "SignOutViewModel"

        fun factory(
            activeIdentityStateFlow: StateFlow<Identity?>,
            nostrRepository: NostrRepository,
            forgetNostrCredential: suspend (Identity) -> IdentityWriter.ForgetNostrCredentialResult,
            hideIdentityMetadata: suspend (Identity) -> Unit,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SignOutViewModel(
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    nostrRepository = nostrRepository,
                    forgetNostrCredential = forgetNostrCredential,
                    hideIdentityMetadata = hideIdentityMetadata,
                )
            }
        }
    }
}
