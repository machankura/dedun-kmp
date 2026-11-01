package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.add_liquidity
import dedun.composeapp.generated.resources.after_a_year_the_lsp_may_reclaim
import dedun.composeapp.generated.resources.ask_the_group
import dedun.composeapp.generated.resources.buying_liquidity_asks_the_group_to_sign
import dedun.composeapp.generated.resources.couldn_t_propose_this
import dedun.composeapp.generated.resources.current_liquidity_s_sats
import dedun.composeapp.generated.resources.duration
import dedun.composeapp.generated.resources.fee_limit_in_sats
import dedun.composeapp.generated.resources.i_understand
import dedun.composeapp.generated.resources.left_empty_s_sats
import dedun.composeapp.generated.resources.liquidity_is_an_initial_amount
import dedun.composeapp.generated.resources.liquidity_lets_the_group_receive_more
import dedun.composeapp.generated.resources.liquidity_to_add_s_sats
import dedun.composeapp.generated.resources.miner_fee
import dedun.composeapp.generated.resources.nobody_runs_this_wallet
import dedun.composeapp.generated.resources.one_year
import dedun.composeapp.generated.resources.plan_ahead_your_liquidity
import dedun.composeapp.generated.resources.priced_at_s_sat_vbyte_in_term_s
import dedun.composeapp.generated.resources.receive_a_payment_first_it_opens_the_channel
import dedun.composeapp.generated.resources.review_the_purchase
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.sats
import dedun.composeapp.generated.resources.service_fee
import dedun.composeapp.generated.resources.the_channel_grows_from_s_to_s_sats
import dedun.composeapp.generated.resources.the_channel_holds_the_most_it_may_for_now
import dedun.composeapp.generated.resources.the_fee_is_a_quarter_of_the_amount_or_more
import dedun.composeapp.generated.resources.the_fee_is_more_than_the_group_s_balance
import dedun.composeapp.generated.resources.the_lsp_sells_none_of_these_amounts
import dedun.composeapp.generated.resources.the_operator_hasn_t_reported_what_the_lsp_sells
import dedun.composeapp.generated.resources.total
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.text.GroupedSats
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupLightningLiquidityViewModel
import com.machankura.dedun.compose.ui.view.state.GroupLightningLiquidityUIState
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.wallet.lightning.LiquidityPrice
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Satoshi
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * Asking the group to buy inbound liquidity, as Phoenix asks its owner (`docs/group-lightning-liquidity.md`,
 * Phase 7): an amount on Phoenix's slider, its price worked out on this device, a review with the
 * acknowledgement Phoenix asks for, and then a proposal rather than a purchase. The group signs it; the
 * operator's node buys; every member's device checks the splice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupLightningLiquidityScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chatRepository: ChatRepository,
    lightningRepository: GroupLightningRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupLightningLiquidityViewModel = viewModel(
        factory = GroupLightningLiquidityViewModel.factory(activeUserPublicKey, chatRoomId, GroupLightningRollout.NETWORK, chatRepository, lightningRepository)
    )
    LaunchedEffect(true) { viewModel.initiate() }

    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.couldn_t_propose_this)

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.add_liquidity), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                GroupLightningLiquidityUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                is GroupLightningLiquidityUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)

                is GroupLightningLiquidityUIState.Unavailable -> EmptyState(
                    modifier = Modifier.padding(padding).readableContent(),
                    message = unavailableBecause(state.why),
                    icon = Icons.Default.Bolt,
                )

                is GroupLightningLiquidityUIState.Form -> LiquidityForm(
                    modifier = Modifier.padding(padding),
                    form = state,
                    viewModel = viewModel,
                    onAsk = {
                        viewModel.propose(
                            onSession = { sessionId -> onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)) },
                            onFailure = { notify(couldNotPropose) },
                        )
                    },
                )
            }
        }
    }
}

