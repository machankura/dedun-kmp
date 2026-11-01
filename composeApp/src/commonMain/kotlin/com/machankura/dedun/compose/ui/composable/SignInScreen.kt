package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_password_protected_key_ncryptsec_is_not
import dedun.composeapp.generated.resources.enter_a_recovery_phrase_or_an_nsec_to_sign_in
import dedun.composeapp.generated.resources.next
import dedun.composeapp.generated.resources.paste
import dedun.composeapp.generated.resources.recognised_as_a_nostr_secret_key_no_wallet
import dedun.composeapp.generated.resources.recognised_as_a_public_key_you_will_see
import dedun.composeapp.generated.resources.recognised_as_a_recovery_phrase_it_also
import dedun.composeapp.generated.resources.recovery_phrase_nsec_or_npub
import dedun.composeapp.generated.resources.sign_in
import dedun.composeapp.generated.resources.signed_in
import dedun.composeapp.generated.resources.switch_to_it
import dedun.composeapp.generated.resources.signing_you_in
import dedun.composeapp.generated.resources.that_is_not_a_recovery_phrase_or_a_nostr_key
import dedun.composeapp.generated.resources.that_nostr_secret_key_is_not_valid
import dedun.composeapp.generated.resources.that_recovery_phrase_is_not_valid_check
import dedun.composeapp.generated.resources.the_key_could_not_be_saved_on_this_device
import dedun.composeapp.generated.resources.the_words_or_the_key_never_leave_this_device
import dedun.composeapp.generated.resources.this_key_is_already_on_this_device
import dedun.composeapp.generated.resources.twelve_words_nsec1_or_npub1
import dedun.composeapp.generated.resources.use_a_different_key
import dedun.composeapp.generated.resources.you_are_signing_in_as
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.data.WalletId
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.extensions.hexToNpubHrp
import com.machankura.dedun.compose.identity.CredentialProblem
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.identity.SignInCredential
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.SignInToProfileViewModel
import com.machankura.dedun.compose.ui.view.model.WritingSeedState
import com.machankura.dedun.compose.ui.view.state.SignInToProfileUIState

