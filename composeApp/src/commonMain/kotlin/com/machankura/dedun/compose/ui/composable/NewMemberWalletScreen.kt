package com.machankura.dedun.compose.ui.composable

import dedun.composeapp.generated.resources.this_group_has_stopped_guarding_members_wallets
import dedun.composeapp.generated.resources.this_group_doesn_t_guard_members_wallets
import com.machankura.dedun.compose.ui.composable.widgets.delayText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_group
import dedun.composeapp.generated.resources.a_wallet_only_you_can_spend_from_guarded_by_s
import dedun.composeapp.generated.resources.ask_s
import dedun.composeapp.generated.resources.ask_the_group_for_its_key
import dedun.composeapp.generated.resources.asked_for_the_group_s_key
import dedun.composeapp.generated.resources.asked_s_to_guard_a_wallet
import dedun.composeapp.generated.resources.choose_a_group_to_guard_it
import dedun.composeapp.generated.resources.couldn_t_ask_the_group_to_guard_a_wallet
import dedun.composeapp.generated.resources.if_you_lose_this_device_those_groups_can_still_move_your_coins
import dedun.composeapp.generated.resources.label_optional
import dedun.composeapp.generated.resources.member_wallets_are_not_offered_on_this_network
import dedun.composeapp.generated.resources.members_of_those_groups_will_see_this_wallet
import dedun.composeapp.generated.resources.new_guarded_wallet
import dedun.composeapp.generated.resources.only_a_group_can_guard_a_wallet
import dedun.composeapp.generated.resources.s_and_the_d_groups_above_it_can_take_any_coin_without_asking_you
import dedun.composeapp.generated.resources.s_and_the_group_above_it_can_take_any_coin_without_asking_you
import dedun.composeapp.generated.resources.s_can_take_any_coin_without_asking_you
import dedun.composeapp.generated.resources.this_device_does_not_know_what_this_group_signs_with
import dedun.composeapp.generated.resources.this_group_s_guardian_keys_can_t_be_read_to_guard_a_wallet
import dedun.composeapp.generated.resources.this_profile_cannot_sign_a_request
import dedun.composeapp.generated.resources.none_of_your_groups_guards_members_wallets_yet
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.repository.MemberWalletRepository
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
import com.machankura.dedun.compose.ui.view.model.NewMemberWalletViewModel
import com.machankura.dedun.compose.ui.view.state.NewMemberWalletUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import org.jetbrains.compose.resources.stringResource

