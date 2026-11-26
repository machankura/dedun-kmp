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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.acinq.phoenix.PhoenixGlobal
import kotlinx.coroutines.flow.StateFlow
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCode
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.backup_confirmation
import dedun.composeapp.generated.resources.block_number
import dedun.composeapp.generated.resources.check_backup
import dedun.composeapp.generated.resources.could_not_unlock_your_recovery_code
import dedun.composeapp.generated.resources.display_recovery_code
import dedun.composeapp.generated.resources.hide
import dedun.composeapp.generated.resources.i_understand_that_if_i_lose_this_phone_and_my_code
import dedun.composeapp.generated.resources.keep_this_code_safe_do_not_share_it
import dedun.composeapp.generated.resources.loading_preferences
import dedun.composeapp.generated.resources.lose_this_phone_before_you_do_and_the
import dedun.composeapp.generated.resources.no_profile_is_open_so_there_is_no_code_to_show
import dedun.composeapp.generated.resources.recovery_code
import dedun.composeapp.generated.resources.show_the_recovery_phrase_instead
import dedun.composeapp.generated.resources.show_the_words
import dedun.composeapp.generated.resources.the_ninth_block_is_part_of_the_code
import dedun.composeapp.generated.resources.the_recovery_code_is_nine_blocks
import dedun.composeapp.generated.resources.these_24_words_are_this_profile_for_other_apps
import dedun.composeapp.generated.resources.this_device_holds_no_code_for_this_profile
import dedun.composeapp.generated.resources.those_blocks_do_not_match
import dedun.composeapp.generated.resources.type_blocks_from_what_you_wrote_down
import dedun.composeapp.generated.resources.unlocking_your_code
import dedun.composeapp.generated.resources.use_this_profile_in_another_app
import dedun.composeapp.generated.resources.word_position
import dedun.composeapp.generated.resources.you_have_not_backed_up_your_recovery_code
import dedun.composeapp.generated.resources.your_backup_matches
import dedun.composeapp.generated.resources.your_code_could_not_be_read_to_check
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.RecoveryCodeViewModel
import com.machankura.dedun.compose.ui.view.state.BackupCheckState
import com.machankura.dedun.compose.ui.view.state.RecoveryCodeUIState
import org.jetbrains.compose.resources.stringResource

/**
 * The recovery code behind this profile, shown on request, and a check that the copy on
 * paper is right. See docs/recovery-code-v3.md, Phase 6.
 *
 * The reveal is a separate press, as the phrase's is: reading the code goes to the
 * keystore, and nobody should walk past a phone that is quietly showing it. Below the code,
 * behind a press of its own, are the 24 words it is -- for other apps, not a second backup.
 */
