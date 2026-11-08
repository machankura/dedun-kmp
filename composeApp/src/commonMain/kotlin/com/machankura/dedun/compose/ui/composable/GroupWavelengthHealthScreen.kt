package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthDaemonSource
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.composable.widgets.wallet.GroupWavelengthGlossaryButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupWavelengthWalletViewModel
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WavelengthPlatform
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthHealth
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.couldn_t_propose_this
import dedun.composeapp.generated.resources.group_wavelength_advanced
import dedun.composeapp.generated.resources.group_wavelength_exit_title
import dedun.composeapp.generated.resources.group_wavelength_health_coins
import dedun.composeapp.generated.resources.group_wavelength_health_coins_attention
import dedun.composeapp.generated.resources.group_wavelength_health_coins_fine
import dedun.composeapp.generated.resources.group_wavelength_health_coins_problem
import dedun.composeapp.generated.resources.group_wavelength_health_departed
import dedun.composeapp.generated.resources.group_wavelength_health_departed_fine
import dedun.composeapp.generated.resources.group_wavelength_health_departed_problem
import dedun.composeapp.generated.resources.group_wavelength_health_devices
import dedun.composeapp.generated.resources.group_wavelength_health_devices_attention
import dedun.composeapp.generated.resources.group_wavelength_health_devices_fine
import dedun.composeapp.generated.resources.group_wavelength_health_devices_problem
import dedun.composeapp.generated.resources.group_wavelength_health_fees
import dedun.composeapp.generated.resources.group_wavelength_health_fees_attention
import dedun.composeapp.generated.resources.group_wavelength_health_fees_fine
import dedun.composeapp.generated.resources.group_wavelength_health_fees_problem
import dedun.composeapp.generated.resources.group_wavelength_health_leaving
import dedun.composeapp.generated.resources.group_wavelength_health_title
import dedun.composeapp.generated.resources.group_wavelength_health_who_runs_it
import dedun.composeapp.generated.resources.group_wavelength_health_who_runs_it_attention
import dedun.composeapp.generated.resources.group_wavelength_health_who_runs_it_fine
import dedun.composeapp.generated.resources.group_wavelength_health_who_runs_it_problem
import dedun.composeapp.generated.resources.run_this_wallet_on_my_device
import dedun.composeapp.generated.resources.see_the_key_ceremony
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Whether this wallet is all right, in five lines.
 *
 * The guide's security checklist, with the wallet's real obligations in place of its settings (B7).
 * Nothing on it is new information: every item is derived from what the wallet screen already holds.
 * What is new is that a member can answer *does anything need doing?* without reading five cards and
 * knowing what each of them means -- and that each line says **what happens if nobody does it**, which
 * is the part the cards never said.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWavelengthHealthScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    repository: GroupWavelengthRepository,
    daemonSource: GroupWavelengthDaemonSource,
    onOpenAdvanced: () -> Unit = {},
    onOpenCeremony: () -> Unit = {},
    /** Volunteering to run the wallet is a proposal the group signs, so it ends in a signing session. */
    onOpenSession: (sessionId: String) -> Unit = {},
    onOpenExit: () -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupWavelengthWalletViewModel = viewModel(
        key = "wavelength-health:$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}",
        factory = GroupWavelengthWalletViewModel.factory(activeUserPublicKey, chatRoomId, chain, activeIdentityStateFlow, chatRepository, repository, WavelengthPlatform.client != null, daemonSource),
    )
    LaunchedEffect(true) { viewModel.initiate() }
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.couldn_t_propose_this)
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.group_wavelength_health_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
                actions = { GroupWavelengthGlossaryButton() },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                is GroupWavelengthWalletUIState.Opened -> GroupWavelengthHealthContent(
                    modifier = Modifier.padding(padding),
                    state = state,
                    onOpenAdvanced = onOpenAdvanced,
                    onOpenCeremony = onOpenCeremony,
                    onNominate = { viewModel.nominateSelf(onSession = onOpenSession, onFailure = { notify(couldNotPropose) }) },
                    onOpenExit = onOpenExit,
                )
                is GroupWavelengthWalletUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = { viewModel.initiate() })
                else -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
            }
        }
    }
}

@Composable
internal fun GroupWavelengthHealthContent(
    modifier: Modifier,
    state: GroupWavelengthWalletUIState.Opened,
    onOpenAdvanced: () -> Unit = {},
    onOpenCeremony: () -> Unit = {},
    onNominate: () -> Unit = {},
    onOpenExit: () -> Unit = {},
) {
    val items = GroupWavelengthHealth.of(state)
    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        items(items, key = { it.kind.name }) { item ->
            ListItem(
                leadingContent = {
                    // Described, not decorative: green, amber and red are the whole of what this icon
                    // says, and a member who cannot tell them apart would otherwise read five
                    // identical rows.
                    Icon(icon(item.standing), contentDescription = stringResource(title(item.kind)), tint = tint(item.standing))
                },
                headlineContent = { Text(stringResource(title(item.kind))) },
                supportingContent = { Text(stringResource(message(item))) },
                trailingContent = fix(item.fix, onOpenAdvanced, onOpenCeremony, onNominate),
            )
        }
        // The way out, named here rather than only under *Advanced* (B10). "Can I get out?" is a
        // question about this wallet's health, and a member should not have to guess that the answer is
        // behind a word that means "things you probably should not touch".
        item {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                Text(stringResource(Res.string.group_wavelength_health_leaving), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The link only where the exit is actually offered: a button to a screen that refuses
                // is worse than a sentence that says what would happen.
                if (GroupWavelengthRollout.offersUnilateralExit(state.wallet.statement.chain) && state.isOperator) {
                    TextButton(onClick = onOpenExit) { Text(stringResource(Res.string.group_wavelength_exit_title)) }
                }
            }
        }
    }
}

