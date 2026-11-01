package com.machankura.dedun.compose.ui.composable

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
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.this_is_the_last_thing_the_ceremony_needs

/**
 * Asks whether to confirm the combined result.
 *
 * The one approval of the three that is a check rather than a decision. Every
 * member signs to say the coordinator's combined result matches what they
 * themselves sent, and it is that agreement — not the coordinator's word — which
 * makes the key final. A coordinator trying to substitute a key the members never
 * contributed to is caught precisely here.
 *
 * The check itself is done by the device, which has the member's own round-1
 * message to compare against and would refuse a mismatch outright. So this screen
 * is not asking the member to verify anything by eye; it is asking whether to put
 * their name to a result their device has already found consistent.
 */
@Composable
fun DkgRound2ApprovalScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    dkgSessionId: String,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    dkgRepository: DkgRepository,
    onDone: () -> Unit,
) {
    DkgApprovalScaffold(
        step = DkgApprovalStep.ROUND_2,
        title = "Confirm the shared key?",
        approveLabel = "Confirm",
        activeUserPublicKey = activeUserPublicKey,
        chatRoomId = chatRoomId,
        dkgSessionId = dkgSessionId,
        activeIdentityStateFlow = activeIdentityStateFlow,
        chatRepository = chatRepository,
        dkgRepository = dkgRepository,
        onDone = onDone
    ) { state ->
        val session = state.session

        Text(
            text = "Everyone's contributions have been combined into a " +
                "${session.threshold}-of-${state.participantCount} key.",
            style = MaterialTheme.typography.bodyLarge
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            DkgApprovalPoint(
                "Your device has checked the combined result against what it sent, and they match."
            )
            DkgApprovalPoint(
                "Confirming signs to say so. The key becomes final once every member has confirmed."
            )
            DkgApprovalPoint(
                "It is this step that stops anyone substituting a key the group did not make together."
            )
        }

        Text(
            text = stringResource(Res.string.this_is_the_last_thing_the_ceremony_needs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