/** Why nothing can be asked for now, in a sentence; the wallet screen says the same where its button would be. */
@Composable
internal fun unavailableBecause(why: LiquidityPrice.Availability): String = when (why) {
    LiquidityPrice.Availability.NoOperator -> stringResource(Res.string.nobody_runs_this_wallet)
    LiquidityPrice.Availability.NoPrices -> stringResource(Res.string.the_operator_hasn_t_reported_what_the_lsp_sells)
    LiquidityPrice.Availability.NoChannel -> stringResource(Res.string.receive_a_payment_first_it_opens_the_channel)
    LiquidityPrice.Availability.AtTheCap -> stringResource(Res.string.the_channel_holds_the_most_it_may_for_now)
    LiquidityPrice.Availability.NothingSold -> stringResource(Res.string.the_lsp_sells_none_of_these_amounts)
    is LiquidityPrice.Availability.Available -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiquidityForm(modifier: Modifier, form: GroupLightningLiquidityUIState.Form, viewModel: GroupLightningLiquidityViewModel, onAsk: () -> Unit) {
    val channel = form.available.channel
    val offered = form.available.offered
    val amount = viewModel.amountSat(form)
    val price = viewModel.price(form)
    val cap = viewModel.maxFeeSat(form)
    val overBalance = price != null && price.totalSat > form.snapshot.spendableMsat / 1000
    Column(
        modifier = modifier.readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        Text(stringResource(Res.string.plan_ahead_your_liquidity), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.liquidity_lets_the_group_receive_more), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(Res.string.current_liquidity_s_sats, Satoshi(form.snapshot.inboundMsat / 1000).grouped()), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(Res.string.liquidity_to_add_s_sats, Satoshi(amount).grouped()), style = MaterialTheme.typography.titleLarge)
        // Phoenix's discrete slider, over the amounts the LSP sells and the channel has room for.
        if (offered.size > 1) {
            Slider(
                value = viewModel.choice.toFloat(),
                onValueChange = { viewModel.choice = it.roundToInt() },
                valueRange = 0f..offered.lastIndex.toFloat(),
                steps = (offered.size - 2).coerceAtLeast(0),
            )
        }
        Text(stringResource(Res.string.the_channel_grows_from_s_to_s_sats, Satoshi(channel.capacitySat).grouped(), Satoshi(channel.capacitySat + amount).grouped()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        price?.let { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(MaterialTheme.spacing.containerPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                    FeeLine(stringResource(Res.string.miner_fee), stringResource(Res.string.s_sats, Satoshi(p.miningSat).grouped()))
                    FeeLine(stringResource(Res.string.service_fee), stringResource(Res.string.s_sats, Satoshi(p.serviceSat).grouped()))
                    FeeLine(stringResource(Res.string.duration), stringResource(Res.string.one_year))
                    Text(stringResource(Res.string.after_a_year_the_lsp_may_reclaim), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    FeeLine(stringResource(Res.string.total), stringResource(Res.string.s_sats, Satoshi(p.totalSat).grouped()), emphasised = true)
                    Text(stringResource(Res.string.priced_at_s_sat_vbyte_in_term_s, form.available.feeratePerByte.toString(), form.term.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Phoenix's two checks: a warning that does not stop anyone, and a balance that does.
            if (LiquidityPrice.expensive(p, amount)) {
                Text(stringResource(Res.string.the_fee_is_a_quarter_of_the_amount_or_more), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (overBalance) {
                Text(stringResource(Res.string.the_fee_is_more_than_the_group_s_balance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            state = viewModel.maxFeeField,
            outputTransformation = GroupedSats,
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            label = { Text(stringResource(Res.string.fee_limit_in_sats)) },
            suffix = { Text(stringResource(Res.string.sats)) },
            isError = cap == null,
            supportingText = price?.let { p -> { Text(stringResource(Res.string.left_empty_s_sats, Satoshi(LiquidityPrice.defaultCap(p)).grouped())) } },
        )
        Text(stringResource(Res.string.buying_liquidity_asks_the_group_to_sign), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { viewModel.acknowledged = false; viewModel.reviewing = true },
            enabled = price != null && cap != null && !overBalance && !viewModel.isActionPending.value,
        ) {
            Text(stringResource(Res.string.review_the_purchase))
        }
    }

    if (viewModel.reviewing) {
        ModalBottomSheet(onDismissRequest = { viewModel.reviewing = false }) {
            Column(
                modifier = Modifier.readableContent().padding(MaterialTheme.spacing.screenMargin),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            ) {
                Text(stringResource(Res.string.review_the_purchase), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.liquidity_to_add_s_sats, Satoshi(amount).grouped()), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(Res.string.liquidity_is_an_initial_amount), style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(value = viewModel.acknowledged, role = Role.Checkbox, onValueChange = { viewModel.acknowledged = it }).minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = viewModel.acknowledged, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                    Text(stringResource(Res.string.i_understand))
                }
                Button(modifier = Modifier.fillMaxWidth(), onClick = onAsk, enabled = viewModel.acknowledged && !viewModel.isActionPending.value) {
                    Icon(Icons.Default.Bolt, contentDescription = Decorative)
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                    Text(stringResource(Res.string.ask_the_group))
                }
            }
        }
    }
}

@Composable
private fun FeeLine(label: String, value: String, emphasised: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (emphasised) FontWeight.SemiBold else null)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (emphasised) FontWeight.SemiBold else null)
    }
}