@Composable
fun RecoveryCodeScreen(
    phoenixGlobal: PhoenixGlobal,
    activeIdentityStateFlow: StateFlow<Identity?>,
    onNavigateBack: () -> Unit,
    onShowRecoveryPhrase: () -> Unit,
) {
    val viewModel: RecoveryCodeViewModel = viewModel(
        factory = RecoveryCodeViewModel.factory(
            phoenixGlobal = phoenixGlobal,
            activeIdentityStateFlow = activeIdentityStateFlow,
        )
    )

    val uiState by viewModel.uiState.collectAsState()
    val backupCheck by viewModel.backupCheck.collectAsState()
    val isBackupDone by viewModel.isBackupDone.collectAsState()
    val isDisclaimerRead by viewModel.isDisclaimerRead.collectAsState()
    val showBackupNotice by viewModel.showBackupNotice.collectAsState()

    RecoveryCodeContent(
        uiState = uiState,
        backupCheck = backupCheck,
        checkBlocks = viewModel.checkBlocks,
        isBackupDone = isBackupDone,
        isDisclaimerRead = isDisclaimerRead,
        showBackupNotice = showBackupNotice,
        onReveal = viewModel::reveal,
        onHide = viewModel::hide,
        onRevealWords = viewModel::revealWords,
        onHideWords = viewModel::hideWords,
        onCheckBackup = viewModel::checkBackup,
        onDisclaimerReadChange = viewModel::setDisclaimerRead,
        onShowRecoveryPhrase = onShowRecoveryPhrase,
        onNavigateBack = onNavigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecoveryCodeContent(
    uiState: RecoveryCodeUIState,
    backupCheck: BackupCheckState,
    checkBlocks: Pair<Int, Int>,
    isBackupDone: Boolean?,
    isDisclaimerRead: Boolean?,
    showBackupNotice: Boolean,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onRevealWords: () -> Unit,
    onHideWords: () -> Unit,
    onCheckBackup: (String, String) -> Unit,
    onDisclaimerReadChange: (Boolean) -> Unit,
    onShowRecoveryPhrase: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(Res.string.recovery_code)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .readableContent()
                .padding(horizontal = MaterialTheme.spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.containerPadding),
                    text = stringResource(Res.string.the_recovery_code_is_nine_blocks),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (showBackupNotice) {
                item { BackupNotice() }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.spacing.containerPadding),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (uiState) {
                            is RecoveryCodeUIState.Hidden -> Button(onClick = onReveal) {
                                Icon(Icons.Default.Visibility, contentDescription = Decorative)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                Text(text = stringResource(Res.string.display_recovery_code))
                            }

                            is RecoveryCodeUIState.Revealing -> LoadingDataIndicator(
                                fillScreen = false,
                                text = stringResource(Res.string.unlocking_your_code),
                            )

                            is RecoveryCodeUIState.Revealed -> {
                                RecoveryCodeBlocks(uiState.code)
                                TextButton(onClick = onHide) {
                                    Icon(Icons.Default.VisibilityOff, contentDescription = Decorative)
                                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                    Text(text = stringResource(Res.string.hide))
                                }
                            }

                            is RecoveryCodeUIState.Error -> {
                                ErrorState(
                                    message = when (uiState) {
                                        RecoveryCodeUIState.Error.NoActiveProfile ->
                                            stringResource(Res.string.no_profile_is_open_so_there_is_no_code_to_show)
                                        RecoveryCodeUIState.Error.CodesUnreadable ->
                                            stringResource(Res.string.could_not_unlock_your_recovery_code)
                                        RecoveryCodeUIState.Error.NoCodeForThisProfile ->
                                            stringResource(Res.string.this_device_holds_no_code_for_this_profile)
                                    },
                                    // Nothing to match against until a profile is open.
                                    onRetry = if (uiState is RecoveryCodeUIState.Error.NoActiveProfile) null else onReveal,
                                )
                                // Every code profile is also its 24 words in seed.dat, which
                                // a codes file that cannot be read does not touch.
                                if (uiState !is RecoveryCodeUIState.Error.NoActiveProfile) {
                                    TextButton(onClick = onShowRecoveryPhrase) {
                                        Text(text = stringResource(Res.string.show_the_recovery_phrase_instead))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (uiState is RecoveryCodeUIState.Revealed) {
                item {
                    OtherAppsWords(
                        words = uiState.words,
                        onRevealWords = onRevealWords,
                        onHideWords = onHideWords,
                    )
                }
            }

            item {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(Res.string.backup_confirmation),
                    style = MaterialTheme.typography.titleMedium,
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
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        BackupCheck(
                            checkBlocks = checkBlocks,
                            state = if (isBackupDone && backupCheck is BackupCheckState.Idle) BackupCheckState.Matched else backupCheck,
                            onCheck = onCheckBackup,
                        )
                        DisclaimerCheckbox(
                            checked = isDisclaimerRead,
                            onCheckedChange = onDisclaimerReadChange,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.padding(bottom = MaterialTheme.spacing.containerPadding)) }
        }
    }
}

@Composable
private fun BackupNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.containerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
            Column {
                Text(
                    text = stringResource(Res.string.you_have_not_backed_up_your_recovery_code),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(Res.string.lose_this_phone_before_you_do_and_the),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Nine blocks in three rows of three, numbered. A grid rather than one line, because a
 * person copying writes block by block and checks block by block -- and a ninth block on a
 * line of its own is the one that gets dropped.
 */
@Composable
private fun RecoveryCodeBlocks(code: RecoveryCode) {
    Text(
        text = stringResource(Res.string.keep_this_code_safe_do_not_share_it),
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
    )
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
        code.blocks.chunked(3).forEachIndexed { row, blocks ->
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)) {
                blocks.forEachIndexed { column, block ->
                    val number = row * 3 + column + 1
                    val label = stringResource(Res.string.block_number, number)
                    Column(
                        modifier = Modifier.semantics { contentDescription = "$label: $block" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(Res.string.word_position, number),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = block,
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
    Text(
        text = stringResource(Res.string.the_ninth_block_is_part_of_the_code),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
}

/**
 * The 24 words the code is, for an app that takes a recovery phrase. Behind a press of its
 * own and with a sentence that says what they are not: a second backup. They restore the
 * profile in full, and they cannot give the code back.
 */
@Composable
private fun OtherAppsWords(
    words: List<String>?,
    onRevealWords: () -> Unit,
    onHideWords: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        ) {
            Text(
                text = stringResource(Res.string.use_this_profile_in_another_app),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(Res.string.these_24_words_are_this_profile_for_other_apps),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (words == null) {
                OutlinedButton(modifier = Modifier.align(Alignment.End), onClick = onRevealWords) {
                    Text(text = stringResource(Res.string.show_the_words))
                }
            } else {
                Text(
                    text = words.mapIndexed { index, word -> "${index + 1}. $word" }.joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                TextButton(modifier = Modifier.align(Alignment.End), onClick = onHideWords) {
                    Text(text = stringResource(Res.string.hide))
                }
            }
        }
    }
}

/** Two blocks typed back from the paper. A match records the backup; nothing else does. */
@Composable
private fun BackupCheck(
    checkBlocks: Pair<Int, Int>,
    state: BackupCheckState,
    onCheck: (String, String) -> Unit,
) {
    val first = rememberTextFieldState()
    val second = rememberTextFieldState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MaterialTheme.spacing.containerPadding),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        if (state is BackupCheckState.Matched) {
            Text(text = stringResource(Res.string.your_backup_matches), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(
            text = stringResource(Res.string.type_blocks_from_what_you_wrote_down, checkBlocks.first, checkBlocks.second),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
            listOf(first to checkBlocks.first, second to checkBlocks.second).forEach { (field, number) ->
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    state = field,
                    label = { Text(text = stringResource(Res.string.block_number, number), maxLines = 1) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    isError = state is BackupCheckState.Mismatch,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                )
            }
        }
        when (state) {
            BackupCheckState.Mismatch -> Text(
                text = stringResource(Res.string.those_blocks_do_not_match),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            BackupCheckState.Unreadable -> Text(
                text = stringResource(Res.string.your_code_could_not_be_read_to_check),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
        Button(
            modifier = Modifier.align(Alignment.End),
            enabled = state !is BackupCheckState.Checking,
            onClick = { onCheck(first.text.toString(), second.text.toString()) },
        ) {
            Text(text = stringResource(Res.string.check_backup))
        }
    }
}

@Composable
private fun DisclaimerCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row toggles, so the row is the target and carries the 48dp.
            .minimumInteractiveComponentSize()
            .clickable { onCheckedChange(!checked) }
            .padding(
                horizontal = MaterialTheme.spacing.containerPadding,
                vertical = MaterialTheme.spacing.space150,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
        Text(
            text = stringResource(Res.string.i_understand_that_if_i_lose_this_phone_and_my_code),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@ConformancePreviews
@Composable
private fun RecoveryCodeScreenPreview() {
    com.machankura.dedun.compose.ui.theme.DedunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            RecoveryCodeContent(
                uiState = RecoveryCodeUIState.Revealed(RecoveryCode.generate()),
                backupCheck = BackupCheckState.Idle,
                checkBlocks = 3 to 7,
                isBackupDone = false,
                isDisclaimerRead = false,
                showBackupNotice = true,
                onReveal = {},
                onHide = {},
                onRevealWords = {},
                onHideWords = {},
                onCheckBackup = { _, _ -> },
                onDisclaimerReadChange = {},
                onShowRecoveryPhrase = {},
                onNavigateBack = {},
            )
        }
    }
}
