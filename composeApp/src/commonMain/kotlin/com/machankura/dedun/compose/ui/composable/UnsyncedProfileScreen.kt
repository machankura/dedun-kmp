package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.enter_a_name_for_the_profile
import dedun.composeapp.generated.resources.enter_the_name_you_want_to_use_for_your_2
import dedun.composeapp.generated.resources.introduce_yourself
import dedun.composeapp.generated.resources.it_may_be_new_or_it_may_live_on_relays_we
import dedun.composeapp.generated.resources.name_eg_alan_turing
import dedun.composeapp.generated.resources.set_up_a_profile
import dedun.composeapp.generated.resources.try_again
import dedun.composeapp.generated.resources.use_a_different_key
import dedun.composeapp.generated.resources.use_a_different_key_question
import dedun.composeapp.generated.resources.switch_profile
import dedun.composeapp.generated.resources.could_not_sign_out_please_try_again
import com.machankura.dedun.compose.ui.composable.widgets.dialogs.SignOutOfReadOnlyDialog
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.view.model.SignOutViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import dedun.composeapp.generated.resources.we_are_looking_for_your_profile_on_as_many
import dedun.composeapp.generated.resources.we_are_searching_the_internet_to_find_your
import dedun.composeapp.generated.resources.we_could_not_find_a_profile_for_this_key
import dedun.composeapp.generated.resources.what_should_people_know_about_you
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.UnsyncedProfileViewModel
import com.machankura.dedun.compose.ui.view.state.UnsyncedProfileUIState

/**
 * Waiting for a sign-in sync -- and, since Phase 5 of docs/nsec-sign-in.md, able to say
 * when the wait is over with nothing to show for it. Before that the spinner below was
 * the whole screen and had no exit: a key made in a client that never wrote a kind 0
 * sat under "searching" for good.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UnsyncedProfileScreen(
    unsyncedProfilePublicKey: String,
    nostrRepository: NostrRepository,
    // The not-found state's exit for an identity that cannot set a profile up: the same
    // act as signing out, reached from the other end of the identity's life. Null where
    // there is no identity to leave (previews), in which case that kind has no exit here.
    signOut: SignOutDependencies? = null,
    // The third exit, for every kind: another profile on the device to go to instead.
    // Null when there is none, in which case the row is not drawn. The identity that was
    // not found stays listed; forgetting it remains a separate decision.
    onSwitchProfile: (() -> Unit)? = null,
) {
    val viewModel: UnsyncedProfileViewModel = viewModel(
        factory = UnsyncedProfileViewModel.factory(
            publicKey = unsyncedProfilePublicKey,
            nostrRepository = nostrRepository,
        )
    )
    val uiState by viewModel.uiState.collectAsState()

    val signOutViewModel: SignOutViewModel? = signOut?.let {
        viewModel(
            factory = SignOutViewModel.factory(
                activeIdentityStateFlow = it.activeIdentityStateFlow,
                nostrRepository = nostrRepository,
                forgetNostrCredential = it.forgetNostrCredential,
                hideIdentityMetadata = it.hideIdentityMetadata,
            ),
        )
    }
    val signOutState = signOutViewModel?.state?.collectAsState()?.value
    if (signOutViewModel != null && signOut != null && signOutState is SignOutViewModel.State.Confirming) {
        SignOutOfReadOnlyDialog(
            title = Res.string.use_a_different_key_question,
            nostrPublicKey = signOutViewModel.nostrPublicKey,
            onConfirm = { signOutViewModel.signOut(onSignedOut = signOut.onSignedOut) },
            onDismiss = { signOutViewModel.cancel() },
        )
    }
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotSignOut = stringResource(Res.string.could_not_sign_out_please_try_again)
    LaunchedEffect(signOutState) {
        if (signOutState is SignOutViewModel.State.Failed) notify(couldNotSignOut)
    }

    // imePadding for the not-found form's fields.
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { innerPadding ->
        ScreenStateTransition(
            state = uiState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            label = "unsynced profile",
        ) { state ->
            when (state) {
                is UnsyncedProfileUIState.Searching -> Searching()
                is UnsyncedProfileUIState.NotFound -> NotFound(
                    viewModel = viewModel,
                    unsignedNostrEventId = state.unsignedNostrEventId,
                    onUseADifferentKey = signOutViewModel?.let { { it.askToSignOut() } },
                    onSwitchProfile = onSwitchProfile,
                )
            }
        }
    }
}

@Composable
private fun Searching() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .readableContent()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                MaterialTheme.spacing.space125
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
        ) {

            Spacer(
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(Res.string.we_are_searching_the_internet_to_find_your),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(Res.string.we_are_looking_for_your_profile_on_as_many),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            LoadingDataIndicator(
                fillScreen = false
            )

            Spacer(
                modifier = Modifier.weight(2f)
            )
        }
    }
}

/**
 * Every relay asked has answered and none had a profile. Two ways out: ask again, or
 * give the key that is already on this device a profile -- the six-event bootstrap a
 * fresh key gets, without a fresh key.
 */
