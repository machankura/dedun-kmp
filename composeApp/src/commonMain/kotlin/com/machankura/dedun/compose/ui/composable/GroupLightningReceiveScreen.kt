package com.machankura.dedun.compose.ui.composable

import dedun.composeapp.generated.resources.ask_again
import dedun.composeapp.generated.resources.no_reply_from_the_operator_s_device_yet
import dedun.composeapp.generated.resources.a_member_who_holds_a_share_of_the_group_s_key_can_open_your_deposits
import dedun.composeapp.generated.resources.start_depositing
import dedun.composeapp.generated.resources.the_group_opens_your_deposits_once_signing_a_branch_of_its_wallet_that_is_yours
import dedun.composeapp.generated.resources.set_where_this_group_pays_you_first
import dedun.composeapp.generated.resources.the_group_counts_what_you_put_in_once_it_knows_where_to_pay_you
import dedun.composeapp.generated.resources.this_profile_can_t_say_where_to_be_paid
import dedun.composeapp.generated.resources.couldn_t_ask_the_group_to_open_your_deposits
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupPayoutDestinationRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.repository.DepositRepository
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.deposit_onchain_instead
import dedun.composeapp.generated.resources.amount_in_sats
import dedun.composeapp.generated.resources.ask_for_an_invoice
import dedun.composeapp.generated.resources.an_expired_invoice_cannot_be_paid_ask_for_another
import dedun.composeapp.generated.resources.ask_for_another_invoice
import dedun.composeapp.generated.resources.this_invoice_was_paid_and_should_not_be_paid_again
import dedun.composeapp.generated.resources.copy_invoice
import dedun.composeapp.generated.resources.couldn_t_ask_for_an_invoice
import dedun.composeapp.generated.resources.invoice_copied
import dedun.composeapp.generated.resources.leave_the_amount_empty_to_let_the_payer_choose
import dedun.composeapp.generated.resources.nobody_runs_this_wallet_to_make_an_invoice
import dedun.composeapp.generated.resources.receive
import dedun.composeapp.generated.resources.sats
import dedun.composeapp.generated.resources.sats_are_whole_numbers
import dedun.composeapp.generated.resources.that_is_more_sats_than_there_will_ever_be
import dedun.composeapp.generated.resources.that_isn_t_an_amount_in_sats
import dedun.composeapp.generated.resources.this_invoice_pays_the_group_s_node
import dedun.composeapp.generated.resources.waiting_for_the_operator_s_device
import dedun.composeapp.generated.resources.what_it_is_for
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.text.GroupedSats
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.InvoiceExpired
import com.machankura.dedun.compose.ui.composable.widgets.PaymentReceived
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.rememberInvoiceExpired
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupLightningReceiveViewModel
import com.machankura.dedun.compose.ui.view.state.GroupLightningReceiveUIState
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import org.jetbrains.compose.resources.stringResource

