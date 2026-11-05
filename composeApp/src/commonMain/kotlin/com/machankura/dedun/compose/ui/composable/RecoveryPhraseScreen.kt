package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.acinq.phoenix.PhoenixGlobal
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.flow.StateFlow
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.backup_confirmation
import dedun.composeapp.generated.resources.bip39_seed_with_the_standard_bip84
import dedun.composeapp.generated.resources.could_not_unlock_your_phrase_please_try
import dedun.composeapp.generated.resources.display_recovery_phrase
import dedun.composeapp.generated.resources.hide
import dedun.composeapp.generated.resources.i_have_saved_my_recovery_phrase_somewhere
import dedun.composeapp.generated.resources.i_understand_that_if_i_lose_this_phone_and
import dedun.composeapp.generated.resources.keep_this_phrase_safe_do_not_share_it
import dedun.composeapp.generated.resources.loading_preferences
import dedun.composeapp.generated.resources.lose_this_phone_before_you_do_and_the
import dedun.composeapp.generated.resources.no_wallet_is_open_on_this_device_so_there
import dedun.composeapp.generated.resources.recovery_phrase
import dedun.composeapp.generated.resources.the_recovery_phrase_sometimes_called_a_seed
import dedun.composeapp.generated.resources.this_device_holds_no_phrase_for_the_profile
import dedun.composeapp.generated.resources.unlocking_your_phrase
import dedun.composeapp.generated.resources.wavelength_recovery_phrase_also
import dedun.composeapp.generated.resources.word_position
import dedun.composeapp.generated.resources.you_have_not_backed_up_your_recovery_phrase
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.RecoveryPhraseViewModel
import com.machankura.dedun.compose.ui.view.state.RecoveryPhraseUIState

/**
 * The twelve words behind this profile, shown once and on request.
 *
 * The reveal is deliberately a separate step from opening the screen: reading
 * the seed goes to the keystore, and nobody should walk past a phone that is
 * quietly displaying it.
 */
@Composable
fun RecoveryPhraseScreen(
    phoenixGlobal: PhoenixGlobal,
    activeIdentityStateFlow: StateFlow<Identity?>,
    onNavigateBack: () -> Unit,
) {
    val recoveryPhraseViewModel: RecoveryPhraseViewModel = viewModel(
        factory = RecoveryPhraseViewModel.factory(
            phoenixGlobal = phoenixGlobal,
            activeIdentityStateFlow = activeIdentityStateFlow
        )
    )

    val recoveryPhraseUIState by recoveryPhraseViewModel.recoveryPhraseUIState.collectAsState()
    val isBackupDone by recoveryPhraseViewModel.isBackupDone.collectAsState()
    val isDisclaimerRead by recoveryPhraseViewModel.isDisclaimerRead.collectAsState()
    val showBackupNotice by recoveryPhraseViewModel.showBackupNotice.collectAsState()

    RecoveryPhraseContent(
        recoveryPhraseUIState = recoveryPhraseUIState,
        isBackupDone = isBackupDone,
        isDisclaimerRead = isDisclaimerRead,
        showBackupNotice = showBackupNotice,
        onRevealRecoveryPhrase = recoveryPhraseViewModel::revealRecoveryPhrase,
        onHideRecoveryPhrase = recoveryPhraseViewModel::hideRecoveryPhrase,
        onBackupDoneChange = recoveryPhraseViewModel::setBackupDone,
        onDisclaimerReadChange = recoveryPhraseViewModel::setDisclaimerRead,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * The screen once the state and the two backup preferences have been read.
 * Everything the view model owns arrives as a value or a callback, so a preview
 * can render any branch without a seed on disk.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RecoveryPhraseContent(
    recoveryPhraseUIState: RecoveryPhraseUIState,
    isBackupDone: Boolean?,
    isDisclaimerRead: Boolean?,
    showBackupNotice: Boolean,
    onRevealRecoveryPhrase: () -> Unit,
    onHideRecoveryPhrase: () -> Unit,
    onBackupDoneChange: (Boolean) -> Unit,
    onDisclaimerReadChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.recovery_phrase)
                    )
                },
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
                    text = stringResource(Res.string.the_recovery_phrase_sometimes_called_a_seed),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // The same words open the profile's wallet: docs/wavelength-wallet.md, D1 and D11.
            item {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(Res.string.wavelength_recovery_phrase_also),
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
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = Decorative
                            )

                            Spacer(
                                modifier = Modifier.width(MaterialTheme.spacing.space125)
                            )

                            Column {
                                Text(
                                    text = stringResource(Res.string.you_have_not_backed_up_your_recovery_phrase),
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
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.spacing.containerPadding),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (recoveryPhraseUIState) {
                            is RecoveryPhraseUIState.Hidden -> {
                                Button(
                                    onClick = onRevealRecoveryPhrase
                                ) {
                                    Icon(
                                        Icons.Default.Visibility,
                                        contentDescription = Decorative
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        text = stringResource(Res.string.display_recovery_phrase)
                                    )
                                }
                            }

                            is RecoveryPhraseUIState.Revealing -> {
                                LoadingDataIndicator(
                                    fillScreen = false,
                                    text = stringResource(Res.string.unlocking_your_phrase)
                                )
                            }

                            is RecoveryPhraseUIState.Revealed -> {
                                RecoveryPhraseWords(
                                    words = recoveryPhraseUIState.words
                                )

                                TextButton(
                                    onClick = onHideRecoveryPhrase
                                ) {
                                    Icon(
                                        Icons.Default.VisibilityOff,
                                        contentDescription = Decorative
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        text = stringResource(Res.string.hide)
                                    )
                                }
                            }

                            is RecoveryPhraseUIState.Error -> {
                                ErrorState(
                                    message = when (recoveryPhraseUIState) {
                                        is RecoveryPhraseUIState.Error.NoActiveWallet ->
                                            stringResource(Res.string.no_wallet_is_open_on_this_device_so_there)
                                        is RecoveryPhraseUIState.Error.NoPhraseForThisWallet ->
                                            stringResource(Res.string.this_device_holds_no_phrase_for_the_profile)
                                        is RecoveryPhraseUIState.Error.SeedUnreadable ->
                                            stringResource(Res.string.could_not_unlock_your_phrase_please_try)
                                    },
                                    // Retrying a wallet that is not open cannot help: the
                                    // seed file has nothing to match against until one is.
                                    onRetry = when (recoveryPhraseUIState) {
                                        is RecoveryPhraseUIState.Error.NoActiveWallet -> null
                                        else -> onRevealRecoveryPhrase
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
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isBackupDone == null || isDisclaimerRead == null) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.spacing.containerPadding),
                            text = stringResource(Res.string.loading_preferences),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        RecoveryPhraseCheckbox(
                            checked = isBackupDone,
                            text = stringResource(Res.string.i_have_saved_my_recovery_phrase_somewhere),
                            onCheckedChange = onBackupDoneChange
                        )

                        RecoveryPhraseCheckbox(
                            checked = isDisclaimerRead,
                            text = stringResource(Res.string.i_understand_that_if_i_lose_this_phone_and),
                            onCheckedChange = onDisclaimerReadChange
                        )
                    }
                }
            }

            item {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = MaterialTheme.spacing.containerPadding),
                    text = stringResource(Res.string.bip39_seed_with_the_standard_bip84),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * The words themselves, numbered and in two columns so they can be checked off
 * against what the user writes down.
 */
