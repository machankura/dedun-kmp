package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.disband_group_instead
import dedun.composeapp.generated.resources.governance_on
import dedun.composeapp.generated.resources.governance_waiting
import dedun.composeapp.generated.resources.governance_switch_waits_for
import dedun.composeapp.generated.resources.governance_switching
import dedun.composeapp.generated.resources.require_signatures_explainer
import dedun.composeapp.generated.resources.make_admin
import dedun.composeapp.generated.resources.make_admin_and_leave
import dedun.composeapp.generated.resources.make_admin_body
import dedun.composeapp.generated.resources.make_admin_body_keyed
import dedun.composeapp.generated.resources.remove_as_admin_body_keyed
import dedun.composeapp.generated.resources.promotion_offer_body
import dedun.composeapp.generated.resources.accept
import dedun.composeapp.generated.resources.make_admin_title
import dedun.composeapp.generated.resources.member_actions
import dedun.composeapp.generated.resources.pick_heir_body
import dedun.composeapp.generated.resources.leave_as_admin_title
import dedun.composeapp.generated.resources.remove_as_admin
import dedun.composeapp.generated.resources.remove_as_admin_body
import dedun.composeapp.generated.resources.remove_as_admin_title
import dedun.composeapp.generated.resources.step_down
import dedun.composeapp.generated.resources.step_down_and_leave
import dedun.composeapp.generated.resources.step_down_and_leave_body
import dedun.composeapp.generated.resources.step_down_body
import dedun.composeapp.generated.resources.step_down_title
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.view.state.AdminLeave
import com.machankura.dedun.compose.ui.view.state.GovernanceStatus
import com.machankura.dedun.compose.ui.view.state.MemberAction
import org.jetbrains.compose.resources.stringResource

/**
 * The member row's overflow menu: the actions [actions] allows, and nothing else.
 * The view model decides them; this only lays them out. A 48dp `IconButton`, as
 * every target is. See docs/member-admin-promotion.md, Phase 11.
 */
