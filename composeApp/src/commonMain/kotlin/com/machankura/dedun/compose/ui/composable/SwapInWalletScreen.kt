package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.machankura.dedun.compose.text.GroupedSats
import com.machankura.dedun.compose.text.SatsInput
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.approximateDuration
import com.machankura.dedun.compose.ui.composable.widgets.dialogs.ModalBottomSheet
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.SwapInWalletViewModel
import com.machankura.dedun.compose.ui.view.state.SwapInWalletUIState
import com.machankura.dedun.compose.wallet.FirstDeposit
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInCoin
import com.machankura.dedun.compose.wallet.SwapInNotice
import com.machankura.dedun.compose.wallet.SwapInRefusal
import com.machankura.dedun.compose.wallet.SwapInWaiting
import com.machankura.dedun.compose.wallet.bip21Uri
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.this_address_has_been_used_before
import dedun.composeapp.generated.resources.the_refund_wasn_t_sent
import dedun.composeapp.generated.resources.nothing_can_be_refunded
import dedun.composeapp.generated.resources.couldn_t_check_that_address
import dedun.composeapp.generated.resources.that_is_one_of_your_deposit_addresses
import dedun.composeapp.generated.resources.that_isn_t_a_bitcoin_address_on_this_network
import dedun.composeapp.generated.resources.refund_sent
import dedun.composeapp.generated.resources.change_address
import dedun.composeapp.generated.resources.sending_the_refund
import dedun.composeapp.generated.resources.send_refund
import dedun.composeapp.generated.resources.network_fee_s_sats
import dedun.composeapp.generated.resources.refund_s_sats_to
import dedun.composeapp.generated.resources.checking_the_address
import dedun.composeapp.generated.resources.review_refund
import dedun.composeapp.generated.resources.address_to_refund_to
import dedun.composeapp.generated.resources.refund_explainer
import dedun.composeapp.generated.resources.refund
import dedun.composeapp.generated.resources.refund_s_sats
import dedun.composeapp.generated.resources.s_sats_must_move_into_lightning_within_s
import dedun.composeapp.generated.resources.that_isn_t_an_amount_in_sats
import dedun.composeapp.generated.resources.that_is_more_sats_than_there_will_ever_be
import dedun.composeapp.generated.resources.sats_are_whole_numbers
import dedun.composeapp.generated.resources.sats
import dedun.composeapp.generated.resources.fee_limit_in_sats
import dedun.composeapp.generated.resources.the_limit_can_t_be_more_than_the_s_sats_being_moved
import dedun.composeapp.generated.resources.a_limit_of_0_sats_lets_nothing_through
import dedun.composeapp.generated.resources.fee_limit_saved
import dedun.composeapp.generated.resources.fee_allowed_for_these_coins
import dedun.composeapp.generated.resources.save_as_my_limit
import dedun.composeapp.generated.resources.or_raise_your_limit_for_every_deposit
import dedun.composeapp.generated.resources.allowing_a_fee_once_explainer
import dedun.composeapp.generated.resources.allow_this_fee_once
import dedun.composeapp.generated.resources.moving_these_coins_costs_s_more_than_s_of_s
import dedun.composeapp.generated.resources.moving_these_coins_costs_s_your_limit_is_s
import dedun.composeapp.generated.resources.fee_limit
import dedun.composeapp.generated.resources.review_fee_limit
import dedun.composeapp.generated.resources.s_sats_added_to_your_lightning_balance
import dedun.composeapp.generated.resources.moving_s_sats_into_lightning
import dedun.composeapp.generated.resources.waiting_for_your_node_to_move_them
import dedun.composeapp.generated.resources.waiting_the_lsp_couldn_t_take_them_yet
import dedun.composeapp.generated.resources.moving_deposits_into_lightning_is_turned_off
import dedun.composeapp.generated.resources.waiting_the_fee_is_over_your_share
import dedun.composeapp.generated.resources.waiting_the_fee_is_over_your_limit
import dedun.composeapp.generated.resources.waiting_for_your_node_to_connect_to_the_lsp
import dedun.composeapp.generated.resources.address_copied
import dedun.composeapp.generated.resources.can_be_refunded_in_s
import dedun.composeapp.generated.resources.can_be_refunded_now
import dedun.composeapp.generated.resources.coins_sent_here_move_into_lightning_after_n
import dedun.composeapp.generated.resources.copy_address
import dedun.composeapp.generated.resources.copy_address_s
import dedun.composeapp.generated.resources.copy_transaction
import dedun.composeapp.generated.resources.deposit
import dedun.composeapp.generated.resources.deposit_address
import dedun.composeapp.generated.resources.deposits
import dedun.composeapp.generated.resources.finding_your_deposits
import dedun.composeapp.generated.resources.first_deposit_opens_a_channel
import dedun.composeapp.generated.resources.first_deposit_over_your_limit
import dedun.composeapp.generated.resources.legacy
import dedun.composeapp.generated.resources.moving_into_lightning
import dedun.composeapp.generated.resources.must_move_into_lightning_within_s
import dedun.composeapp.generated.resources.n_of_n_confirmations
import dedun.composeapp.generated.resources.nothing_has_been_sent_to_your_deposit_addresses_yet
import dedun.composeapp.generated.resources.onchain_deposits
import dedun.composeapp.generated.resources.paid_s_sats
import dedun.composeapp.generated.resources.paid_spent_since
import dedun.composeapp.generated.resources.ready_to_move_into_lightning
import dedun.composeapp.generated.resources.s_confirmations
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.show_details
import dedun.composeapp.generated.resources.this_profile_has_no_wallet
import dedun.composeapp.generated.resources.transaction_copied
import dedun.composeapp.generated.resources.used_addresses
import dedun.composeapp.generated.resources.waiting_for_its_first_confirmation
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.utils.sum
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The personal node's swap-in wallet (docs/personal-swap-in-wallet.md, Phase 3): the address to
 * deposit to, every coin sent there with where it is on its way into Lightning, and the addresses
 * already paid.
 *
 * The current address is the only one shown as a QR code. A used one is listed to be checked and
 * copied, never handed out again (D4); and as on the group's address list, nothing here opens a block
 * explorer, which would hand every address of the wallet to somebody else's server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwapInWalletScreen(
    identity: Identity?,
    onNavigateBack: () -> Unit,
) {
    val swapInViewModel: SwapInWalletViewModel = viewModel(factory = SwapInWalletViewModel.factory(identity))
    LaunchedEffect(true) { swapInViewModel.initiate() }

    // In the screen's scope rather than a handler's: the notice is read as the node acts, not on a tap.
    val notify = rememberNotifier(rememberCoroutineScope())
    LaunchedEffect(swapInViewModel) {
        swapInViewModel.notices.collect { notice ->
            notify(
                when (notice) {
                    is SwapInNotice.Moving -> getString(Res.string.moving_s_sats_into_lightning, notice.amount.grouped())
                    is SwapInNotice.Received -> getString(Res.string.s_sats_added_to_your_lightning_balance, notice.amount.grouped())
                }
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.onchain_deposits), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        SwapInWalletContent(
            uiState = swapInViewModel.uiState,
            onRetry = swapInViewModel::initiate,
            onReview = if (swapInViewModel.canReviewFees) swapInViewModel::review else null,
            onRefund = if (swapInViewModel.canRefund) swapInViewModel::startRefund else null,
            modifier = Modifier.padding(padding),
        )
    }

    swapInViewModel.refund?.let { step ->
        val sent = stringResource(Res.string.refund_sent)
        RefundSheet(
            step = step,
            addressField = swapInViewModel.refundAddressField,
            onReview = swapInViewModel::reviewRefund,
            onEdit = swapInViewModel::editRefund,
            onSend = { swapInViewModel.sendRefund { notify(sent) } },
            onDismiss = swapInViewModel::closeRefund,
        )
    }

    swapInViewModel.reviewing?.let { refusal ->
        val allowed = stringResource(Res.string.fee_allowed_for_these_coins)
        val saved = stringResource(Res.string.fee_limit_saved)
        FeeLimitSheet(
            refusal = refusal,
            limitField = swapInViewModel.limitField,
            problem = swapInViewModel.limitProblem(),
            isApplying = swapInViewModel.isApplying,
            onAllowOnce = { swapInViewModel.allowOnce { notify(allowed) } },
            onSave = { swapInViewModel.saveLimit { notify(saved) } },
            onDismiss = swapInViewModel::dismissReview,
        )
    }
}

/** The screen's body for a given state: separate so that it can be drawn without a node. */
@Composable
fun SwapInWalletContent(
    uiState: SwapInWalletUIState,
    onRetry: () -> Unit,
    /** Opens the fee-limit sheet for a refusal over the fee; null where nothing can change the limit. */
    onReview: ((SwapInRefusal) -> Unit)? = null,
    /** Opens the refund sheet; null where nothing can sign a refund. */
    onRefund: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val notify = rememberNotifier(rememberCoroutineScope())
    var shown by remember { mutableStateOf<SwapInCoin?>(null) }

    ScreenStateTransition(uiState) { state ->
        when (state) {
            SwapInWalletUIState.Loading ->
                LoadingDataIndicator(modifier = modifier.fillMaxWidth(), text = stringResource(Res.string.finding_your_deposits))

            // The profile screen offers no way here for a profile without a node, so there is nothing to retry.
            SwapInWalletUIState.NoNode ->
                ErrorState(modifier = modifier, message = stringResource(Res.string.this_profile_has_no_wallet), onRetry = null)

            SwapInWalletUIState.Error ->
                ErrorState(modifier = modifier, onRetry = onRetry)

            is SwapInWalletUIState.Loaded -> {
                SwapInWalletList(state = state, notify = notify, onShow = { shown = it }, onReview = onReview, onRefund = onRefund, modifier = modifier)
                // Read from the latest state, so a sheet left open follows its coin as it confirms.
                shown?.let { coin -> state.coins.firstOrNull { it.outPoint == coin.outPoint } }?.let { coin ->
                    CoinSheet(coin = coin, notify = notify, onDismiss = { shown = null })
                }
            }
        }
    }
}

@Composable
private fun SwapInWalletList(
    state: SwapInWalletUIState.Loaded,
    notify: (String) -> Unit,
    onShow: (SwapInCoin) -> Unit,
    onReview: ((SwapInRefusal) -> Unit)?,
    onRefund: (() -> Unit)?,
    modifier: Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val addressCopied = stringResource(Res.string.address_copied)
    val copy = { address: String ->
        clipboardManager.setText(buildAnnotatedString { append(address) })
        notify(addressCopied)
    }

    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
    ) {
        // Ahead of everything: the one thing on this screen with a deadline (Phase 6).
        val expiring = state.coins.filter { it.status is SwapInCoin.Status.ExpiringSoon }
        if (expiring.isNotEmpty()) {
            item(key = "expiring") {
                val soonest = expiring.minOf { (it.status as SwapInCoin.Status.ExpiringSoon).blocksLeft }
                Text(
                    text = stringResource(
                        Res.string.s_sats_must_move_into_lightning_within_s,
                        expiring.map { it.amount }.sum().grouped(),
                        approximateDuration(soonest.toLong()),
                        approximateDuration(state.refundDelay.toLong()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item(key = "receive") {
            ReceiveSection(state = state, onCopy = { copy(state.current.address) })
        }

        item(key = "deposits") {
            SectionHeading(stringResource(Res.string.deposits))
        }
        state.waiting?.let { waiting ->
            item(key = "waiting") { WaitingLine(waiting, onReview) }
        }
        val refundable = state.coins.filter { it.status == SwapInCoin.Status.Refundable }
        if (onRefund != null && refundable.isNotEmpty()) {
            item(key = "refund") {
                OutlinedButton(onClick = onRefund) {
                    Text(stringResource(Res.string.refund_s_sats, refundable.map { it.amount }.sum().grouped()))
                }
            }
        }
        if (state.coins.isEmpty()) {
            item(key = "no-deposits") { EmptyState(message = stringResource(Res.string.nothing_has_been_sent_to_your_deposit_addresses_yet)) }
        }
        items(state.coins, key = { "coin-${it.outPoint}" }) { coin ->
            CoinRow(coin = coin, onShow = { onShow(coin) })
        }

        if (state.used.isNotEmpty()) {
            item(key = "used") { SectionHeading(stringResource(Res.string.used_addresses)) }
            items(state.used, key = { "address-${it.address}" }) { address ->
                UsedAddressRow(address = address, onCopy = { copy(address.address) })
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = MaterialTheme.spacing.sectionGap),
    )
}

/** The address to deposit to, as a QR code, and what a deposit there will do. */
@Composable
private fun ReceiveSection(state: SwapInWalletUIState.Loaded, onCopy: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        Text(
            text = stringResource(Res.string.deposit_address),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(Res.string.coins_sent_here_move_into_lightning_after_n, state.minConfirmations),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        state.firstDeposit?.let { first ->
            Text(
                text = first.sentence(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (first.overLimit != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // BIP21, so a scanning wallet knows what the text is.
        QRCodeView(bip21Uri(state.current.address))
        SelectionContainer {
            Text(text = state.current.address, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        }
        TextButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
            Text(stringResource(Res.string.copy_address))
        }
    }
}

@Composable
private fun FirstDeposit.sentence(): String = when (val limit = overLimit) {
    null -> stringResource(Res.string.first_deposit_opens_a_channel, fee.grouped(), (minimum ?: fee).grouped())
    else -> stringResource(Res.string.first_deposit_over_your_limit, fee.grouped(), limit.grouped())
}

/**
 * Why the ready coins have not moved (docs/personal-swap-in-wallet.md, Phase 4). One line for all of
 * them, since the node moves every ready coin in one request and refuses them together.
 */
@Composable
private fun WaitingLine(waiting: SwapInWaiting, onReview: ((SwapInRefusal) -> Unit)?) {
    val refusal = (waiting as? SwapInWaiting.Refused)?.refusal
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        Text(
            text = waiting.sentence(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (refusal != null && refusal != SwapInRefusal.Other) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Only a fee a higher limit would let through is offered one (Phase 5).
        if (onReview != null && (refusal is SwapInRefusal.OverLimit || refusal is SwapInRefusal.OverShare)) {
            TextButton(onClick = { onReview(refusal) }) { Text(stringResource(Res.string.review_fee_limit)) }
        }
    }
}

/**
 * The refused fee, and the two ways past it (D6): allow it for these coins only, or save a higher limit
 * for every deposit. Only a fee over the absolute cap offers the second: the share is a fraction of
 * each deposit, and a cap typed in sats does not change it.
 */
@Composable
fun FeeLimitSheet(
    refusal: SwapInRefusal,
    limitField: TextFieldState,
    problem: SwapInWalletViewModel.LimitProblem?,
    isApplying: Boolean,
    onAllowOnce: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
            Text(text = stringResource(Res.string.fee_limit), style = MaterialTheme.typography.titleMedium)
            Text(
                text = when (refusal) {
                    is SwapInRefusal.OverLimit -> stringResource(Res.string.moving_these_coins_costs_s_your_limit_is_s, refusal.fee.grouped(), refusal.limit.grouped())
                    is SwapInRefusal.OverShare -> stringResource(
                        Res.string.moving_these_coins_costs_s_more_than_s_of_s,
                        refusal.fee.grouped(),
                        "${refusal.basisPoints / 100}%",
                        refusal.amount.grouped(),
                    )
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(modifier = Modifier.fillMaxWidth(), onClick = onAllowOnce, enabled = !isApplying) {
                Text(stringResource(Res.string.allow_this_fee_once))
            }
            Text(
                text = stringResource(Res.string.allowing_a_fee_once_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (refusal is SwapInRefusal.OverLimit) {
                Text(
                    text = stringResource(Res.string.or_raise_your_limit_for_every_deposit),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.sectionGap),
                )
                val input = (problem as? SwapInWalletViewModel.LimitProblem.Input)?.input
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    state = limitField,
                    outputTransformation = GroupedSats,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    label = { Text(stringResource(Res.string.fee_limit_in_sats)) },
                    suffix = { Text(stringResource(Res.string.sats)) },
                    isError = problem != null && input != SatsInput.Empty,
                    supportingText = problem?.takeIf { input != SatsInput.Empty }?.let { p -> { Text(p.sentence()) } },
                )
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onSave, enabled = problem == null && !isApplying) {
                    Text(stringResource(Res.string.save_as_my_limit))
                }
            }
        }
    }
}

/**
 * Every refundable coin, sent to an address the user types (D7): checked to be a bitcoin address on
 * this network, not one of this wallet's own, and never paid before; then shown with its fee, signed,
 * before anything is broadcast.
 */
@Composable
fun RefundSheet(
    step: SwapInWalletViewModel.RefundStep,
    addressField: TextFieldState,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
            Text(text = stringResource(Res.string.refund), style = MaterialTheme.typography.titleMedium)
            when (step) {
                is SwapInWalletViewModel.RefundStep.Review -> {
                    Text(stringResource(Res.string.refund_s_sats_to, step.preview.received.grouped()), style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Text(text = step.address, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                    Text(
                        text = stringResource(Res.string.network_fee_s_sats, step.preview.fee.grouped()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onSend) { Text(stringResource(Res.string.send_refund)) }
                    TextButton(onClick = onEdit) { Text(stringResource(Res.string.change_address)) }
                }

                SwapInWalletViewModel.RefundStep.Sending ->
                    LoadingDataIndicator(fillScreen = false, text = stringResource(Res.string.sending_the_refund))

                SwapInWalletViewModel.RefundStep.Checking ->
                    LoadingDataIndicator(fillScreen = false, text = stringResource(Res.string.checking_the_address))

                SwapInWalletViewModel.RefundStep.Entering, is SwapInWalletViewModel.RefundStep.Problem -> {
                    Text(
                        text = stringResource(Res.string.refund_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val problem = (step as? SwapInWalletViewModel.RefundStep.Problem)?.problem
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        state = addressField,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        label = { Text(stringResource(Res.string.address_to_refund_to)) },
                        isError = problem != null,
                        supportingText = problem?.let { p -> { Text(p.sentence()) } },
                    )
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onReview, enabled = addressField.text.isNotBlank()) {
                        Text(stringResource(Res.string.review_refund))
                    }
                }
            }
        }
    }
}

@Composable
private fun SwapInWalletViewModel.RefundProblem.sentence(): String = when (this) {
    SwapInWalletViewModel.RefundProblem.UNREADABLE -> stringResource(Res.string.that_isn_t_a_bitcoin_address_on_this_network)
    SwapInWalletViewModel.RefundProblem.OWN_DEPOSIT -> stringResource(Res.string.that_is_one_of_your_deposit_addresses)
    SwapInWalletViewModel.RefundProblem.USED -> stringResource(Res.string.this_address_has_been_used_before)
    SwapInWalletViewModel.RefundProblem.UNCHECKED -> stringResource(Res.string.couldn_t_check_that_address)
    SwapInWalletViewModel.RefundProblem.NOTHING_TO_REFUND -> stringResource(Res.string.nothing_can_be_refunded)
    SwapInWalletViewModel.RefundProblem.NOT_SENT -> stringResource(Res.string.the_refund_wasn_t_sent)
}

@Composable
private fun SwapInWalletViewModel.LimitProblem.sentence(): String = when (this) {
    SwapInWalletViewModel.LimitProblem.Zero -> stringResource(Res.string.a_limit_of_0_sats_lets_nothing_through)
    is SwapInWalletViewModel.LimitProblem.OverAmount -> stringResource(Res.string.the_limit_can_t_be_more_than_the_s_sats_being_moved, amount.grouped())
    is SwapInWalletViewModel.LimitProblem.Input -> when (input) {
        SatsInput.NotWhole -> stringResource(Res.string.sats_are_whole_numbers)
        SatsInput.TooLarge -> stringResource(Res.string.that_is_more_sats_than_there_will_ever_be)
        else -> stringResource(Res.string.that_isn_t_an_amount_in_sats)
    }
}

@Composable
private fun SwapInWaiting.sentence(): String = when (this) {
    SwapInWaiting.NotConnected -> stringResource(Res.string.waiting_for_your_node_to_connect_to_the_lsp)
    SwapInWaiting.ForNode -> stringResource(Res.string.waiting_for_your_node_to_move_them)
    is SwapInWaiting.Refused -> when (val refusal = refusal) {
        is SwapInRefusal.OverLimit -> stringResource(Res.string.waiting_the_fee_is_over_your_limit, refusal.fee.grouped(), refusal.limit.grouped())
        is SwapInRefusal.OverShare -> stringResource(
            Res.string.waiting_the_fee_is_over_your_share,
            refusal.fee.grouped(),
            "${refusal.basisPoints / 100}%",
            refusal.amount.grouped(),
        )
        SwapInRefusal.Disabled -> stringResource(Res.string.moving_deposits_into_lightning_is_turned_off)
        SwapInRefusal.Other -> stringResource(Res.string.waiting_the_lsp_couldn_t_take_them_yet)
    }
}

/** One coin: its amount, and where it is on its way into Lightning. A tap shows its transaction. */
@Composable
private fun CoinRow(coin: SwapInCoin, onShow: () -> Unit) {
    val showDetails = stringResource(Res.string.show_details)
    ListItem(
        modifier = Modifier.clickable(onClickLabel = showDetails, onClick = onShow).minimumInteractiveComponentSize(),
        headlineContent = { Text(stringResource(Res.string.s_sats, coin.amount.grouped())) },
        supportingContent = { Text(coin.status.label()) },
    )
}

/** A coin's transaction, to be copied and checked elsewhere, and how deep it is. */
@Composable
private fun CoinSheet(coin: SwapInCoin, notify: (String) -> Unit, onDismiss: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val copied = stringResource(Res.string.transaction_copied)
    val outPoint = "${coin.txId}:${coin.outPoint.index}"

    ModalBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        ) {
            Text(text = stringResource(Res.string.deposit), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(Res.string.s_sats, coin.amount.grouped()), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = coin.status.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.s_confirmations, coin.confirmations.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(text = outPoint, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
            TextButton(
                onClick = {
                    clipboardManager.setText(buildAnnotatedString { append(coin.txId.toString()) })
                    notify(copied)
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                Text(stringResource(Res.string.copy_transaction))
            }
        }
    }
}

/** A paid address: there to check and copy, never to be shown as a QR code again. */
@Composable
private fun UsedAddressRow(address: SwapInAddress, onCopy: () -> Unit) {
    val name = address.index?.toString() ?: stringResource(Res.string.legacy)
    ListItem(
        leadingContent = { Text(text = name, style = MaterialTheme.typography.labelLarge) },
        headlineContent = {
            Text(
                text = address.address,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
        supportingContent = {
            Text(
                if (address.balance > Satoshi(0)) stringResource(Res.string.paid_s_sats, address.balance.grouped())
                else stringResource(Res.string.paid_spent_since)
            )
        },
        trailingContent = {
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(Res.string.copy_address_s, name))
            }
        },
    )
}

/** Where a coin is on its way into Lightning, in the catalogue's words. */
@Composable
internal fun SwapInCoin.Status.label(): String = when (this) {
    SwapInCoin.Status.Unconfirmed -> stringResource(Res.string.waiting_for_its_first_confirmation)
    is SwapInCoin.Status.Confirming -> stringResource(Res.string.n_of_n_confirmations, confirmations, needed)
    SwapInCoin.Status.Ready -> stringResource(Res.string.ready_to_move_into_lightning)
    SwapInCoin.Status.Swapping -> stringResource(Res.string.moving_into_lightning)
    is SwapInCoin.Status.ExpiringSoon -> stringResource(Res.string.must_move_into_lightning_within_s, approximateDuration(blocksLeft.toLong()))
    is SwapInCoin.Status.Locked -> stringResource(Res.string.can_be_refunded_in_s, approximateDuration(blocksUntilRefund.toLong()))
    SwapInCoin.Status.Refundable -> stringResource(Res.string.can_be_refunded_now)
}