private fun icon(standing: GroupWavelengthHealth.Standing): ImageVector = when (standing) {
    GroupWavelengthHealth.Standing.FINE -> Icons.Default.CheckCircle
    GroupWavelengthHealth.Standing.ATTENTION -> Icons.Default.WarningAmber
    GroupWavelengthHealth.Standing.PROBLEM -> Icons.Default.ErrorOutline
}

@Composable
private fun tint(standing: GroupWavelengthHealth.Standing): Color = when (standing) {
    // Green from the theme's brand pair rather than a literal: a tick has to read as "fine" in all six
    // schemes, and `primary` is the only role that does.
    GroupWavelengthHealth.Standing.FINE -> MaterialTheme.colorScheme.primary
    GroupWavelengthHealth.Standing.ATTENTION -> MaterialTheme.colorScheme.tertiary
    GroupWavelengthHealth.Standing.PROBLEM -> MaterialTheme.colorScheme.error
}

private fun title(kind: GroupWavelengthHealth.Kind): StringResource = when (kind) {
    GroupWavelengthHealth.Kind.COINS -> Res.string.group_wavelength_health_coins
    GroupWavelengthHealth.Kind.DEVICES -> Res.string.group_wavelength_health_devices
    GroupWavelengthHealth.Kind.WHO_RUNS_IT -> Res.string.group_wavelength_health_who_runs_it
    GroupWavelengthHealth.Kind.FEES -> Res.string.group_wavelength_health_fees
    GroupWavelengthHealth.Kind.DEPARTED -> Res.string.group_wavelength_health_departed
}

/**
 * One sentence per obligation per standing, and no shared default.
 *
 * Fourteen strings for five items, because what to do about a fee budget four fifths spent is nothing
 * like what to do about one that is gone, and a checklist whose amber and red say the same thing is a
 * checklist with two colours and one message.
 */
internal fun message(item: GroupWavelengthHealth.Item): StringResource = when (item.kind) {
    GroupWavelengthHealth.Kind.COINS -> when (item.standing) {
        GroupWavelengthHealth.Standing.FINE -> Res.string.group_wavelength_health_coins_fine
        GroupWavelengthHealth.Standing.ATTENTION -> Res.string.group_wavelength_health_coins_attention
        GroupWavelengthHealth.Standing.PROBLEM -> Res.string.group_wavelength_health_coins_problem
    }
    GroupWavelengthHealth.Kind.DEVICES -> when (item.standing) {
        GroupWavelengthHealth.Standing.FINE -> Res.string.group_wavelength_health_devices_fine
        GroupWavelengthHealth.Standing.ATTENTION -> Res.string.group_wavelength_health_devices_attention
        GroupWavelengthHealth.Standing.PROBLEM -> Res.string.group_wavelength_health_devices_problem
    }
    GroupWavelengthHealth.Kind.WHO_RUNS_IT -> when (item.standing) {
        GroupWavelengthHealth.Standing.FINE -> Res.string.group_wavelength_health_who_runs_it_fine
        GroupWavelengthHealth.Standing.ATTENTION -> Res.string.group_wavelength_health_who_runs_it_attention
        GroupWavelengthHealth.Standing.PROBLEM -> Res.string.group_wavelength_health_who_runs_it_problem
    }
    GroupWavelengthHealth.Kind.FEES -> when (item.standing) {
        GroupWavelengthHealth.Standing.FINE -> Res.string.group_wavelength_health_fees_fine
        GroupWavelengthHealth.Standing.ATTENTION -> Res.string.group_wavelength_health_fees_attention
        GroupWavelengthHealth.Standing.PROBLEM -> Res.string.group_wavelength_health_fees_problem
    }
    // Never amber: either nobody who left holds a share, or the money has to move.
    GroupWavelengthHealth.Kind.DEPARTED -> when (item.standing) {
        GroupWavelengthHealth.Standing.FINE -> Res.string.group_wavelength_health_departed_fine
        else -> Res.string.group_wavelength_health_departed_problem
    }
}

/** The thing that fixes it, where it is somewhere a member can be sent. */
private fun fix(
    fix: GroupWavelengthHealth.Fix,
    onOpenAdvanced: () -> Unit,
    onOpenCeremony: () -> Unit,
    onNominate: () -> Unit,
): (@Composable () -> Unit)? = when (fix) {
    GroupWavelengthHealth.Fix.NOTHING -> null
    GroupWavelengthHealth.Fix.ADVANCED -> { { TextButton(onClick = onOpenAdvanced) { Text(stringResource(Res.string.group_wavelength_advanced)) } } }
    GroupWavelengthHealth.Fix.CEREMONY -> { { TextButton(onClick = onOpenCeremony) { Text(stringResource(Res.string.see_the_key_ceremony)) } } }
    GroupWavelengthHealth.Fix.THIS_DEVICE_CAN_RUN_IT -> { { TextButton(onClick = onNominate) { Text(stringResource(Res.string.run_this_wallet_on_my_device)) } } }
}
