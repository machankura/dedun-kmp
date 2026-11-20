package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.also_in_the_way
import dedun.composeapp.generated.resources.back_to_the_group
import dedun.composeapp.generated.resources.make_admin
import dedun.composeapp.generated.resources.member_action_below_floor_body
import dedun.composeapp.generated.resources.member_action_below_floor_title
import dedun.composeapp.generated.resources.member_action_change_pending_body
import dedun.composeapp.generated.resources.member_action_change_pending_title
import dedun.composeapp.generated.resources.member_action_disbanded_body
import dedun.composeapp.generated.resources.member_action_disbanded_title
import dedun.composeapp.generated.resources.member_action_legacy_body
import dedun.composeapp.generated.resources.member_action_legacy_title
import dedun.composeapp.generated.resources.member_action_no_admins_body
import dedun.composeapp.generated.resources.member_action_no_admins_title
import dedun.composeapp.generated.resources.member_action_not_an_admin_body
import dedun.composeapp.generated.resources.member_action_not_an_admin_title
import dedun.composeapp.generated.resources.member_action_not_in_group_body
import dedun.composeapp.generated.resources.member_action_not_in_group_title
import dedun.composeapp.generated.resources.member_action_offer_pending_body
import dedun.composeapp.generated.resources.member_action_offer_pending_title
import dedun.composeapp.generated.resources.member_action_only_admin_body
import dedun.composeapp.generated.resources.member_action_only_admin_title
import dedun.composeapp.generated.resources.member_action_share_rotation_body
import dedun.composeapp.generated.resources.member_action_share_rotation_title
import dedun.composeapp.generated.resources.member_action_signatures_body
import dedun.composeapp.generated.resources.member_action_signatures_title
import dedun.composeapp.generated.resources.member_action_signatures_waiting
import dedun.composeapp.generated.resources.member_action_you_left_body
import dedun.composeapp.generated.resources.member_action_you_left_title
import dedun.composeapp.generated.resources.open_the_admin_room
import dedun.composeapp.generated.resources.remove_as_admin
import dedun.composeapp.generated.resources.remove_from_group
import dedun.composeapp.generated.resources.step_down_as_admin
import dedun.composeapp.generated.resources.this_no_longer_applies
import dedun.composeapp.generated.resources.you_can_do_this_now
import dedun.composeapp.generated.resources.you_can_do_this_now_body
import dedun.composeapp.generated.resources.member_action_rotation_pending_title
import dedun.composeapp.generated.resources.member_action_rotation_pending_body
import dedun.composeapp.generated.resources.member_action_rotation_pending_waiting_body
import dedun.composeapp.generated.resources.see_the_rotation
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomDetailRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.MemberActionUnavailableViewModel
import com.machankura.dedun.compose.ui.view.state.MemberAction
import com.machankura.dedun.compose.ui.view.state.MemberActionBlocker
import com.machankura.dedun.compose.ui.view.state.MemberActionUnavailableUIState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Why a member row's action cannot be taken now, and what would change that.
 *
 * Pushed from the row's menu when the item chosen is not open. It leads with the
 * first thing in the way -- the room before the viewer, the viewer before the
 * member -- and lists anything else under it, so a member of a disbanded group
 * hears that the group is gone before hearing they are not an admin. At most one
 * action, where there is one that helps; never the action itself, whose
 * confirmation and wording live on the group's page. See
 * docs/member-row-actions.md, D5 and D6.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberActionUnavailableScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    memberPublicKey: HexKey,
    /** Null for a route naming an action this build does not know: it no longer applies. */
    action: MemberAction?,
    initialUIState: MemberActionUnavailableUIState = MemberActionUnavailableUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: MemberActionUnavailableViewModel = viewModel(
        factory = MemberActionUnavailableViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chatRoomId = chatRoomId,
            memberPublicKey = memberPublicKey,
            action = action,
            initialUIState = initialUIState,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
        ),
    )

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { action?.let { Text(stringResource(it.label())) } },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { innerPadding ->
        // The bar is constant, so the transition wraps the content alone, and none of
        // the branches needs a scope the transition would take away.
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                MemberActionUnavailableUIState.Loading -> LoadingDataIndicator(
                    modifier = Modifier.padding(innerPadding).readableContent().fillMaxWidth(),
                )

                MemberActionUnavailableUIState.Error -> ErrorState(
                    modifier = Modifier.padding(innerPadding).readableContent(),
                    onRetry = { viewModel.reload() },
                )

                MemberActionUnavailableUIState.NoLongerApplies -> EmptyState(
                    modifier = Modifier.padding(innerPadding).readableContent(),
                    message = stringResource(Res.string.this_no_longer_applies),
                    icon = Icons.Default.Info,
                    action = {
                        TextButton(onClick = onNavigateBack) { Text(stringResource(Res.string.back_to_the_group)) }
                    },
                )

                is MemberActionUnavailableUIState.Blocked -> Column(
                    modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap),
                ) {
                    val lead = state.blockers.first()
                    Blocker(
                        blocker = lead,
                        memberName = state.memberName,
                        names = state.names,
                        titleStyle = MaterialTheme.typography.headlineSmall,
                    )

                    when {
                        lead == MemberActionBlocker.NoAdminsInThisRoom && state.adminRoomId != null -> Button(
                            onClick = {
                                onNavigateToRoute(
                                    ChatRoomDetailRoute(
                                        activeUserPublicKey = activeUserPublicKey,
                                        chatRoomId = state.adminRoomId,
                                        relayHint = null,
                                    )
                                )
                            },
                        ) {
                            Text(stringResource(Res.string.open_the_admin_room))
                        }

                        // The switch is on the group's page, under its members.
                        lead is MemberActionBlocker.SignaturesNotRequired -> OutlinedButton(onClick = onNavigateBack) {
                            Text(stringResource(Res.string.back_to_the_group))
                        }
                    }

                    // The rotation is on the group's screen, under Shares, with Abandon
                    // for one that has stalled (docs/admin-change-rotation.md, A10).
                    if (lead is MemberActionBlocker.RotationPending) {
                        Button(onClick = onNavigateBack) {
                            Text(stringResource(Res.string.see_the_rotation))
                        }
                    }

                    val rest = state.blockers.drop(1)
                    if (rest.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
                            Text(
                                text = stringResource(Res.string.also_in_the_way),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            rest.forEach { blocker ->
                                Blocker(
                                    blocker = blocker,
                                    memberName = state.memberName,
                                    names = state.names,
                                    titleStyle = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }

                is MemberActionUnavailableUIState.NowAvailable -> Column(
                    modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = Decorative, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = stringResource(Res.string.you_can_do_this_now),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                    Text(
                        text = stringResource(Res.string.you_can_do_this_now_body),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onNavigateBack) { Text(stringResource(Res.string.back_to_the_group)) }
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialUIState == MemberActionUnavailableUIState.Loading) viewModel.initiate()
    }
}

/**
 * One thing in the way: what it is, and what would change it. The icon names the
 * kind of reason -- the room, the viewer, the room's rules, the member, work in
 * flight -- and the title beside it says the same, so it is decorative.
 */
@Composable
private fun Blocker(
    blocker: MemberActionBlocker,
    memberName: String,
    names: Map<HexKey, String>,
    titleStyle: TextStyle,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Icon(blocker.icon(), contentDescription = Decorative, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = blocker.title(memberName), style = titleStyle)
        }
        Text(text = blocker.body(names), style = MaterialTheme.typography.bodyLarge)
        if (blocker is MemberActionBlocker.SignaturesNotRequired && blocker.waitingOn.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.member_action_signatures_waiting, blocker.waitingOn.namedBy(names)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Set<HexKey>.namedBy(names: Map<HexKey, String>): String =
    map { names[it] ?: it.take(12) }.sorted().joinToString(", ")

private fun MemberActionBlocker.icon(): ImageVector = when (this) {
    MemberActionBlocker.NoAdminsInThisRoom,
    MemberActionBlocker.LegacyRoom,
    MemberActionBlocker.Disbanded,
    MemberActionBlocker.YouLeft -> Icons.Default.Groups
    is MemberActionBlocker.NotAnAdmin -> Icons.Default.Lock
    is MemberActionBlocker.SignaturesNotRequired,
    MemberActionBlocker.ShareRotationUnavailable -> Icons.Default.Key
    MemberActionBlocker.NotInGroupYet -> Icons.Default.Person
    MemberActionBlocker.OnlyAdmin,
    is MemberActionBlocker.BelowFloor -> Icons.Default.AdminPanelSettings
    MemberActionBlocker.ChangePending,
    MemberActionBlocker.OfferPending -> Icons.Default.Schedule
    is MemberActionBlocker.RotationPending -> Icons.Default.Sync
}

@Composable
private fun MemberActionBlocker.title(memberName: String): String = when (this) {
    MemberActionBlocker.NoAdminsInThisRoom -> stringResource(Res.string.member_action_no_admins_title)
    MemberActionBlocker.LegacyRoom -> stringResource(Res.string.member_action_legacy_title)
    MemberActionBlocker.Disbanded -> stringResource(Res.string.member_action_disbanded_title)
    MemberActionBlocker.YouLeft -> stringResource(Res.string.member_action_you_left_title)
    is MemberActionBlocker.NotAnAdmin -> stringResource(Res.string.member_action_not_an_admin_title)
    is MemberActionBlocker.SignaturesNotRequired -> stringResource(Res.string.member_action_signatures_title)
    MemberActionBlocker.ShareRotationUnavailable -> stringResource(Res.string.member_action_share_rotation_title)
    MemberActionBlocker.NotInGroupYet -> stringResource(Res.string.member_action_not_in_group_title, memberName)
    MemberActionBlocker.OnlyAdmin -> stringResource(Res.string.member_action_only_admin_title, memberName)
    is MemberActionBlocker.BelowFloor -> stringResource(Res.string.member_action_below_floor_title, floor.toString())
    MemberActionBlocker.ChangePending -> stringResource(Res.string.member_action_change_pending_title)
    MemberActionBlocker.OfferPending -> stringResource(Res.string.member_action_offer_pending_title, memberName)
    is MemberActionBlocker.RotationPending -> stringResource(Res.string.member_action_rotation_pending_title)
}

@Composable
private fun MemberActionBlocker.body(names: Map<HexKey, String>): String = when (this) {
    MemberActionBlocker.NoAdminsInThisRoom -> stringResource(Res.string.member_action_no_admins_body)
    MemberActionBlocker.LegacyRoom -> stringResource(Res.string.member_action_legacy_body)
    MemberActionBlocker.Disbanded -> stringResource(Res.string.member_action_disbanded_body)
    MemberActionBlocker.YouLeft -> stringResource(Res.string.member_action_you_left_body)
    is MemberActionBlocker.NotAnAdmin -> stringResource(Res.string.member_action_not_an_admin_body, admins.namedBy(names))
    is MemberActionBlocker.SignaturesNotRequired -> stringResource(Res.string.member_action_signatures_body)
    MemberActionBlocker.ShareRotationUnavailable -> stringResource(Res.string.member_action_share_rotation_body)
    MemberActionBlocker.NotInGroupYet -> stringResource(Res.string.member_action_not_in_group_body)
    MemberActionBlocker.OnlyAdmin -> stringResource(Res.string.member_action_only_admin_body)
    is MemberActionBlocker.BelowFloor -> stringResource(Res.string.member_action_below_floor_body)
    MemberActionBlocker.ChangePending -> stringResource(Res.string.member_action_change_pending_body)
    MemberActionBlocker.OfferPending -> stringResource(Res.string.member_action_offer_pending_body)
    is MemberActionBlocker.RotationPending ->
        if (waitingOn.isEmpty()) stringResource(Res.string.member_action_rotation_pending_body)
        else stringResource(Res.string.member_action_rotation_pending_waiting_body, waitingOn.namedBy(names))
}

/** The menu's word for [this], which is also this screen's title. */
internal fun MemberAction.label(): StringResource = when (this) {
    MemberAction.MAKE_ADMIN -> Res.string.make_admin
    MemberAction.REMOVE_AS_ADMIN -> Res.string.remove_as_admin
    MemberAction.STEP_DOWN -> Res.string.step_down_as_admin
    MemberAction.REMOVE_FROM_GROUP -> Res.string.remove_from_group
}
