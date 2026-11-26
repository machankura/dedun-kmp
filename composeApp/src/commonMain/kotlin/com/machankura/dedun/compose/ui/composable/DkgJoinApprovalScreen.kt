package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.ui.composable.widgets.delayText
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.database.model.subgroupWalletPolicy
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.the_subgroup_s_wallet_will_not_be_guarded
import dedun.composeapp.generated.resources.the_subgroup_s_wallet_will_be_guarded
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.you_already_share_with_these_people_this_makes_a_separate_group
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.flow.StateFlow
import dedun.composeapp.generated.resources.join_the_ceremony
import dedun.composeapp.generated.resources.join_the_key_ceremony_question
import dedun.composeapp.generated.resources.nothing_has_been_published_from_this_device_yet_declining
import dedun.composeapp.generated.resources.once_everyone_has_joined_your_device_contributes_to
import dedun.composeapp.generated.resources.s_is_creating_a_shared_key_that_takes
import dedun.composeapp.generated.resources.taking_part_with_these_members
import dedun.composeapp.generated.resources.the_ceremony_cannot_finish_until_every_member_has
import dedun.composeapp.generated.resources.this_group
import dedun.composeapp.generated.resources.you
import dedun.composeapp.generated.resources.your_device_publishes_the_key_it_will_be
import dedun.composeapp.generated.resources.your_share_of_the_key_never_leaves_this
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.managers.MarmotGroupName

/**
 * Asks whether to join a shared key ceremony somebody else opened.
 *
 * The one approval a ceremony asks for (docs/automatic-key-ceremony.md). Publishing
 * a host key is what puts this member in the participant set, and the set is what
 * `n` is counted over — so a member who joins and then stops answering does not
 * merely fail to help, they hold the ceremony open for everybody else. Saying that
 * plainly here is the point of the screen.
 *
 * Everything after it runs without asking: the device contributes once everyone
 * has joined, checks the combined result itself and confirms it. So this screen
 * carries what the round-one screen used to, the members in particular. Who the
 * key is being made with was settled by the proposal before anyone joined, so it
 * was never something a later screen knew and this one could not.
 *
 * Until this is approved nothing has left the device, which is worth stating
 * outright: the member is being asked to start, not to continue.
 */
@Composable
fun DkgJoinApprovalScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    dkgSessionId: String,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    dkgRepository: DkgRepository,
    onDone: () -> Unit,
) {
    DkgApprovalScaffold(
        step = DkgApprovalStep.HOST_KEY,
        title = stringResource(Res.string.join_the_key_ceremony_question),
        approveLabel = stringResource(Res.string.join_the_ceremony),
        activeUserPublicKey = activeUserPublicKey,
        chatRoomId = chatRoomId,
        dkgSessionId = dkgSessionId,
        activeIdentityStateFlow = activeIdentityStateFlow,
        chatRepository = chatRepository,
        dkgRepository = dkgRepository,
        onDone = onDone
    ) { state ->
        val session = state.session
        // The group the ceremony makes, not the room it is held in: the same people
        // share one NIP-17 room however many groups they make, and the room keeps
        // the first one's name (docs/nip17-multiple-ceremonies.md).
        val group = (session.subject ?: state.localChatRoom.chatRoom.subject)
            ?.let(MarmotGroupName::display)
            ?: stringResource(Res.string.this_group)

        Text(
            text = stringResource(
                Res.string.s_is_creating_a_shared_key_that_takes,
                group,
                session.threshold,
                state.participantCount
            ),
            style = MaterialTheme.typography.bodyLarge
        )

        // Joining is the one decision a member makes, so they should know it is not
        // the group they already have with these people.
        if (state.otherGroups.isNotEmpty()) {
            Text(
                text = stringResource(
                    Res.string.you_already_share_with_these_people_this_makes_a_separate_group,
                    state.otherGroups.joinToString(", ", transform = MarmotGroupName::display)
                ),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // What the subgroup's wallet will be, as the coordinator chose it and the
        // parent's quorum will sign it -- the one place an admin is told before
        // joining is agreeing to it. A ceremony with no policy says nothing.
        // See docs/subgroup-wallet-policy.md.
        session.subgroupWalletPolicy?.let { policy ->
            Text(
                text = when (policy) {
                    // The parent's own leaf's delay, as the coordinator chose it --
                    // or the default an older build's ceremony is certified at.
                    // An inherited ancestor's is not here: a member deciding
                    // whether to join cannot check it. docs/guardian-delay.md.
                    SubgroupWalletPolicy.GUARDED -> stringResource(
                        Res.string.the_subgroup_s_wallet_will_be_guarded,
                        delayText(session.guardianDelay ?: GuardianDerivation.DEFAULT_DELAY)
                    )
                    SubgroupWalletPolicy.UNGUARDED -> stringResource(Res.string.the_subgroup_s_wallet_will_not_be_guarded)
                },
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            DkgApprovalPoint(stringResource(Res.string.your_device_publishes_the_key_it_will_be))
            DkgApprovalPoint(stringResource(Res.string.once_everyone_has_joined_your_device_contributes_to))
            DkgApprovalPoint(stringResource(Res.string.your_share_of_the_key_never_leaves_this))
            DkgApprovalPoint(stringResource(Res.string.the_ceremony_cannot_finish_until_every_member_has))
        }

        // The members, named: this is the last moment "who am I making a key
        // with" is a question the member can act on, and from here it is the
        // only one they are asked.
        Text(
            text = stringResource(Res.string.taking_part_with_these_members),
            style = MaterialTheme.typography.titleSmall
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
        ) {
            state.ritualMembers.forEach { member ->
                val key = member.participant.participantPublicKey

                Text(
                    text = if (key == activeUserPublicKey) {
                        stringResource(Res.string.you)
                    } else {
                        member.profile?.humanReadableNameOrPubkey() ?: key.take(12)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = stringResource(Res.string.nothing_has_been_published_from_this_device_yet_declining),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
