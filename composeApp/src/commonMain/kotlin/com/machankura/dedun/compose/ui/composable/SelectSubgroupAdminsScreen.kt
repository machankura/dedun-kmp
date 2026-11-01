package com.machankura.dedun.compose.ui.composable

import dedun.composeapp.generated.resources.a_coin_the_subgroup_moves_starts_waiting_again
import dedun.composeapp.generated.resources.how_long_before_this_group_can_spend_a_coin
import com.machankura.dedun.compose.ui.composable.widgets.GuardianDelayChooser
import com.machankura.dedun.compose.ui.view.state.onlyGuarded
import com.machankura.dedun.compose.ui.view.state.canGuard
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import dedun.composeapp.generated.resources.this_group_s_guardian_keys_can_t_be_read
import dedun.composeapp.generated.resources.this_group_is_guarded_so_its_subgroups_are_too
import dedun.composeapp.generated.resources.only_the_subgroup_can_spend_from_its_wallet
import dedun.composeapp.generated.resources.this_group_and_the_d_above_it_can_spend
import dedun.composeapp.generated.resources.this_group_can_spend_its_coins_once
import dedun.composeapp.generated.resources.unguarded_wallet
import dedun.composeapp.generated.resources.guarded_wallet
import dedun.composeapp.generated.resources.the_subgroup_s_wallet
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_subgroup_needs_at_least_three_admins
import dedun.composeapp.generated.resources.admin_of_this_group
import dedun.composeapp.generated.resources.anyone_in_this_group_can_run_a_subgroup
import dedun.composeapp.generated.resources.has_no_key_package_yet
import dedun.composeapp.generated.resources.how_many_admins_have_to_approve_a_change
import dedun.composeapp.generated.resources.new_subgroup
import dedun.composeapp.generated.resources.of
import dedun.composeapp.generated.resources.start_the_key_ceremony
import dedun.composeapp.generated.resources.what_is_the_subgroup_called
import dedun.composeapp.generated.resources.who_runs_the_subgroup
import dedun.composeapp.generated.resources.you_coordinate_this_subgroup
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.managers.MarmotGroupName
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.SelectSubgroupAdminsViewModel
import com.machankura.dedun.compose.ui.view.state.SelectSubgroupAdminsUIState