@Composable
private fun NotFound(
    viewModel: UnsyncedProfileViewModel,
    unsignedNostrEventId: Long,
    /** Shown only where the set-up form is not; null when there is no identity to leave. */
    onUseADifferentKey: (() -> Unit)?,
    /** Shown for every kind, when there is another profile to go to. */
    onSwitchProfile: (() -> Unit)?,
) {
    val nameField = viewModel.formState.nameField.textFieldState
    val biographyField = viewModel.formState.biographyField.textFieldState
    val nameMissing = viewModel.nameMissing.value
    val isActionPending = viewModel.isActionPending.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .readableContent()
            .padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap),
    ) {
        EmptyState(
            message = stringResource(Res.string.we_could_not_find_a_profile_for_this_key),
            icon = Icons.Default.PersonSearch,
            action = {
                TextButton(
                    enabled = !isActionPending,
                    onClick = { viewModel.tryAgain(unsignedNostrEventId) },
                ) {
                    Text(stringResource(Res.string.try_again))
                }
            },
        )

        Text(
            text = stringResource(Res.string.it_may_be_new_or_it_may_live_on_relays_we),
            style = MaterialTheme.typography.bodyMedium,
        )

        // With other profiles on the device, a way to one of them -- for every kind, since
        // the wait is the same whichever kind is waiting. See docs/multiple-profiles.md,
        // Phase 7.
        if (onSwitchProfile != null) {
            TextButton(
                modifier = Modifier.align(Alignment.End),
                enabled = !isActionPending,
                onClick = onSwitchProfile,
            ) {
                Text(text = stringResource(Res.string.switch_profile))
            }
        }

        // Setting a profile up is signing a kind 0, which a read-only identity cannot do.
        // The form is not offered to it -- and then this state would have no exit: the
        // profile tab is not reachable before ProfileLoaded, so a user whose npub was not
        // found on any relay could try again for ever. So, for that kind only, the third
        // action: the identity leaves the device, and the way back is the selector or
        // Landing.
        if (!LocalCanSign.current) {
            if (onUseADifferentKey != null) {
                TextButton(
                    modifier = Modifier.align(Alignment.End),
                    enabled = !isActionPending,
                    onClick = onUseADifferentKey,
                ) {
                    Text(text = stringResource(Res.string.use_a_different_key))
                }
            }
            return@Column
        }

        Text(
            text = stringResource(Res.string.set_up_a_profile),
            style = MaterialTheme.typography.titleMedium,
        )

        TextField(
            modifier = Modifier.fillMaxWidth(),
            state = nameField,
            isError = nameMissing,
            supportingText = if (nameMissing) {
                { Text(text = stringResource(Res.string.enter_a_name_for_the_profile)) }
            } else null,
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            label = { Text(text = stringResource(Res.string.name_eg_alan_turing), maxLines = 1) },
            placeholder = { Text(text = stringResource(Res.string.enter_the_name_you_want_to_use_for_your_2), maxLines = 1) },
            // The label names the field; the icon only decorates it.
            leadingIcon = { Icon(Icons.Default.Badge, contentDescription = Decorative) },
        )

        TextField(
            modifier = Modifier.fillMaxWidth(),
            state = biographyField,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            label = { Text(text = stringResource(Res.string.introduce_yourself), maxLines = 1) },
            placeholder = { Text(text = stringResource(Res.string.what_should_people_know_about_you), maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Description, contentDescription = Decorative) },
        )

        Button(
            modifier = Modifier.align(Alignment.End),
            enabled = !isActionPending,
            onClick = { viewModel.setUpProfile(unsignedNostrEventId) },
        ) {
            Text(text = stringResource(Res.string.set_up_a_profile))
        }
    }
}

@ConformancePreviews
@Composable
private fun UnsyncedProfileScreenPreview() {
    com.machankura.dedun.compose.ui.theme.DedunTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            UnsyncedProfileScreen(
                unsyncedProfilePublicKey = "a".repeat(64),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
            )
        }
    }
}
