package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.backup_confirmation
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.copied_the_nsec
import dedun.composeapp.generated.resources.copy_nsec
import dedun.composeapp.generated.resources.could_not_forget_the_key_please_try_again
import dedun.composeapp.generated.resources.could_not_unlock_your_key_please_try
import dedun.composeapp.generated.resources.bennu_will_delete_the_nsec_for_s_from_this
import dedun.composeapp.generated.resources.display_nostr_secret_key
import dedun.composeapp.generated.resources.forget
import dedun.composeapp.generated.resources.forget_this_key
import dedun.composeapp.generated.resources.forget_this_key_question
import dedun.composeapp.generated.resources.hide
import dedun.composeapp.generated.resources.i_have_saved_my_nostr_secret_key_somewhere
import dedun.composeapp.generated.resources.i_understand_that_if_i_lose_this_phone_and_my_key
import dedun.composeapp.generated.resources.keep_this_key_safe_do_not_share_it
import dedun.composeapp.generated.resources.key_forgotten
import dedun.composeapp.generated.resources.loading_preferences
import dedun.composeapp.generated.resources.lose_this_phone_before_you_do_and_the
import dedun.composeapp.generated.resources.no_identity_is_open_on_this_device_so_there
import dedun.composeapp.generated.resources.nostr_secret_key
import dedun.composeapp.generated.resources.remove_the_key_from_this_device_the_profile
import dedun.composeapp.generated.resources.the_nsec_is_the_key_that_signs_as_you_this
import dedun.composeapp.generated.resources.this_device_holds_no_key_for_the_profile
import dedun.composeapp.generated.resources.unlocking_your_key
import dedun.composeapp.generated.resources.you_have_not_backed_up_your_nostr_secret_key
import fr.acinq.phoenix.PhoenixGlobal
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.extensions.hexToNpubHrp
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityWriter
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.NostrSecretViewModel
import com.machankura.dedun.compose.ui.view.state.NostrSecretUIState

/**
 * The nostr secret key behind a bare-key identity, shown once and on request.
 *
 * `RecoveryPhraseScreen` with the word grid replaced by the nsec, for the identity kind
 * that has a key and no phrase. The reveal is a separate step from opening the screen
 * for the same reason: reading the key goes to the keystore, and nobody should walk past
 * a phone that is quietly displaying it. Below the backup section is the one thing this
 * screen has that the phrase screen does not: the way to take the key off the device.
 */
@Composable
fun NostrSecretScreen(
    phoenixGlobal: PhoenixGlobal,
    activeIdentityStateFlow: StateFlow<Identity?>,
    nostrRepository: NostrRepository,
    forgetNostrCredential: suspend (Identity) -> IdentityWriter.ForgetNostrCredentialResult,
    hideIdentityMetadata: suspend (Identity) -> Unit,
    onNavigateBack: () -> Unit,
    onForgotten: () -> Unit,
) {
    val viewModel: NostrSecretViewModel = viewModel(
        factory = NostrSecretViewModel.factory(
            phoenixGlobal = phoenixGlobal,
            activeIdentityStateFlow = activeIdentityStateFlow,
            nostrRepository = nostrRepository,
            forgetNostrCredential = forgetNostrCredential,
            hideIdentityMetadata = hideIdentityMetadata,
        )
    )

    val uiState by viewModel.uiState.collectAsState()
    val forgetting by viewModel.forgetting.collectAsState()
    val isBareKeyIdentity by viewModel.isBareKeyIdentity.collectAsState()
    val isBackupDone by viewModel.isBackupDone.collectAsState()
    val isDisclaimerRead by viewModel.isDisclaimerRead.collectAsState()
    val showBackupNotice by viewModel.showBackupNotice.collectAsState()
    val activeIdentity by activeIdentityStateFlow.collectAsState()

    // Both composable, and the handlers that show these are not: read above them.
    val notify = rememberNotifier(rememberCoroutineScope())
    val keyForgotten = stringResource(Res.string.key_forgotten)

    NostrSecretContent(
        uiState = uiState,
        forgetting = forgetting,
        npub = activeIdentity?.nostrPublicKey?.hexToNpubHrp(),
        canForget = isBareKeyIdentity,
        isBackupDone = isBackupDone,
        isDisclaimerRead = isDisclaimerRead,
        showBackupNotice = showBackupNotice,
        onReveal = viewModel::revealNostrSecret,
        onHide = viewModel::hideNostrSecret,
        onBackupDoneChange = viewModel::setBackupDone,
        onDisclaimerReadChange = viewModel::setDisclaimerRead,
        onAskToForget = viewModel::askToForgetKey,
        onCancelForget = viewModel::cancelForgetKey,
        onForget = {
            viewModel.forgetKey {
                notify(keyForgotten)
                onForgotten()
            }
        },
        onNavigateBack = onNavigateBack,
    )
}