/**
 * Signing in with a secret the user already has.
 *
 * One field, two things it accepts -- a recovery phrase or an nsec -- because a user
 * does not choose an input type, they paste what they have. What was recognised, and
 * the npub it signs as, is shown before anything is written; that is the step that
 * catches the wrong key. Problems with the input stay on the field; problems after
 * confirming are a state of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInToProfileScreen(
    nostrRepository: NostrRepository,
    writeNostrKey: suspend (PrivateKey) -> IdentityWriter.WriteNostrCredentialResult,
    writeNostrPublicKey: suspend (HexKey) -> IdentityWriter.WriteNostrCredentialResult,
    writeRecoveryPhrase: (
        words: List<String>,
        onWritten: (WalletId) -> Unit,
        onError: (WritingSeedState.Error) -> Unit,
    ) -> Unit,
    onNavigateBack: () -> Unit,
    /**
     * The tail: re-list, select, go to startup. Also what "switch to it" does when the key
     * pasted is already here -- the same tail with the id it is here under, which makes a
     * duplicate paste the fastest switch in the app.
     */
    onSignedIn: (WalletId) -> Unit,
) {
    val viewModel: SignInToProfileViewModel = viewModel(
        factory = SignInToProfileViewModel.factory(
            nostrRepository = nostrRepository,
            writeNostrKey = writeNostrKey,
            writeNostrPublicKey = writeNostrPublicKey,
            writeRecoveryPhrase = writeRecoveryPhrase,
        )
    )

    // Both composable, and the handler that shows this is not: read above it.
    val notify = rememberNotifier(rememberCoroutineScope())
    val signedIn = stringResource(Res.string.signed_in)

    // imePadding: there is a text field, and without it the keyboard covers it.
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.sign_in)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { innerPadding ->
        ScreenStateTransition(
            state = viewModel.uiState.value,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            label = "sign in",
        ) { uiState ->
            when (val state = uiState) {
                is SignInToProfileUIState.InputPrompt -> InputPrompt(viewModel)

                is SignInToProfileUIState.Confirm -> Confirm(
                    credential = state.credential,
                    onConfirm = {
                        viewModel.confirm(state.credential) { id ->
                            notify(signedIn)
                            onSignedIn(id)
                        }
                    },
                    onUseADifferentKey = { viewModel.useADifferentKey() },
                )

                is SignInToProfileUIState.Committing -> LoadingDataIndicator(
                    text = stringResource(Res.string.signing_you_in)
                )

                is SignInToProfileUIState.Error -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ErrorState(
                        message = problemMessage(state.problem),
                        onRetry = { viewModel.retry() },
                    )
                    // "Already on this device" is a sentence with an obvious next step.
                    state.alreadyHere?.let { id ->
                        TextButton(onClick = { onSignedIn(id) }) {
                            Text(stringResource(Res.string.switch_to_it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputPrompt(viewModel: SignInToProfileViewModel) {
    val field = viewModel.signInToProfileFormState.textFieldForm.textFieldState
    val problem = viewModel.inputProblem.value
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .readableContent()
            .padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap),
    ) {
        Text(
            text = stringResource(Res.string.the_words_or_the_key_never_leave_this_device),
            style = MaterialTheme.typography.bodyMedium,
        )

        TextField(
            modifier = Modifier.fillMaxWidth(),
            state = field,
            isError = problem != null,
            supportingText = problem?.let { { Text(text = problemMessage(it)) } },
            // Two lines shows a whole phrase without scrolling and an nsec on one; four is
            // room for a phrase pasted with line breaks.
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 4),
            // A key is not prose: no capitalisation, no autocorrect, no suggestions rewriting
            // a word of the phrase. Monospace so an nsec can be read back character by character.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            label = { Text(text = stringResource(Res.string.recovery_phrase_nsec_or_npub), maxLines = 1) },
            placeholder = { Text(text = stringResource(Res.string.twelve_words_nsec1_or_npub1), maxLines = 1) },
            trailingIcon = {
                IconButton(
                    onClick = {
                        clipboardManager.getText()?.text?.let { field.setTextAndPlaceCursorAtEnd(it) }
                    }
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = stringResource(Res.string.paste)
                    )
                }
            },
        )

        Button(
            modifier = Modifier.align(Alignment.End),
            onClick = { viewModel.recognise() },
        ) {
            Text(text = stringResource(Res.string.next))
        }
    }
}

@Composable
private fun Confirm(
    credential: SignInCredential,
    onConfirm: () -> Unit,
    onUseADifferentKey: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .readableContent()
            .padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap),
    ) {
        Text(
            text = stringResource(Res.string.you_are_signing_in_as),
            style = MaterialTheme.typography.bodyMedium,
        )

        // In full, so a wrong paste can be seen for what it is. The secret is never echoed.
        Text(
            text = credential.nostrPublicKey.hexToNpubHrp(),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        ) {
            // The label beside each icon says what it is; the icon only decorates it.
            when (credential) {
                is SignInCredential.RecoveryPhrase -> {
                    Icon(Icons.Default.Spellcheck, contentDescription = Decorative)
                    Text(
                        text = stringResource(Res.string.recognised_as_a_recovery_phrase_it_also),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is SignInCredential.NostrSecret -> {
                    Icon(Icons.Default.Key, contentDescription = Decorative)
                    Text(
                        text = stringResource(Res.string.recognised_as_a_nostr_secret_key_no_wallet),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is SignInCredential.NostrPublicKey -> {
                    // What the user is about to get and not get, said here and nowhere
                    // else: this is the one step before the choice is made.
                    Icon(Icons.Default.Visibility, contentDescription = Decorative)
                    Text(
                        text = stringResource(Res.string.recognised_as_a_public_key_you_will_see),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        ) {
            Button(onClick = onConfirm) {
                Text(text = stringResource(Res.string.sign_in))
            }
            TextButton(onClick = onUseADifferentKey) {
                Text(text = stringResource(Res.string.use_a_different_key))
            }
        }
    }
}

/** Each refusal is a sentence that says what to do instead, not a code. */
@Composable
private fun problemMessage(problem: CredentialProblem): String = stringResource(
    when (problem) {
        CredentialProblem.Empty -> Res.string.enter_a_recovery_phrase_or_an_nsec_to_sign_in
        CredentialProblem.NotRecognised -> Res.string.that_is_not_a_recovery_phrase_or_a_nostr_key
        CredentialProblem.InvalidPhrase -> Res.string.that_recovery_phrase_is_not_valid_check
        CredentialProblem.InvalidKey -> Res.string.that_nostr_secret_key_is_not_valid
        CredentialProblem.EncryptedKey -> Res.string.a_password_protected_key_ncryptsec_is_not
        CredentialProblem.AlreadyOnThisDevice -> Res.string.this_key_is_already_on_this_device
        CredentialProblem.CouldNotWrite -> Res.string.the_key_could_not_be_saved_on_this_device
    }
)

@ConformancePreviews
@Composable
private fun SignInToProfileScreenPreview() {
    com.machankura.dedun.compose.ui.theme.DedunTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            SignInToProfileScreen(
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                writeNostrKey = { IdentityWriter.WriteNostrCredentialResult.CannotLoadKeys },
                writeNostrPublicKey = { IdentityWriter.WriteNostrCredentialResult.CannotLoadKeys },
                writeRecoveryPhrase = { _, _, onError -> onError(WritingSeedState.Error.CannotLoadSeedMap) },
                onNavigateBack = {},
                onSignedIn = {},
            )
        }
    }
}
