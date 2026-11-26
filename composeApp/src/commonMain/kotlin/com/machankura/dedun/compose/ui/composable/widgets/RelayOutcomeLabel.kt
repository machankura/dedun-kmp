package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.runtime.Composable
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.relay_accepted
import dedun.composeapp.generated.resources.relay_could_not_send
import dedun.composeapp.generated.resources.relay_no_answer
import dedun.composeapp.generated.resources.relay_rejected
import dedun.composeapp.generated.resources.relay_sending
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.ui.view.model.BroadcastGroupSignedEventViewModel.RelayOutcome

/**
 * One relay's answer to a broadcast, in the words both screens that show one
 * use for it: the row under a relay on `BroadcastGroupSignedEventScreen`, and
 * the line under an entry that "Broadcast all" sent.
 *
 * A rejection and a failure carry the relay's own reason, because "blocked:
 * not on the allow list" is something a member can act on and a red icon is
 * not. One spelling for both screens, so a relay's answer reads the same
 * whichever button sent to it.
 */
@Composable
fun RelayOutcome.label(): String = when (this) {
    RelayOutcome.Sending -> stringResource(Res.string.relay_sending)
    RelayOutcome.Accepted -> stringResource(Res.string.relay_accepted)
    is RelayOutcome.Rejected -> stringResource(Res.string.relay_rejected, reason)
    is RelayOutcome.Failed -> stringResource(Res.string.relay_could_not_send, reason)
    RelayOutcome.NoAnswer -> stringResource(Res.string.relay_no_answer)
}
