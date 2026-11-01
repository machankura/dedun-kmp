package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.ui.composable.widgets.delayText
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.database.model.subgroupWalletPolicy
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.the_subgroup_s_wallet_will_not_be_guarded
import bennu.composeapp.generated.resources.the_subgroup_s_wallet_will_be_guarded
import bennu.composeapp.generated.resources.Res
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
import com.machankura.dedun.compose.ui.theme.spacing

/**
 * Asks whether to join a shared key ceremony somebody else opened.
 *
 * The first and most consequential of the three approvals. Publishing a host key
 * is what puts this member in the participant set, and the set is what `n` is
 * counted over — so a member who joins and then stops answering does not merely
 * fail to help, they hold the ceremony open for everybody else. Saying that
 * plainly here is the point of the screen.
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
        title = "Join the key ceremony?",
        approveLabel = "Join the ceremony",
        activeUserPublicKey = activeUserPublicKey,
        chatRoomId = chatRoomId,
        dkgSessionId = dkgSessionId,
        activeIdentityStateFlow = activeIdentityStateFlow,
        chatRepository = chatRepository,
        dkgRepository = dkgRepository,
        onDone = onDone
    ) { state ->
        val session = state.session
        val group = state.localChatRoom.chatRoom.subject ?: "this group"

        Text(
            text = "$group is creating a shared key that takes " +
                "${session.threshold} of ${state.participantCount} members to sign with.",
            style = MaterialTheme.typography.bodyLarge
        )

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
            DkgApprovalPoint("Your device publishes the key it will be identified by for the rest of the ceremony.")
            DkgApprovalPoint("You will be asked twice more before anything of your key material goes out.")
            DkgApprovalPoint(
                "The ceremony cannot finish until every member has taken part, so joining and " +
                    "then stopping leaves the group waiting."
            )
        }

        Text(
            text = "Nothing has been published from this device yet. Declining leaves the ceremony " +
                "for the others to abandon or wait on, and you can join later from the shared key screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
