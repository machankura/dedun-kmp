package com.machankura.dedun.compose.ui.composable

import dedun.composeapp.generated.resources.pays_s_s_sats_up_to_s_in_fees_to_s
import dedun.composeapp.generated.resources.pay_out_over_lightning
import dedun.composeapp.generated.resources.pay_out_over_lightning_from_wavelength
import dedun.composeapp.generated.resources.pay_out_onchain_from_wavelength
import dedun.composeapp.generated.resources.wavelength_onchain_payout_pays_s_s_s_s
import dedun.composeapp.generated.resources.wavelength_onchain_payout_needs_s_more
import dedun.composeapp.generated.resources.wavelength_onchain_payout_wallet_too_small
import dedun.composeapp.generated.resources.wavelength_onchain_payout_waiting_s
import dedun.composeapp.generated.resources.wavelength_onchain_payout_no_address_s
import dedun.composeapp.generated.resources.wavelength_s_sats
import dedun.composeapp.generated.resources.something_went_wrong
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.managers.MemberBalances
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.repository.PayoutRepository
import com.machankura.dedun.compose.text.ProposedSpend
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupAssignCoinsRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupPayoutDestinationRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupPayoutsViewModel
import com.machankura.dedun.compose.ui.view.state.GroupPayoutsUIState
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.PayoutBuilder
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_payout_pays_each_member_everything_they_own
import dedun.composeapp.generated.resources.ask_the_group_to_pay_out
import dedun.composeapp.generated.resources.asked_the_group_to_pay_out
import dedun.composeapp.generated.resources.assign
import dedun.composeapp.generated.resources.checking_s_address
import dedun.composeapp.generated.resources.couldn_t_ask_the_group
import dedun.composeapp.generated.resources.couldn_t_check_the_address
import dedun.composeapp.generated.resources.fee_s_sats_at_s_sats_per_byte
import dedun.composeapp.generated.resources.feerate_in_sats_per_byte
import dedun.composeapp.generated.resources.finding_the_group_s_coins
import dedun.composeapp.generated.resources.former_member
import dedun.composeapp.generated.resources.hasn_t_said_where_to_be_paid
import dedun.composeapp.generated.resources.lightning_s_sats
import dedun.composeapp.generated.resources.no_onchain_address
import dedun.composeapp.generated.resources.nobody_has_a_balance_in_this_group_yet
import dedun.composeapp.generated.resources.not_anyone_s_yet_s_sats
import dedun.composeapp.generated.resources.nothing_leaves_until_a_quorum_signs
import dedun.composeapp.generated.resources.onchain_s_sats_in_s_coins
import dedun.composeapp.generated.resources.paid_before_ask_s_for_a_new_address
import dedun.composeapp.generated.resources.payouts
import dedun.composeapp.generated.resources.s_address_paid_before_and_they_have_left
import dedun.composeapp.generated.resources.s_already_has_a_payout_waiting
import dedun.composeapp.generated.resources.s_has_no_confirmed_coins
import dedun.composeapp.generated.resources.s_hasn_t_given_an_onchain_address
import dedun.composeapp.generated.resources.s_s_sats_arrive_at_s
import dedun.composeapp.generated.resources.s_sats_waiting_for_a_confirmation
import dedun.composeapp.generated.resources.this_also_pays_s_their_share
import dedun.composeapp.generated.resources.too_little_for_s_after_the_fee
import dedun.composeapp.generated.resources.until_the_group_says_whose_it_is
import dedun.composeapp.generated.resources.you_have_s_sats_onchain_add_an_onchain_address
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * Every member of the group, former members last, with where the group pays them and what they
 * own in it -- and, on a device that holds a share, choosing some to pay out. A payout pays each
 * chosen member every confirmed coin they own, to their own onchain address, less their share of
 * the fee; it is the one spend the group's wallet signs. `docs/payouts-only.md`, Phase 4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPayoutsScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    initialUIState: GroupPayoutsUIState = GroupPayoutsUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    payoutRepository: PayoutRepository,
    payoutDestinationRepository: PayoutDestinationRepository,
    wallets: GroupOnchainWallets?,
    lightningRepository: GroupLightningRepository = GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY,
    wavelengthRepository: GroupWavelengthRepository = GroupWavelengthRepository.NO_OP,
    onNavigateToRoute: (Route) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupPayoutsViewModel = viewModel(
        factory = GroupPayoutsViewModel.factory(
            activeUserPublicKey, chatRoomId, chain, initialUIState,
            chatRepository, frostSigningRepository, payoutRepository, payoutDestinationRepository, wallets, lightningRepository, wavelengthRepository,
        )
    )
    LaunchedEffect(chatRoomId, chain) {
        if (initialUIState == GroupPayoutsUIState.Loading) viewModel.initiate()
    }
    val notify = rememberNotifier(rememberCoroutineScope())
    val asked = stringResource(Res.string.asked_the_group_to_pay_out)
    val couldNotAsk = stringResource(Res.string.couldn_t_ask_the_group)
    var lightningProblem by remember { mutableStateOf<GroupPayoutsViewModel.LightningProblem?>(null) }
    lightningProblem?.let { problem ->
        val text = when (problem) {
            is GroupPayoutsViewModel.LightningProblem.NoInvoice -> payoutInvoiceProblemText(problem.answer)
            is GroupPayoutsViewModel.LightningProblem.NotAuthorisable -> stringResource(Res.string.something_went_wrong)
            GroupPayoutsViewModel.LightningProblem.CouldNotPropose -> couldNotAsk
        }
        LaunchedEffect(problem) {
            notify(text)
            lightningProblem = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.payouts)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                GroupPayoutsUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                is GroupPayoutsUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = viewModel::initiate)
                is GroupPayoutsUIState.Loaded -> {
                    fun name(member: HexKey) = "@" + (state.names[member] ?: member.take(8))
                    val members = state.balances.members.values.sortedWith(
                        compareBy<MemberBalances.MemberBalance> { it.isFormerMember }.thenBy { name(it.member).lowercase() }
                    )
                    if (members.isEmpty() && state.balances.unowned.isEmpty()) {
                        EmptyState(
                            modifier = Modifier.padding(padding),
                            message = stringResource(Res.string.nobody_has_a_balance_in_this_group_yet),
                            icon = Icons.Default.Savings,
                        )
                    } else LazyColumn(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
                    ) {
                        item {
                            Text(
                                stringResource(Res.string.a_payout_pays_each_member_everything_they_own),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.walletState == null && state.canSign) {
                            item { LoadingDataIndicator(fillScreen = false, text = stringResource(Res.string.finding_the_group_s_coins)) }
                        }
                        items(members, key = { it.member }) { balance ->
                            val selectable = state.canSign && balance.hasOnchainAddress && balance.onchainConfirmedSat > 0
                            MemberPayoutRow(
                                balance = balance,
                                name = name(balance.member),
                                isSelf = balance.member == state.self,
                                selected = balance.member in viewModel.selected,
                                onToggle = if (selectable) { { viewModel.toggle(balance.member) } } else null,
                                onSetMine = {
                                    onNavigateToRoute(GroupPayoutDestinationRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId))
                                },
                                // The whole Lightning balance, through the group's Lightning wallet (D8).
                                lightningOffer = if (state.canSign && state.lightningWallet != null && balance.destination != null) {
                                    viewModel.lightningPayable(balance)
                                } else null,
                                // The whole Wavelength balance, through the group's Wavelength wallet.
                                wavelengthOffer = if (state.canSign && state.wavelengthWallet != null && state.wavelengthPaysLightning && balance.destination != null) {
                                    viewModel.wavelengthPayable(balance)
                                } else null,
                                // The same balance, onchain, as a leave in the wallet's next round (docs/group-wavelength-payouts.md).
                                wavelengthOnchain = if (state.canSign && balance.destination != null) viewModel.wavelengthOnchainOffer(balance) else null,
                                wavelengthOnchainProblem = viewModel.usageProblemOf(balance.member),
                                onCheckOnchainAddress = { viewModel.checkUsage(listOf(balance.member)) },
                                onPayWavelengthOnchain = if (!viewModel.isActionPending.value) {
                                    {
                                        viewModel.payOutWavelengthOnchain(
                                            member = balance.member,
                                            onSuccess = { sessionId ->
                                                notify(asked)
                                                onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId))
                                            },
                                            onFailure = { notify(couldNotAsk) },
                                        )
                                    }
                                } else null,
                                onPayWavelength = if (!viewModel.isActionPending.value) {
                                    {
                                        viewModel.payOutWavelength(
                                            member = balance.member,
                                            onSuccess = { sessionId ->
                                                notify(asked)
                                                onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId))
                                            },
                                            onProblem = { lightningProblem = it },
                                        )
                                    }
                                } else null,
                                onPayLightning = if (!viewModel.isActionPending.value) {
                                    {
                                        viewModel.payOutLightning(
                                            member = balance.member,
                                            onSuccess = { sessionId ->
                                                notify(asked)
                                                onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId))
                                            },
                                            onProblem = { lightningProblem = it },
                                        )
                                    }
                                } else null,
                            )
                            HorizontalDivider()
                        }
                        if (state.balances.unownedSat > 0) {
                            item {
                                ListItem(
                                    headlineContent = { Text(stringResource(Res.string.not_anyone_s_yet_s_sats, Satoshi(state.balances.unownedSat).grouped())) },
                                    supportingContent = { Text(stringResource(Res.string.until_the_group_says_whose_it_is)) },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            onNavigateToRoute(GroupAssignCoinsRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, chain = GroupWalletStatementEvent.chainName(chain)))
                                        }) { Text(stringResource(Res.string.assign)) }
                                    },
                                )
                            }
                        }
                        if (state.canSign && viewModel.selected.isNotEmpty()) {
                            item {
                                PayoutPreview(
                                    viewModel = viewModel,
                                    chain = chain,
                                    name = ::name,
                                    onPropose = {
                                        viewModel.propose(
                                            onSuccess = { sessionId ->
                                                notify(asked)
                                                onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId))
                                            },
                                            onFailure = { notify(couldNotAsk) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One member: their name, where the group pays them, and what they own. The whole row is the
 * checkbox's target where they can be paid out from this device: one 48dp control with one name.
 */
@Composable
private fun MemberPayoutRow(
    balance: MemberBalances.MemberBalance,
    name: String,
    isSelf: Boolean,
    selected: Boolean,
    onToggle: (() -> Unit)?,
    onSetMine: () -> Unit,
    /** The Lightning payout this member's balance makes, as (amount, fee limit) in msat, or null where there is none to offer. */
    lightningOffer: Pair<Long, Long>?,
    onPayLightning: (() -> Unit)?,
    /** The same from the Wavelength wallet. */
    wavelengthOffer: Pair<Long, Long>? = null,
    /** How many sats more the wallet needs before that payout leaves change the operator accepts, or 0 when it does. */
    onPayWavelength: (() -> Unit)? = null,
    /** The Wavelength balance paid out onchain, or why it can't be: null where there's nothing to say. */
    wavelengthOnchain: GroupPayoutsViewModel.WavelengthOnchainOffer? = null,
    /** What was found of the address's use, which an onchain payout waits on (`docs/payouts-only.md`, D7). */
    wavelengthOnchainProblem: GroupPayoutsViewModel.UsageProblem? = null,
    onCheckOnchainAddress: () -> Unit = {},
    onPayWavelengthOnchain: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(if (onToggle != null) Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }) else Modifier)
                .minimumInteractiveComponentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (balance.isFormerMember) {
                    Text(stringResource(Res.string.former_member), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val destination = balance.destination
                Text(
                    destination?.lightningAddress?.toString() ?: stringResource(Res.string.hasn_t_said_where_to_be_paid),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (destination != null) {
                    Text(
                        destination.onchainAddress ?: stringResource(Res.string.no_onchain_address),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(Res.string.onchain_s_sats_in_s_coins, Satoshi(balance.onchainConfirmedSat).grouped(), balance.coins.count { it.isConfirmed }.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (balance.onchainPendingSat > 0) {
                    Text(
                        stringResource(Res.string.s_sats_waiting_for_a_confirmation, Satoshi(balance.onchainPendingSat).grouped()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (balance.lightning.depositedMsat > 0) {
                    Text(stringResource(Res.string.lightning_s_sats, Satoshi(balance.lightning.availableMsat / 1000).grouped()), style = MaterialTheme.typography.bodyMedium)
                }
                if (balance.wavelength.depositedSat > 0) {
                    Text(stringResource(Res.string.wavelength_s_sats, Satoshi(balance.wavelength.availableSat).grouped()), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (onToggle != null) Checkbox(checked = selected, onCheckedChange = null)
        }
        // The member's own row asks for the address it can't be paid without (D7).
        if (isSelf && !balance.hasOnchainAddress && balance.onchainConfirmedSat > 0) {
            TextButton(onClick = onSetMine) {
                Text(stringResource(Res.string.you_have_s_sats_onchain_add_an_onchain_address, Satoshi(balance.onchainConfirmedSat).grouped()))
            }
        }
        if (lightningOffer != null) {
            val (amount, fee) = lightningOffer
            Text(
                stringResource(
                    Res.string.pays_s_s_sats_up_to_s_in_fees_to_s,
                    name, Satoshi(amount / 1000).grouped(), Satoshi(fee / 1000).grouped(), balance.destination?.lightningAddress.toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onPayLightning?.invoke() }, enabled = onPayLightning != null) {
                Text(stringResource(Res.string.pay_out_over_lightning))
            }
        }
        if (wavelengthOffer != null) {
            val (amount, fee) = wavelengthOffer
            Text(
                stringResource(
                    Res.string.pays_s_s_sats_up_to_s_in_fees_to_s,
                    name, Satoshi(amount / 1000).grouped(), Satoshi(fee / 1000).grouped(), balance.destination?.lightningAddress.toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onPayWavelength?.invoke() }, enabled = onPayWavelength != null) {
                Text(stringResource(Res.string.pay_out_over_lightning_from_wavelength))
            }
        }
        WavelengthOnchainOffer(name, wavelengthOnchain, wavelengthOnchainProblem, onCheckOnchainAddress, onPayWavelengthOnchain)
    }
}

/**
 * A member's Wavelength balance paid out onchain: what arrives where and what it may cost, or why it can't go yet. The address is checked
 * for use before the button does anything, as an onchain payout from the group's own wallet is (`docs/payouts-only.md`, D7).
 */
@Composable
private fun WavelengthOnchainOffer(
    name: String,
    offer: GroupPayoutsViewModel.WavelengthOnchainOffer?,
    problem: GroupPayoutsViewModel.UsageProblem?,
    onCheck: () -> Unit,
    onPay: (() -> Unit)?,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (offer) {
        null -> Unit
        GroupPayoutsViewModel.WavelengthOnchainOffer.NoAddress ->
            Text(stringResource(Res.string.wavelength_onchain_payout_no_address_s, name), style = MaterialTheme.typography.bodySmall, color = muted)
        GroupPayoutsViewModel.WavelengthOnchainOffer.Waiting ->
            Text(stringResource(Res.string.wavelength_onchain_payout_waiting_s, name), style = MaterialTheme.typography.bodySmall, color = muted)
        is GroupPayoutsViewModel.WavelengthOnchainOffer.TooLittle ->
            Text(stringResource(Res.string.wavelength_onchain_payout_needs_s_more, Satoshi(offer.shortSat).grouped()), style = MaterialTheme.typography.bodySmall, color = muted)
        GroupPayoutsViewModel.WavelengthOnchainOffer.WalletTooSmall ->
            Text(stringResource(Res.string.wavelength_onchain_payout_wallet_too_small), style = MaterialTheme.typography.bodySmall, color = muted)
        is GroupPayoutsViewModel.WavelengthOnchainOffer.Offer -> {
            LaunchedEffect(offer.address) { onCheck() }
            Text(
                stringResource(Res.string.wavelength_onchain_payout_pays_s_s_s_s, name, Satoshi(offer.amountSat).grouped(), offer.address, Satoshi(offer.maxFeeSat).grouped()),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            when (problem) {
                is GroupPayoutsViewModel.UsageProblem.Checking -> Text(stringResource(Res.string.checking_s_address, name), style = MaterialTheme.typography.bodySmall)
                is GroupPayoutsViewModel.UsageProblem.CouldNotCheck -> Text(stringResource(Res.string.couldn_t_check_the_address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                is GroupPayoutsViewModel.UsageProblem.PaidBefore -> Text(stringResource(Res.string.paid_before_ask_s_for_a_new_address, name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
            TextButton(onClick = { onPay?.invoke() }, enabled = onPay != null && problem == null) {
                Text(stringResource(Res.string.pay_out_onchain_from_wavelength))
            }
        }
    }
}

/** What the chosen payout pays: each member, what arrives, the fee, and why it can't go yet where it can't. */
@Composable
private fun PayoutPreview(
    viewModel: GroupPayoutsViewModel,
    chain: Chain,
    name: (HexKey) -> String,
    onPropose: () -> Unit,
) {
    val preview = viewModel.preview()
    val built = preview as? PayoutBuilder.Result.Built
    val payees = built?.payout?.members?.map { it.member }.orEmpty()
    LaunchedEffect(payees) { viewModel.checkUsage(payees) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                state = viewModel.feerateField,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(Res.string.feerate_in_sats_per_byte)) },
            )
            when (preview) {
                null -> Unit
                is PayoutBuilder.Result.Built -> {
                    preview.payout.members.forEach { payee ->
                        Text(
                            stringResource(Res.string.s_s_sats_arrive_at_s, name(payee.member), Satoshi(payee.paid).grouped(), ProposedSpend.addressOf(payee.script, chain)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (payee.member !in viewModel.selected) {
                            Text(
                                stringResource(Res.string.this_also_pays_s_their_share, name(payee.member)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        stringResource(Res.string.fee_s_sats_at_s_sats_per_byte, preview.fee.grouped(), viewModel.feerateField.text.toString().trim()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    viewModel.reusedByFormer(preview).forEach { member ->
                        Text(stringResource(Res.string.s_address_paid_before_and_they_have_left, name(member)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    when (val problem = viewModel.usageProblem(preview)) {
                        is GroupPayoutsViewModel.UsageProblem.Checking -> Text(stringResource(Res.string.checking_s_address, name(problem.member)), style = MaterialTheme.typography.bodySmall)
                        is GroupPayoutsViewModel.UsageProblem.CouldNotCheck -> Text(stringResource(Res.string.couldn_t_check_the_address), color = MaterialTheme.colorScheme.error)
                        is GroupPayoutsViewModel.UsageProblem.PaidBefore -> Text(stringResource(Res.string.paid_before_ask_s_for_a_new_address, name(problem.member)), color = MaterialTheme.colorScheme.error)
                        null -> Unit
                    }
                }
                is PayoutBuilder.Result.AlreadyWaiting -> ProblemText(stringResource(Res.string.s_already_has_a_payout_waiting, name(preview.member)))
                is PayoutBuilder.Result.NothingToPay -> ProblemText(stringResource(Res.string.s_has_no_confirmed_coins, name(preview.member)))
                is PayoutBuilder.Result.NoOnchainAddress -> ProblemText(stringResource(Res.string.s_hasn_t_given_an_onchain_address, name(preview.member)))
                is PayoutBuilder.Result.BelowDust -> ProblemText(stringResource(Res.string.too_little_for_s_after_the_fee, name(preview.member)))
                is PayoutBuilder.Result.Refused -> ProblemText(preview.reason)
                PayoutBuilder.Result.NotHigher -> Unit
            }
            Text(
                stringResource(Res.string.nothing_leaves_until_a_quorum_signs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onPropose,
                enabled = built != null && viewModel.usageProblem(built) == null && !viewModel.isActionPending.value,
            ) { Text(stringResource(Res.string.ask_the_group_to_pay_out)) }
        }
    }
}

@Composable
private fun ProblemText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
