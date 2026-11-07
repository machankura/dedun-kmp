package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.machankura.dedun.compose.repository.GroupWavelengthDaemonSource
import com.machankura.dedun.compose.repository.GroupWavelengthExitPlan
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.group_wavelength_exit_coins_s
import dedun.composeapp.generated.resources.group_wavelength_exit_confirm
import dedun.composeapp.generated.resources.group_wavelength_exit_confirm_question
import dedun.composeapp.generated.resources.group_wavelength_exit_couldnt
import dedun.composeapp.generated.resources.group_wavelength_exit_needs_funding_s
import dedun.composeapp.generated.resources.group_wavelength_exit_not_here
import dedun.composeapp.generated.resources.group_wavelength_exit_stage_s_s_s
import dedun.composeapp.generated.resources.group_wavelength_exit_start
import dedun.composeapp.generated.resources.group_wavelength_exit_title
import dedun.composeapp.generated.resources.group_wavelength_exit_warning
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Leaving without the Ark operator, on the device that runs the wallet. It has never been run, and this says so first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWavelengthExitScreen(
    chatRoomId: String,
    chain: Chain,
    wallet: Long,
    daemonSource: GroupWavelengthDaemonSource,
    onNavigateBack: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var plan by remember { mutableStateOf<GroupWavelengthExitPlan?>(null) }
    val scope = rememberCoroutineScope()
    val notify = rememberNotifier(scope)
    val couldnt = stringResource(Res.string.group_wavelength_exit_couldnt)
    LaunchedEffect(true) {
        plan = runCatching { daemonSource.exitPlan(chatRoomId, chain, wallet) }.getOrNull()
        loading = false
    }
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.group_wavelength_exit_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        if (loading) LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
        else GroupWavelengthExitContent(Modifier.padding(padding), plan, onStart = {
            scope.launch {
                runCatching { daemonSource.forceExit(chatRoomId, chain, wallet) }
                    .onFailure { notify(couldnt) }
                plan = runCatching { daemonSource.exitPlan(chatRoomId, chain, wallet) }.getOrNull()
            }
        })
    }
}

@Composable
internal fun GroupWavelengthExitContent(modifier: Modifier, plan: GroupWavelengthExitPlan?, onStart: () -> Unit = {}) {
    if (plan == null) {
        EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_exit_not_here), icon = Icons.Default.Waves)
        return
    }
    var isConfirming by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        item { Text(stringResource(Res.string.group_wavelength_exit_warning), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
        item { Text(stringResource(Res.string.group_wavelength_exit_coins_s, plan.coins.size.toString()), style = MaterialTheme.typography.bodyMedium) }
        if (plan.fundingShortfallSat > 0) {
            item { Text(stringResource(Res.string.group_wavelength_exit_needs_funding_s, Satoshi(plan.fundingShortfallSat).grouped()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        if (plan.coins.isNotEmpty()) {
            item { Button(onClick = { isConfirming = true }, enabled = plan.canStart) { Text(stringResource(Res.string.group_wavelength_exit_start)) } }
        }
        items(plan.underWay, key = { it.outpoint }) { stage ->
            ListItem(
                headlineContent = { Text(stringResource(Res.string.group_wavelength_exit_stage_s_s_s, Satoshi(stage.amountSat).grouped(), stage.status, Satoshi(stage.estimatedNetSat).grouped())) },
                overlineContent = { Text(stage.outpoint, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
            )
        }
    }
    if (isConfirming) {
        AlertDialog(
            onDismissRequest = { isConfirming = false },
            title = { Text(stringResource(Res.string.group_wavelength_exit_confirm_question)) },
            text = { Text(stringResource(Res.string.group_wavelength_exit_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    isConfirming = false
                    onStart()
                }) { Text(stringResource(Res.string.group_wavelength_exit_start)) }
            },
            dismissButton = { TextButton(onClick = { isConfirming = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}
