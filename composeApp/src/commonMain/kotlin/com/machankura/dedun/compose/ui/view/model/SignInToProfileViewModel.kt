package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.data.WalletId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.identity.CredentialParser
import com.machankura.dedun.compose.identity.CredentialProblem
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.identity.SignInCredential
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.SignInToProfileUIState
import com.machankura.dedun.compose.ui.view.state.form.SignInToProfileFormState

/**
 * Signs in with something the user already has: a recovery phrase, an nsec, or -- to
 * look and not sign -- an npub.
 *
 * The three writers are injected as functions rather than reached for, so the commit
 * can be driven in a test without a key store, and because the recovery-phrase writer
 * is `SovereignWalletViewModel.writeSeed`, which lives in another view model and drives
 * a state machine of its own.
 *
 * What this does not do is decide where the user goes next. It writes the credential, plants
 * the placeholder account, and hands the new id to [onSignedIn]; the nav host re-lists
 * identities, selects that one and goes to startup, which is the same tail the create
 * flow uses. The **order** inside [commit] is load-bearing: the account has to be in the
 * database before the identity is activated, because `NavigationViewModel.observeProfile`
 * starts reading the moment the identity flow emits, and an account that is not there
 * yet reads as "go and make one".
 */
class SignInToProfileViewModel(
    private val nostrRepository: NostrRepository,
    private val writeNostrKey: suspend (PrivateKey) -> IdentityWriter.WriteNostrCredentialResult,
    private val writeNostrPublicKey: suspend (HexKey) -> IdentityWriter.WriteNostrCredentialResult,
    private val writeRecoveryPhrase: (
        words: List<String>,
        onWritten: (WalletId) -> Unit,
        onError: (WritingSeedState.Error) -> Unit,
    ) -> Unit,
    val signInToProfileFormState: SignInToProfileFormState = SignInToProfileFormState(),
) : ViewModel() {

    companion object {
        private const val TAG = "SignInToProfileViewModel"

        fun factory(
            nostrRepository: NostrRepository,
            writeNostrKey: suspend (PrivateKey) -> IdentityWriter.WriteNostrCredentialResult,
            writeNostrPublicKey: suspend (HexKey) -> IdentityWriter.WriteNostrCredentialResult,
            writeRecoveryPhrase: (
                words: List<String>,
                onWritten: (WalletId) -> Unit,
                onError: (WritingSeedState.Error) -> Unit,
            ) -> Unit,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SignInToProfileViewModel(
                    nostrRepository = nostrRepository,
                    writeNostrKey = writeNostrKey,
                    writeNostrPublicKey = writeNostrPublicKey,
                    writeRecoveryPhrase = writeRecoveryPhrase,
                )
            }
        }
    }

    private val logger = Logger.withTag(TAG)

    val uiState: MutableState<SignInToProfileUIState> = mutableStateOf(SignInToProfileUIState.InputPrompt)

    /** Why the field's contents were refused, shown as its supporting text; null when they were not. */
    val inputProblem: MutableState<CredentialProblem?> = mutableStateOf(null)

    /** Reads the field and either moves to [SignInToProfileUIState.Confirm] or marks the field. */
    fun recognise() {
        when (val result = CredentialParser.parse(signInToProfileFormState.textFieldForm.textFieldState.text.toString())) {
            is CredentialParser.Result.Refused -> inputProblem.value = result.problem
            is CredentialParser.Result.Recognised -> {
                inputProblem.value = null
                uiState.value = SignInToProfileUIState.Confirm(result.credential)
            }
        }
    }

    /** Back to the field, keeping what was typed. */
    fun useADifferentKey() {
        uiState.value = SignInToProfileUIState.InputPrompt
    }

    /** The outcome of [commit], for the screen to act on and the tests to assert. */
    sealed interface Outcome {
        data class SignedIn(val id: WalletId) : Outcome

        /** @param alreadyHere for [CredentialProblem.AlreadyOnThisDevice], the id the key is listed under. */
        data class Failed(val problem: CredentialProblem, val alreadyHere: WalletId? = null) : Outcome
    }

    private suspend fun write(what: String, writer: suspend () -> IdentityWriter.WriteNostrCredentialResult): Outcome = try {
        when (val result = writer()) {
            is IdentityWriter.WriteNostrCredentialResult.Written -> Outcome.SignedIn(result.id)
            is IdentityWriter.WriteNostrCredentialResult.AlreadyExists -> Outcome.Failed(CredentialProblem.AlreadyOnThisDevice, alreadyHere = result.id)
            is IdentityWriter.WriteNostrCredentialResult.CannotLoadKeys -> Outcome.Failed(CredentialProblem.CouldNotWrite)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e("could not write the $what", e)
        Outcome.Failed(CredentialProblem.CouldNotWrite)
    }

    /**
     * Writes the credential to its store, then plants the placeholder account that tells
     * the navigation machine this pubkey has a history to fetch rather than a profile to
     * create. Suspends until both are done or one has failed. The plant is idempotent by
     * pubkey, which is what lets the nsec of a key held read-only sign in over it.
     */
    suspend fun commit(credential: SignInCredential): Outcome {
        val written: Outcome = when (credential) {
            is SignInCredential.NostrSecret -> write("nostr key") { writeNostrKey(credential.privateKey) }

            is SignInCredential.NostrPublicKey -> write("nostr public key") { writeNostrPublicKey(credential.nostrPublicKey) }

            is SignInCredential.RecoveryPhrase -> {
                // Callback-shaped writer, awaited: the seed writer drives WritingSeedState and
                // reports through two callbacks, exactly one of which fires.
                val outcome = CompletableDeferred<Outcome>()
                writeRecoveryPhrase(
                    credential.words,
                    { id -> outcome.complete(Outcome.SignedIn(id)) },
                    { error ->
                        outcome.complete(
                            when (error) {
                                is WritingSeedState.Error.SeedAlreadyExists -> Outcome.Failed(CredentialProblem.AlreadyOnThisDevice, alreadyHere = error.id)
                                is WritingSeedState.Error.CannotLoadSeedMap,
                                is WritingSeedState.Error.Generic -> Outcome.Failed(CredentialProblem.CouldNotWrite)
                            }
                        )
                    },
                )
                outcome.await()
            }
        }

        if (written is Outcome.SignedIn) {
            // Before the identity is activated -- see the class comment.
            nostrRepository.signInToProfile(publicKey = credential.nostrPublicKey)
        }
        return written
    }

    /** [commit] from the confirm button, moving the screen through Committing to wherever it ends. */
    fun confirm(credential: SignInCredential, onSignedIn: (WalletId) -> Unit) {
        if (uiState.value is SignInToProfileUIState.Committing) return
        uiState.value = SignInToProfileUIState.Committing

        viewModelScope.launch(Dispatchers.IO) {
            when (val outcome = commit(credential)) {
                is Outcome.SignedIn -> withContext(Dispatchers.Main) { onSignedIn(outcome.id) }
                is Outcome.Failed -> uiState.value = SignInToProfileUIState.Error(outcome.problem, outcome.alreadyHere)
            }
        }
    }

    /** From the error state back to the field. */
    fun retry() {
        uiState.value = SignInToProfileUIState.InputPrompt
    }
}