/**
 * The screen once the state and the preferences have been read. Everything the view
 * model owns arrives as a value or a callback, so a preview can render any branch
 * without a key on disk.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun NostrSecretContent(
    uiState: NostrSecretUIState,
    forgetting: NostrSecretUIState.Forgetting,
    npub: String?,
    canForget: Boolean,
    isBackupDone: Boolean?,
    isDisclaimerRead: Boolean?,
    showBackupNotice: Boolean,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onBackupDoneChange: (Boolean) -> Unit,
    onDisclaimerReadChange: (Boolean) -> Unit,
    onAskToForget: () -> Unit,
    onCancelForget: () -> Unit,
    onForget: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    if (forgetting is NostrSecretUIState.Forgetting.Confirming) {
        AlertDialog(
            onDismissRequest = onCancelForget,
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = Decorative) },
            title = { Text(stringResource(Res.string.forget_this_key_question)) },
            text = { Text(stringResource(Res.string.bennu_will_delete_the_nsec_for_s_from_this, npub ?: "")) },
            confirmButton = {
                TextButton(onClick = onForget) { Text(stringResource(Res.string.forget)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelForget) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(Res.string.nostr_secret_key)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .readableContent()
                .padding(horizontal = MaterialTheme.spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.containerPadding),
                    text = stringResource(Res.string.the_nsec_is_the_key_that_signs_as_you_this),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (showBackupNotice) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.spacing.containerPadding),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = Decorative)

                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))

                            Column {
                                Text(
                                    text = stringResource(Res.string.you_have_not_backed_up_your_nostr_secret_key),
                                    style = MaterialTheme.typography.titleSmall
                                )

                                Text(
                                    text = stringResource(Res.string.lose_this_phone_before_you_do_and_the),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.spacing.containerPadding),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (uiState) {
                            is NostrSecretUIState.Hidden -> {
                                Button(onClick = onReveal) {
                                    Icon(Icons.Default.Visibility, contentDescription = Decorative)
                                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                    Text(text = stringResource(Res.string.display_nostr_secret_key))
                                }
                            }

                            is NostrSecretUIState.Revealing -> {
                                LoadingDataIndicator(
                                    fillScreen = false,
                                    text = stringResource(Res.string.unlocking_your_key)
                                )
                            }

                            is NostrSecretUIState.Revealed -> {
                                RevealedNsec(nsec = uiState.nsec)

                                TextButton(onClick = onHide) {
                                    Icon(Icons.Default.VisibilityOff, contentDescription = Decorative)
                                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                    Text(text = stringResource(Res.string.hide))
                                }
                            }

                            is NostrSecretUIState.Error -> {
                                ErrorState(
                                    message = when (uiState) {
                                        is NostrSecretUIState.Error.NoActiveIdentity ->
                                            stringResource(Res.string.no_identity_is_open_on_this_device_so_there)
                                        is NostrSecretUIState.Error.NoKeyForThisIdentity ->
                                            stringResource(Res.string.this_device_holds_no_key_for_the_profile)
                                        is NostrSecretUIState.Error.KeysUnreadable ->
                                            stringResource(Res.string.could_not_unlock_your_key_please_try)
                                    },
                                    // Retrying without an identity, or for one whose key is in
                                    // the seed rather than here, cannot help.
                                    onRetry = when (uiState) {
                                        is NostrSecretUIState.Error.KeysUnreadable -> onReveal
                                        else -> null
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(Res.string.backup_confirmation),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    if (isBackupDone == null || isDisclaimerRead == null) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.spacing.containerPadding),
                            text = stringResource(Res.string.loading_preferences),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        BackupCheckbox(
                            checked = isBackupDone,
                            text = stringResource(Res.string.i_have_saved_my_nostr_secret_key_somewhere),
                            onCheckedChange = onBackupDoneChange
                        )

                        BackupCheckbox(
                            checked = isDisclaimerRead,
                            text = stringResource(Res.string.i_understand_that_if_i_lose_this_phone_and_my_key),
                            onCheckedChange = onDisclaimerReadChange
                        )
                    }
                }
            }

            if (canForget) {
                item {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.sectionGap))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onAskToForget,
                        enabled = forgetting !is NostrSecretUIState.Forgetting.InProgress,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.spacing.containerPadding),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DeleteForever, contentDescription = Decorative)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                Text(
                                    text = stringResource(Res.string.forget_this_key),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Text(
                                text = when (forgetting) {
                                    is NostrSecretUIState.Forgetting.Failed ->
                                        stringResource(Res.string.could_not_forget_the_key_please_try_again)
                                    else -> stringResource(Res.string.remove_the_key_from_this_device_the_profile)
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.containerPadding))
            }
        }
    }
}

/** The key itself, selectable and with a copy action, since nobody transcribes sixty-three characters by hand. */
@Composable
private fun RevealedNsec(nsec: String) {
    val clipboardManager = LocalClipboardManager.current
    val notify = rememberNotifier(rememberCoroutineScope())
    val copied = stringResource(Res.string.copied_the_nsec)

    Text(
        text = stringResource(Res.string.keep_this_key_safe_do_not_share_it),
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center
    )

    SelectionContainer {
        Text(
            text = nsec,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            textAlign = TextAlign.Center
        )
    }

    TextButton(
        onClick = {
            clipboardManager.setText(AnnotatedString(nsec))
            notify(copied)
        }
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
        Text(text = stringResource(Res.string.copy_nsec))
    }
}

@Composable
private fun BackupCheckbox(
    checked: Boolean,
    text: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row toggles, so the row is the target and has to carry the 48dp
            // the Checkbox alone would.
            .minimumInteractiveComponentSize()
            .clickable { onCheckedChange(!checked) }
            .padding(
                horizontal = MaterialTheme.spacing.containerPadding,
                vertical = MaterialTheme.spacing.space150
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))

        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@ConformancePreviews
@Composable
private fun NostrSecretScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            NostrSecretContent(
                uiState = NostrSecretUIState.Revealed("nsec1" + "q".repeat(58)),
                forgetting = NostrSecretUIState.Forgetting.Idle,
                npub = "npub1" + "q".repeat(58),
                canForget = true,
                isBackupDone = false,
                isDisclaimerRead = true,
                showBackupNotice = true,
                onReveal = {},
                onHide = {},
                onBackupDoneChange = {},
                onDisclaimerReadChange = {},
                onAskToForget = {},
                onCancelForget = {},
                onForget = {},
                onNavigateBack = {},
            )
        }
    }
}