/**
 * Asking a group to guard a wallet only this member can spend from.
 *
 * A consent screen first and a form second: what the member gives up -- the
 * groups that can take their coins, and see them -- is said before the one
 * button, in the words `docs/member-guarded-wallets.md` uses. Nothing leaves the
 * device until that button: the key is made, the leaves worked out and the key
 * stored, and only then is the group asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMemberWalletScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String?,
    chain: Chain,
    initialUIState: NewMemberWalletUIState = NewMemberWalletUIState.Loading,
    memberWalletRepository: MemberWalletRepository,
    onNavigateBack: () -> Unit,
) {
    val viewModel: NewMemberWalletViewModel = viewModel(
        factory = NewMemberWalletViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chatRoomId = chatRoomId,
            chain = chain,
            initialUIState = initialUIState,
            memberWalletRepository = memberWalletRepository,
        )
    )

    LaunchedEffect(true) {
        if (initialUIState == NewMemberWalletUIState.Loading) viewModel.initiate()
    }

    // Read above the handlers: the notifier and the strings are composable and
    // an onClick is not. The caller's scope, so "asked" outlives the screen
    // navigating away.
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotAsk = stringResource(Res.string.couldn_t_ask_the_group_to_guard_a_wallet)
    val fallbackName = stringResource(Res.string.a_group)
    val chosenName = (viewModel.uiState as? NewMemberWalletUIState.Chosen)?.room?.chatRoom?.subject?.takeIf { it.isNotBlank() } ?: fallbackName
    val asked = stringResource(Res.string.asked_s_to_guard_a_wallet, chosenName)

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.new_guarded_wallet)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                NewMemberWalletUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                // Nothing to retry: the group came from a navigation argument.
                is NewMemberWalletUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)

                is NewMemberWalletUIState.Picking -> if (state.groups.isEmpty()) {
                    EmptyState(
                        modifier = Modifier.padding(padding).readableContent(),
                        message = stringResource(Res.string.none_of_your_groups_guards_members_wallets_yet),
                        icon = Icons.Default.Groups,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                            .padding(MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
                    ) {
                        item {
                            Text(stringResource(Res.string.choose_a_group_to_guard_it), style = MaterialTheme.typography.titleMedium)
                        }
                        items(state.groups, key = { it.room.chatRoom.id }) { choice ->
                            ListItem(
                                modifier = Modifier.clickable { viewModel.pick(choice.room.chatRoom.id) }.minimumInteractiveComponentSize(),
                                leadingContent = { Icon(Icons.Default.Groups, contentDescription = Decorative) },
                                headlineContent = {
                                    Text(choice.room.chatRoom.subject?.takeIf { it.isNotBlank() } ?: fallbackName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = choice.refusal?.let { refusal -> { Text(refusalText(refusal)) } },
                            )
                        }
                    }
                }

                is NewMemberWalletUIState.Chosen -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative)
                    Text(
                        text = stringResource(Res.string.a_wallet_only_you_can_spend_from_guarded_by_s, chosenName),
                        style = MaterialTheme.typography.titleLarge,
                    )

                    state.delays?.let { delays -> Text(reachText(chosenName, delays, chain), style = MaterialTheme.typography.bodyLarge) }
                    Text(stringResource(Res.string.members_of_those_groups_will_see_this_wallet), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(Res.string.if_you_lose_this_device_those_groups_can_still_move_your_coins),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    when (val refusal = state.refusal) {
                        null -> {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = viewModel.label.value,
                                onValueChange = viewModel::setLabel,
                                label = { Text(stringResource(Res.string.label_optional)) },
                                singleLine = true,
                            )
                            Button(
                                onClick = { viewModel.ask(onAsked = { notify(asked); onNavigateBack() }, onFailure = { notify(couldNotAsk) }) },
                                enabled = !viewModel.isActionPending.value,
                            ) {
                                Text(stringResource(Res.string.ask_s, chosenName))
                            }
                        }

                        MemberWalletManager.Refusal.KEY_UNKNOWN -> {
                            Text(refusalText(refusal), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.askedForKey) {
                                Text(stringResource(Res.string.asked_for_the_group_s_key), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                TextButton(onClick = viewModel::askForGroupKey) { Text(stringResource(Res.string.ask_the_group_for_its_key)) }
                            }
                        }

                        else -> Text(refusalText(refusal), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Who can take the member's coins, and after how long: the group alone, the
 * group and its parent, or the group and N above it, [delays] nearest first.
 * Each guardian's own, since every group above chose its own --
 * `docs/guardian-delay.md`.
 */
@Composable
internal fun reachText(groupName: String, delays: List<Long>, chain: Chain?): String = when (delays.size) {
    0 -> ""
    1 -> stringResource(Res.string.s_can_take_any_coin_without_asking_you, groupName, delayText(delays[0], chain))
    2 -> stringResource(
        Res.string.s_and_the_group_above_it_can_take_any_coin_without_asking_you,
        groupName, delayText(delays[0], chain), delayText(delays[1], chain),
    )
    else -> stringResource(
        Res.string.s_and_the_d_groups_above_it_can_take_any_coin_without_asking_you,
        groupName, delayText(delays[0], chain), delays.size - 1, delayText(delays.drop(1).min(), chain),
    )
}

@Composable
private fun refusalText(refusal: MemberWalletManager.Refusal): String = stringResource(
    when (refusal) {
        MemberWalletManager.Refusal.CANNOT_SIGN -> Res.string.this_profile_cannot_sign_a_request
        MemberWalletManager.Refusal.NOT_A_GROUP -> Res.string.only_a_group_can_guard_a_wallet
        MemberWalletManager.Refusal.KEY_UNKNOWN -> Res.string.this_device_does_not_know_what_this_group_signs_with
        MemberWalletManager.Refusal.GUARDIANS_UNREADABLE -> Res.string.this_group_s_guardian_keys_can_t_be_read_to_guard_a_wallet
        MemberWalletManager.Refusal.NOT_ON_THIS_NETWORK -> Res.string.member_wallets_are_not_offered_on_this_network
        MemberWalletManager.Refusal.NOT_OFFERED -> Res.string.this_group_doesn_t_guard_members_wallets
        MemberWalletManager.Refusal.CLOSED -> Res.string.this_group_has_stopped_guarding_members_wallets
    }
)