@Composable
internal fun MemberActionsMenu(
    actions: Set<MemberAction>,
    onAction: (MemberAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.member_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // In a fixed order, whatever order the set holds them in.
            MemberAction.entries.filter { it in actions }.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.label())) },
                    onClick = {
                        expanded = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

/**
 * Asks before a change to who administers the room. Promotion says what it gives
 * away -- the power to remove the person granting it, and to disband the group --
 * because that is the part nobody thinks of in the moment. [memberName] is the
 * member's display name; it is unused for [MemberAction.STEP_DOWN].
 */
@Composable
internal fun ConfirmAdminChangeDialog(
    action: MemberAction,
    memberName: String,
    keyed: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, body, confirm) = when (action) {
        MemberAction.MAKE_ADMIN -> Triple(
            stringResource(Res.string.make_admin_title, memberName),
            stringResource(if (keyed) Res.string.make_admin_body_keyed else Res.string.make_admin_body),
            stringResource(Res.string.make_admin),
        )
        MemberAction.REMOVE_AS_ADMIN -> Triple(
            stringResource(Res.string.remove_as_admin_title, memberName),
            stringResource(if (keyed) Res.string.remove_as_admin_body_keyed else Res.string.remove_as_admin_body),
            stringResource(Res.string.remove_as_admin),
        )
        MemberAction.STEP_DOWN -> Triple(
            stringResource(Res.string.step_down_title),
            stringResource(Res.string.step_down_body),
            stringResource(Res.string.step_down),
        )
        // Removal keeps the immediate action it always had.
        MemberAction.REMOVE_FROM_GROUP -> return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * **Leave group**, pressed by an admin, who cannot simply leave. Another admin
 * remaining: step down, then leave. The only admin: pick who takes over, or
 * disband instead -- the choice of who governs the group afterwards is the
 * person's, not the device's. docs/member-admin-promotion.md, Phase 10.
 *
 * [candidates] are the other members, by public key and display name, for the
 * [AdminLeave.PICK_HEIR] case.
 */
@Composable
internal fun LeaveAsAdminDialog(
    adminLeave: AdminLeave,
    candidates: List<Pair<HexKey, String>>,
    onStepDownAndLeave: (heir: HexKey?) -> Unit,
    onDisband: () -> Unit,
    onDismiss: () -> Unit,
) {
    var heir by remember { mutableStateOf<HexKey?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.leave_as_admin_title)) },
        text = {
            when (adminLeave) {
                AdminLeave.PICK_HEIR -> Column(modifier = Modifier.selectableGroup()) {
                    Text(stringResource(Res.string.pick_heir_body))
                    candidates.forEach { (publicKey, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = heir == publicKey, onClick = { heir = publicKey }, role = Role.RadioButton)
                                .minimumInteractiveComponentSize()
                                .padding(vertical = MaterialTheme.spacing.compactPadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The row is the target; the button only shows the state.
                            RadioButton(selected = heir == publicKey, onClick = null)
                            Text(name, modifier = Modifier.padding(start = MaterialTheme.spacing.relatedGap))
                        }
                    }
                    TextButton(onClick = onDisband) { Text(stringResource(Res.string.disband_group_instead)) }
                }
                else -> Text(stringResource(Res.string.step_down_and_leave_body))
            }
        },
        confirmButton = {
            when (adminLeave) {
                AdminLeave.PICK_HEIR -> TextButton(onClick = { onStepDownAndLeave(heir) }, enabled = heir != null) {
                    Text(stringResource(Res.string.make_admin_and_leave))
                }
                else -> TextButton(onClick = { onStepDownAndLeave(null) }) {
                    Text(stringResource(Res.string.step_down_and_leave))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * Whether a keyed room's changes need the group's signature. Once they do, a line
 * saying so; before, for an admin, whose device is switching it on -- or who it is
 * still waiting on, since switching it on with a member whose device cannot follow
 * would leave them in a different group from the first change.
 * docs/member-admin-promotion.md, Phase 4b.
 *
 * Nothing to press: one device switches it on by itself once everybody can follow.
 * docs/remove-convenient-groups.md, "After the build".
 */
@Composable
internal fun GovernanceSection(
    governance: GovernanceStatus,
    canGovern: Boolean,
    nameOf: (HexKey) -> String,
    me: HexKey,
) {
    val explainer = Modifier.padding(
        horizontal = MaterialTheme.spacing.containerPadding,
        vertical = MaterialTheme.spacing.compactPadding,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        when (governance) {
            is GovernanceStatus.Governed -> Text(
                text = stringResource(Res.string.governance_on, governance.minimumAdmins),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = explainer,
            )
            is GovernanceStatus.Ready -> if (canGovern && governance.switcher != null) {
                Text(
                    text = if (governance.switcher == me) {
                        stringResource(Res.string.governance_switching)
                    } else {
                        stringResource(Res.string.governance_switch_waits_for, nameOf(governance.switcher))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = explainer,
                )
                Text(
                    text = stringResource(Res.string.require_signatures_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = explainer,
                )
            }
            is GovernanceStatus.Waiting -> if (canGovern) {
                Text(
                    text = stringResource(Res.string.governance_waiting, governance.missing.map(nameOf).sorted().joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = explainer,
                )
            }
        }
    }
}

/**
 * An offer to this account to become an admin of a keyed room, with the one
 * decision only its owner can make: accepting puts them in the group's signing
 * quorum. docs/member-admin-promotion.md, D3 and Phase 8.
 */
@Composable
internal fun PromotionOfferCard(fromName: String, onAccept: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.containerPadding, vertical = MaterialTheme.spacing.compactPadding)) {
        Text(
            text = stringResource(Res.string.promotion_offer_body, fromName),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onAccept) {
            Icon(Icons.Default.Verified, contentDescription = Decorative)
            Text(stringResource(Res.string.accept), modifier = Modifier.padding(start = MaterialTheme.spacing.relatedGap))
        }
    }
}