/**
 * Receiving into the group's Lightning wallet: a member asks, the operator's node makes the invoice, and
 * it is shown once this device has checked it pays the group's node (`docs/group-lightning-wallet.md`,
 * Phase 11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupLightningReceiveScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chatRepository: ChatRepository,
    lightningRepository: GroupLightningRepository,
    depositRepository: DepositRepository = DepositRepository.NO_OP,
    onNavigateToRoute: (Route) -> Unit = {},
    /** Opens the member's swap-in address, where the network has a swap-in; null where it has none. */
    onDepositOnchain: (() -> Unit)? = null,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupLightningReceiveViewModel = viewModel(
        factory = GroupLightningReceiveViewModel.factory(activeUserPublicKey, chatRoomId, GroupLightningRollout.NETWORK, chatRepository, lightningRepository, depositRepository)
    )
    val couldNotOpenDeposits = stringResource(Res.string.couldn_t_ask_the_group_to_open_your_deposits)
    LaunchedEffect(true) { viewModel.initiate() }

    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotAsk = stringResource(Res.string.couldn_t_ask_for_an_invoice)
    val copied = stringResource(Res.string.invoice_copied)
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.receive), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                GroupLightningReceiveUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                is GroupLightningReceiveUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)

                GroupLightningReceiveUIState.Unavailable -> EmptyState(
                    modifier = Modifier.padding(padding).readableContent(),
                    message = stringResource(Res.string.nobody_runs_this_wallet_to_make_an_invoice),
                    icon = Icons.Default.Bolt,
                )

                is GroupLightningReceiveUIState.Form -> {
                    val amountProblem = when (viewModel.amount()) {
                        SatsInput.Empty, is SatsInput.Sats -> null
                        SatsInput.NotWhole -> stringResource(Res.string.sats_are_whole_numbers)
                        SatsInput.TooLarge -> stringResource(Res.string.that_is_more_sats_than_there_will_ever_be)
                        else -> stringResource(Res.string.that_isn_t_an_amount_in_sats)
                    }
                    Column(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            state = viewModel.amountField,
                            outputTransformation = GroupedSats,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            label = { Text(stringResource(Res.string.amount_in_sats)) },
                            suffix = { Text(stringResource(Res.string.sats)) },
                            isError = amountProblem != null,
                            supportingText = { Text(amountProblem ?: stringResource(Res.string.leave_the_amount_empty_to_let_the_payer_choose)) },
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            state = viewModel.descriptionField,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            label = { Text(stringResource(Res.string.what_it_is_for)) },
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewModel.request(onFailure = { notify(couldNotAsk) }) },
                            enabled = amountProblem == null && !viewModel.isActionPending.value,
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = Decorative)
                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                            Text(stringResource(Res.string.ask_for_an_invoice))
                        }
                        // The same deposit, from an onchain wallet: the member's own swap-in address.
                        // docs/group-lightning-swap-in.md, Phase 6.
                        if (onDepositOnchain != null) {
                            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onDepositOnchain) {
                                Text(stringResource(Res.string.deposit_onchain_instead))
                            }
                        }
                    }
                }

                // Only a depositor is issued an invoice: the step before it, as the onchain card has it.
                // docs/member-deposits.md, Phase 6.
                is GroupLightningReceiveUIState.NotDepositor -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    when (val standing = state.standing) {
                        DepositRepository.Standing.CannotSign -> Text(
                            stringResource(Res.string.this_profile_can_t_say_where_to_be_paid),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        DepositRepository.Standing.NeedsDestination -> {
                            Text(stringResource(Res.string.the_group_counts_what_you_put_in_once_it_knows_where_to_pay_you), style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = { onNavigateToRoute(GroupPayoutDestinationRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId)) }) {
                                Text(stringResource(Res.string.set_where_this_group_pays_you_first))
                            }
                        }
                        is DepositRepository.Standing.CanOpen -> {
                            Text(stringResource(Res.string.the_group_opens_your_deposits_once_signing_a_branch_of_its_wallet_that_is_yours), style = MaterialTheme.typography.bodyLarge)
                            if (standing.canPropose) {
                                Button(
                                    enabled = !viewModel.isActionPending.value,
                                    onClick = {
                                        viewModel.openMyDeposits(
                                            onSuccess = { sessionId -> onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)) },
                                            onFailure = { notify(couldNotOpenDeposits) },
                                        )
                                    },
                                ) { Text(stringResource(Res.string.start_depositing)) }
                            } else {
                                Text(
                                    stringResource(Res.string.a_member_who_holds_a_share_of_the_group_s_key_can_open_your_deposits),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        is DepositRepository.Standing.Open -> Unit
                    }
                }

                is GroupLightningReceiveUIState.NoReply -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    Text(stringResource(Res.string.no_reply_from_the_operator_s_device_yet), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::askAgain) { Text(stringResource(Res.string.ask_again)) }
                }

                is GroupLightningReceiveUIState.Waiting -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    LoadingDataIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(Res.string.waiting_for_the_operator_s_device), style = MaterialTheme.typography.bodyLarge)
                }

                is GroupLightningReceiveUIState.Invoice -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    // Expired is as unscannable as paid, and for the same reason: nobody can pay
                    // it now (`docs/paid-requests.md`, Phase 7).
                    if (rememberInvoiceExpired(state.bolt11)) {
                        InvoiceExpired(
                            note = stringResource(Res.string.an_expired_invoice_cannot_be_paid_ask_for_another),
                            action = { Button(onClick = viewModel::askAgain) { Text(stringResource(Res.string.ask_for_another_invoice)) } },
                        )
                    } else {
                        // The URI scheme, so a scanning wallet knows what the text is.
                        QRCodeView("lightning:${state.bolt11}")
                        Text(stringResource(Res.string.this_invoice_pays_the_group_s_node), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SelectionContainer { Text(state.bolt11, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = {
                        clipboardManager.setText(buildAnnotatedString { append(state.bolt11) })
                        notify(copied)
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                        Text(stringResource(Res.string.copy_invoice))
                    }
                }

                // Paid: no QR code, and the bolt11 stays only to be read against what a payer
                // says they paid (`docs/paid-requests.md`, D6).
                is GroupLightningReceiveUIState.Paid -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    PaymentReceived(
                        amount = state.amount,
                        note = stringResource(Res.string.this_invoice_was_paid_and_should_not_be_paid_again),
                        onTheWordOf = state.onTheWordOf,
                        action = {
                            Button(onClick = viewModel::askAgain) { Text(stringResource(Res.string.ask_for_another_invoice)) }
                        },
                    )
                    SelectionContainer { Text(state.bolt11, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = {
                        clipboardManager.setText(buildAnnotatedString { append(state.bolt11) })
                        notify(copied)
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                        Text(stringResource(Res.string.copy_invoice))
                    }
                }
            }
        }
    }
}