@Composable
private fun RecoveryPhraseWords(words: List<String>) {
    Text(
        text = stringResource(Res.string.keep_this_phrase_safe_do_not_share_it),
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center
    )

    // Read down the left column and then the right, the way the words are written on a card:
    // #1..#6 beside #7..#12 rather than odds beside evens.
    val pairedWords = remember(words) {
        val half = (words.size + 1) / 2
        List(half) { index ->
            words[index] to words.getOrNull(index + half)
        }
    }

    Column(
        modifier = Modifier.widthIn(max = 260.dp),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space75)
    ) {
        pairedWords.forEachIndexed { index, (first, second) ->
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                RecoveryPhraseWord(
                    position = index + 1,
                    word = first,
                    modifier = Modifier.weight(1f)
                )

                RecoveryPhraseWord(
                    position = index + pairedWords.size + 1,
                    word = second,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RecoveryPhraseWord(
    position: Int,
    word: String?,
    modifier: Modifier = Modifier,
) {
    if (word == null) {
        Spacer(modifier = modifier)
        return
    }

    Row(
        modifier = modifier
    ) {
        Text(
            modifier = Modifier.width(28.dp),
            text = stringResource(Res.string.word_position, position),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(
            modifier = Modifier.width(MaterialTheme.spacing.space75)
        )

        Text(
            text = word,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RecoveryPhraseCheckbox(
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
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )

        Spacer(
            modifier = Modifier.width(MaterialTheme.spacing.space125)
        )

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@ConformancePreviews
@Composable
private fun RecoveryPhraseScreenPreview() {
    com.machankura.dedun.compose.ui.theme.DedunTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            RecoveryPhraseContent(
                recoveryPhraseUIState = RecoveryPhraseUIState.Revealed(
                    words = listOf(
                        "abandon", "ability", "able", "about", "above", "absent",
                        "absorb", "abstract", "absurd", "abuse", "access", "accident"
                    )
                ),
                isBackupDone = false,
                isDisclaimerRead = true,
                showBackupNotice = true,
                onRevealRecoveryPhrase = {},
                onHideRecoveryPhrase = {},
                onBackupDoneChange = {},
                onDisclaimerReadChange = {},
                onNavigateBack = {},
            )
        }
    }
}

@ConformancePreviews
@Composable
private fun RecoveryPhraseScreenHiddenPreview() {
    com.machankura.dedun.compose.ui.theme.DedunTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            RecoveryPhraseContent(
                recoveryPhraseUIState = RecoveryPhraseUIState.Hidden,
                isBackupDone = false,
                isDisclaimerRead = false,
                showBackupNotice = true,
                onRevealRecoveryPhrase = {},
                onHideRecoveryPhrase = {},
                onBackupDoneChange = {},
                onDisclaimerReadChange = {},
                onNavigateBack = {},
            )
        }
    }
}