/**
 * Picks who runs a subgroup, what it is called, on what quorum, and whether its
 * wallet is guarded.
 *
 * The four things that cannot be changed later, gathered before anything is
 * published -- see `SelectSubgroupAdminsViewModel` for why the threshold in
 * particular has nowhere else to live.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SelectSubgroupAdminsScreen(
    activeUserPublicKey: HexKey,
    parentChatRoomId: String,
    /** A failed subgroup ceremony to start from; see `SelectSubgroupAdminsRoute.retrying`. */
    retrying: String? = null,
    initialUIState: SelectSubgroupAdminsUIState = SelectSubgroupAdminsUIState.Loading,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    dkgRepository: DkgRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: SelectSubgroupAdminsViewModel = viewModel(
        factory = SelectSubgroupAdminsViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            parentChatRoomId = parentChatRoomId,
            retrying = retrying,
            initialUIState = initialUIState,
            activeIdentityStateFlow = activeIdentityStateFlow,
            chatRepository = chatRepository,
            dkgRepository = dkgRepository
        )
    )

    LaunchedEffect(parentChatRoomId) { viewModel.initiate() }

    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    ScreenStateTransition(viewModel.uiState) { uiState ->
        when (val state = uiState) {
            is SelectSubgroupAdminsUIState.Error -> ErrorState(
                message = state.message,
                // Retrying reloads the group, which is the thing that failed.
                onRetry = { viewModel.initiate() }
            )

            SelectSubgroupAdminsUIState.Loading -> LoadingDataIndicator()

            is SelectSubgroupAdminsUIState.Loaded -> Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(Res.string.new_subgroup)) },
                        navigationIcon = { NavigateBackButton(onNavigateBack) },
                    )
                },
                floatingActionButton = {
                    ExtendedFab(
                        label = stringResource(Res.string.start_the_key_ceremony),
                        icon = { Icon(Icons.Default.AccountTree, contentDescription = Decorative) },
                        onClick = {
                            scope.launch {
                                // The refusal is recomputed rather than cached on
                                // the button: the key-package answer can go stale
                                // while the screen is open, and confirm checks
                                // again anyway.
                                val refusal = viewModel.refusal()
                                if (refusal != null) {
                                    snackbarHostState.showSnackbar(refusal)
                                } else {
                                    viewModel.confirm(onNavigateToRoute)
                                }
                            }
                        }
                    )
                }
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .padding(innerPadding)
                        .readableContent()
                        .fillMaxSize()
                        .padding(MaterialTheme.spacing.space250),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                ) {
                    item {
                        OutlinedTextField(
                            value = viewModel.name.value,
                            onValueChange = viewModel::setName,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            // Every Marmot room is a `#name`, and a subgroup is
                            // one. Shown in the field rather than added on
                            // confirm alone, so the name the coordinator reads
                            // here is the name the parent's admins will be asked
                            // to approve.
                            prefix = { Text(MarmotGroupName.PREFIX) },
                            label = { Text(stringResource(Res.string.what_is_the_subgroup_called)) }
                        )
                    }

                    item {
                        Text(
                            text = stringResource(Res.string.who_runs_the_subgroup),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    item {
                        // Stated rather than discovered by tapping a disabled
                        // button. Three is the floor a quorum stops being a check
                        // below, and the coordinator is one of the three whether
                        // or not anybody ticks them.
                        Text(
                            text = stringResource(Res.string.a_subgroup_needs_at_least_three_admins),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    item {
                        Text(
                            text = stringResource(Res.string.anyone_in_this_group_can_run_a_subgroup),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // The coordinator, shown and not offered. They are a ChillDKG
                    // participant by construction and hold a share whether or not
                    // anybody ticked them, so leaving them off the list entirely
                    // would make "pick two more" read as a group of two.
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                leadingContent = {
                                    ProfileAvatar(
                                        publicKey = activeUserPublicKey,
                                        profile = null
                                    )
                                },
                                headlineContent = {
                                    Text(stringResource(Res.string.you_coordinate_this_subgroup))
                                },
                                trailingContent = {
                                    Checkbox(checked = true, enabled = false, onCheckedChange = null)
                                }
                            )
                        }
                    }

                    items(
                        items = state.candidates,
                        key = { it.participant.participantPublicKey }
                    ) { candidate ->
                        val publicKey = candidate.participant.participantPublicKey
                        val isUnreachable = publicKey in state.withoutKeyPackage

                        Card(
                            onClick = { viewModel.toggleMember(publicKey) },
                            enabled = !isUnreachable
                        ) {
                            ListItem(
                                leadingContent = {
                                    ProfileAvatar(
                                        publicKey = publicKey,
                                        profile = candidate.profile
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = candidate.profile?.humanReadableNameOrPubkey()
                                            ?: publicKey,
                                        maxLines = 1,
                                        overflow = TextOverflow.MiddleEllipsis
                                    )
                                },
                                supportingContent = {
                                    // The remedy, not the diagnosis. A key package
                                    // is one-time-use and only its owner can
                                    // publish another, so the useful thing to say
                                    // is whose door to knock on.
                                    if (isUnreachable) {
                                        Text(stringResource(Res.string.has_no_key_package_yet))
                                    } else if (candidate.participant.adminAt != null) {
                                        Text(stringResource(Res.string.admin_of_this_group))
                                    }
                                },
                                trailingContent = {
                                    Checkbox(
                                        checked = viewModel.isSelected(publicKey),
                                        enabled = !isUnreachable,
                                        onCheckedChange = { viewModel.toggleMember(publicKey) }
                                    )
                                }
                            )
                        }
                    }

                    item {
                        QuorumStepper(
                            quorum = viewModel.threshold.value,
                            adminCount = viewModel.adminCount(),
                            quorumRange = viewModel.quorumRange(),
                            onQuorumChange = viewModel::setThreshold
                        )
                    }

                    item {
                        WalletPolicyChooser(
                            policy = viewModel.walletPolicy.value,
                            parentGuardianCount = state.parentGuardians?.size ?: 0,
                            onlyGuarded = state.onlyGuarded,
                            canGuard = state.canGuard,
                            onPolicyChange = viewModel::setWalletPolicy
                        )
                    }

                    // The parent's own leaf's delay, and only for a guarded
                    // subgroup: docs/guardian-delay.md.
                    if (viewModel.walletPolicy.value == SubgroupWalletPolicy.GUARDED && state.canGuard) {
                        item {
                            GuardianDelayChooser(
                                title = stringResource(Res.string.how_long_before_this_group_can_spend_a_coin),
                                consequence = stringResource(Res.string.a_coin_the_subgroup_moves_starts_waiting_again),
                                input = viewModel.guardianDelayInput.value,
                                delay = viewModel.guardianDelay(),
                                isCustom = viewModel.isCustomDelay.value,
                                problem = viewModel.guardianDelayProblem(),
                                onPreset = viewModel::setGuardianDelay,
                                onCustom = viewModel::useCustomDelay,
                                onInput = viewModel::setGuardianDelayInput,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * How many of the subgroup's admins have to sign for it.
 *
 * Stepped rather than typed, like the one on the group-creation screen: the range
 * is small and both ends are bounded. Unlike that one it cannot be revisited --
 * ChillDKG hashes the threshold into the session identity, so this is the only
 * moment it can be chosen at all.
 */
@Composable
private fun QuorumStepper(
    quorum: Int,
    adminCount: Int,
    quorumRange: IntRange,
    onQuorumChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.space50),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)
    ) {
        Text(
            text = stringResource(Res.string.how_many_admins_have_to_approve_a_change),
            style = MaterialTheme.typography.titleSmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = { onQuorumChange(quorum - 1) },
                enabled = quorum > quorumRange.first
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Fewer approvals")
            }

            Text(
                text = stringResource(Res.string.of, quorum, adminCount),
                style = MaterialTheme.typography.titleMedium
            )

            FilledIconButton(
                onClick = { onQuorumChange(quorum + 1) },
                enabled = quorum < quorumRange.last
            ) {
                Icon(Icons.Default.Add, contentDescription = "More approvals")
            }
        }
    }
}

/**
 * Whether the subgroup's wallet is guarded -- `docs/subgroup-wallet-policy.md`.
 *
 * Settled here for the reason the threshold is: it rides the proposal, every
 * admin is shown it before joining, and the parent's quorum signs it into the
 * certificate, so there is no later screen where it could be changed.
 *
 * Under a guarded parent both rows still show, with *unguarded* disabled and its
 * reason in place of its description. Hiding it would make the picker look like
 * an older build's, where nobody decided anything; showing it says a decision was
 * made, and by whom.
 */
@Composable
private fun WalletPolicyChooser(
    policy: SubgroupWalletPolicy,
    parentGuardianCount: Int,
    onlyGuarded: Boolean,
    canGuard: Boolean,
    onPolicyChange: (SubgroupWalletPolicy) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.spacing.space50)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)
    ) {
        Text(
            text = stringResource(Res.string.the_subgroup_s_wallet),
            style = MaterialTheme.typography.titleSmall
        )

        WalletPolicyRow(
            title = stringResource(Res.string.guarded_wallet),
            // Names the reach the parent is actually granting: itself, and each
            // ancestor whose key its own certificate passes on.
            description = if (!canGuard) {
                stringResource(Res.string.this_group_s_guardian_keys_can_t_be_read)
            } else if (parentGuardianCount == 0) {
                stringResource(Res.string.this_group_can_spend_its_coins_once)
            } else {
                stringResource(Res.string.this_group_and_the_d_above_it_can_spend, parentGuardianCount)
            },
            isSelected = policy == SubgroupWalletPolicy.GUARDED,
            isEnabled = canGuard,
            onSelect = { onPolicyChange(SubgroupWalletPolicy.GUARDED) }
        )
        WalletPolicyRow(
            title = stringResource(Res.string.unguarded_wallet),
            description = if (onlyGuarded) {
                stringResource(Res.string.this_group_is_guarded_so_its_subgroups_are_too)
            } else {
                stringResource(Res.string.only_the_subgroup_can_spend_from_its_wallet)
            },
            isSelected = policy == SubgroupWalletPolicy.UNGUARDED,
            isEnabled = !onlyGuarded,
            onSelect = { onPolicyChange(SubgroupWalletPolicy.UNGUARDED) }
        )
    }
}

@Composable
private fun WalletPolicyRow(
    title: String,
    description: String,
    isSelected: Boolean,
    isEnabled: Boolean,
    onSelect: () -> Unit,
) {
    // The card is the target, as the admin rows above are, and the radio button
    // beside the title only says which one is chosen.
    Card(
        modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton; selected = isSelected },
        onClick = onSelect,
        enabled = isEnabled
    ) {
        ListItem(
            leadingContent = {
                RadioButton(selected = isSelected, onClick = null, enabled = isEnabled)
            },
            headlineContent = { Text(title) },
            supportingContent = { Text(description) }
        )
    }
}

@ConformancePreviews
@Composable
private fun SelectSubgroupAdminsScreenPreview() {
    BennuTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            SelectSubgroupAdminsScreen(
                activeUserPublicKey = "a".repeat(64),
                parentChatRoomId = "b".repeat(64),
                activeIdentityStateFlow = kotlinx.coroutines.flow.MutableStateFlow(null),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}
