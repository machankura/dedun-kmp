package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.ask_again
import dedun.composeapp.generated.resources.asked_again
import dedun.composeapp.generated.resources.asked_again_recently
import dedun.composeapp.generated.resources.asked_again_to_sign
import dedun.composeapp.generated.resources.declined_to_sign
import dedun.composeapp.generated.resources.don_t_sign
import dedun.composeapp.generated.resources.events_signed_together
import dedun.composeapp.generated.resources.everyone_has_answered_this_session
import dedun.composeapp.generated.resources.everyone_has_signed_this_session
import dedun.composeapp.generated.resources.fee_s_sats_at_s_sats_per_byte
import dedun.composeapp.generated.resources.has_not_taken_part_yet
import dedun.composeapp.generated.resources.members
import dedun.composeapp.generated.resources.members_have_not_answered
import dedun.composeapp.generated.resources.one_member_has_not_answered
import dedun.composeapp.generated.resources.one_signer_has_not_signed
import dedun.composeapp.generated.resources.read_to_the_end_of_the_list_to_sign
import dedun.composeapp.generated.resources.ready_to_sign
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.s_sats_back_to_the_group_as_change
import dedun.composeapp.generated.resources.sign
import dedun.composeapp.generated.resources.sign_with_the_group_s_key
import dedun.composeapp.generated.resources.signed_by_the_group_any_member_can_send_it
import dedun.composeapp.generated.resources.signed_their_part
import dedun.composeapp.generated.resources.signers_have_not_signed
import dedun.composeapp.generated.resources.spend_from_the_group_s_wallet
import dedun.composeapp.generated.resources.spends_s_of_the_group_s_coins
import dedun.composeapp.generated.resources.sweep_into_the_group_s_onchain_wallet
import dedun.composeapp.generated.resources.sweeps_s_outputs_a_closed_channel_left_the_group
import dedun.composeapp.generated.resources.this_spend_could_not_be_checked
import dedun.composeapp.generated.resources.this_sweep_could_not_be_checked
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.text.ProposedSpend
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.FrostSigningViewModel
import com.machankura.dedun.compose.ui.view.state.FrostSigningUIState
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import org.jetbrains.compose.resources.stringResource

/**
 * One signing session, and the member's decision about it.
 *
 * A ceremony gets three approval screens because it asks three different
 * questions. Signing asks one — sign this or do not — so there is one screen,
 * and it has to carry the whole case for the answer: what is being signed, who
 * else has agreed, and what the group is still waiting on.
 *
 * It stays useful after the decision. A session cannot finish until enough
 * members take part, so a member who has already signed still needs to see
 * whose door to knock on, and the ladder is the only place that says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrostSigningScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    sessionId: String,
    initialFrostSigningUIState: FrostSigningUIState = FrostSigningUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateBack: () -> Unit,
) {
    val frostSigningViewModel: FrostSigningViewModel = viewModel(
        factory = FrostSigningViewModel.factory(
            chatRoomId = chatRoomId,
            sessionId = sessionId,
            activeUserPublicKey = activeUserPublicKey,
            initialFrostSigningUIState = initialFrostSigningUIState,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository
        )
    )

    // Nothing loads the room or starts watching the session until this runs.
    LaunchedEffect(true) {
        if (initialFrostSigningUIState == FrostSigningUIState.Loading) {
            frostSigningViewModel.initiate()
        }
    }

    // Both of these are composable and an onClick lambda is not, so they are
    // read here rather than in the handler. The notifier takes the caller's
    // scope on purpose -- see rememberNotifier.
    val notify = rememberNotifier(rememberCoroutineScope())

    // Four messages for two outcomes, because the knock is not for the same
    // thing at every stage: before the signers are chosen it asks members who
    // have said nothing to answer, and after it asks the chosen signers for the
    // one part nobody else can send. All four are read here, before it is known
    // which stage the session is at, because `stringResource` is composable and
    // an onClick lambda is not.
    val askedAgain = stringResource(Res.string.asked_again)
    val askedAgainToSign = stringResource(Res.string.asked_again_to_sign)
    val everyoneAnswered = stringResource(Res.string.everyone_has_answered_this_session)
    val everyoneSigned = stringResource(Res.string.everyone_has_signed_this_session)

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.sign_with_the_group_s_key),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        when (val state = frostSigningViewModel.frostSigningUIState) {
            is FrostSigningUIState.Loading -> Loading(padding)

            is FrostSigningUIState.Error -> Column(
                modifier = Modifier.fillMaxWidth().padding(padding).readableContent().padding(MaterialTheme.spacing.space250),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                Text(text = state.message, textAlign = TextAlign.Center)
            }

            is FrostSigningUIState.Loaded -> {
                // Loaded means the room is loaded, not the session: initiate() sets
                // this state and only then starts collecting, so the first emission
                // always has a null session. Reading that as "no such session" would
                // flash an error on the way in.
                val session = state.session ?: return@Scaffold Loading(padding)

                val isSpend = session.subject == FrostSigningSubject.TRANSACTION
                val isSweep = session.subject == FrostSigningSubject.SWEEP
                val proposed = if (isSpend || isSweep) emptyList() else frostSigningViewModel.proposedEvents(state.items)

                // Every event of the batch has to be readable before any of it can
                // be signed. A member cannot check what they cannot see, and a
                // batch is all-or-nothing: agreeing to the two that rendered would
                // be agreeing to the third as well. A spend is readable when this
                // device has checked it, every input accounted for.
                val readable = state.items.isNotEmpty() && if (isSpend) {
                    state.spend?.inputs?.size == state.items.size
                } else if (isSweep) {
                    state.sweep?.inputs?.size == state.items.size
                } else {
                    proposed.size == state.items.size
                }

                // What has to be scrolled past before signing: each event of a
                // batch, or each output of a spend.
                val listed = if (isSpend) state.spend?.outputs?.size ?: 0 else if (isSweep) 1 else proposed.size

                val scrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .padding(padding)
                        .readableContent()
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(MaterialTheme.spacing.space250),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200)
                ) {
                    when {
                        isSpend -> WhatIsBeingSpent(state.spend)
                        isSweep -> WhatIsBeingSwept(state.sweep)
                        else -> WhatIsBeingSigned(proposed, state.items.size)
                    }

                    HorizontalDivider()

                    Text(
                        text = statusOf(session, declined = state.declined.size),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    session.failureReason?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    HorizontalDivider()

                    Text(
                        text = stringResource(Res.string.members),
                        style = MaterialTheme.typography.labelMedium
                    )

                    // Named rather than counted, for the same reason the ceremony's
                    // ladder names people: "1 of 2" does not tell anyone whose door
                    // to knock on, and a session stalls until somebody knocks.
                    //
                    // The share holders, not the room. A member added to the room
                    // after its key was made holds no share, and so can neither
                    // sign nor refuse -- listing them would name a door that cannot
                    // answer. See FrostSigningUIState.Loaded.members.
                    state.members.forEach { localParticipant ->
                        val member = localParticipant.participant.participantPublicKey

                        Card {
                            ListItem(
                                leadingContent = {
                                    ProfileAvatar(
                                        publicKey = member,
                                        profile = localParticipant.profile
                                    )
                                },
                                trailingContent = {
                                    when {
                                        member in state.signed -> Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Signed",
                                            tint = MaterialTheme.colorScheme.primary
                                        )

                                        member in state.offeredNonce -> Icon(
                                            Icons.Default.HourglassEmpty,
                                            contentDescription = "Ready to sign"
                                        )

                                        // Quiet rather than red: a refusal the
                                        // session survives is not an error, and
                                        // the one it does not survive ends the
                                        // session, which the status line says.
                                        member in state.declined -> Icon(
                                            Icons.Default.Block,
                                            contentDescription = "Declined",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        else -> Icon(
                                            Icons.Default.RadioButtonUnchecked,
                                            contentDescription = "Not yet",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                headlineContent = {
                                    Text(
                                        text = localParticipant.profile
                                            ?.humanReadableNameOrPubkey()
                                            ?: member
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = when {
                                            member in state.signed -> stringResource(Res.string.signed_their_part)
                                            member in state.offeredNonce -> stringResource(Res.string.ready_to_sign)
                                            member in state.declined -> stringResource(Res.string.declined_to_sign)
                                            else -> stringResource(Res.string.has_not_taken_part_yet)
                                        }
                                    )
                                }
                            )
                        }
                    }

                    // The coordinator's knock, under the list of doors it is for.
                    //
                    // A session can wait forever on a member whose copy of the
                    // proposal never arrived -- MLS dropped the generation it came
                    // in at, or a disappearing-message group expired it off the
                    // relay -- and neither heals on its own. Repeating the proposal
                    // is the only thing that reaches them, and it also names them,
                    // so that a member whose own answer went missing on the way
                    // here says it again.
                    if (state.canResend) {
                        HorizontalDivider()

                        // What they are short of, which is not the same thing at
                        // both stages: past the nonces the members named here
                        // have answered once already, and the rows above say so.
                        val collectingPartials =
                            session.stage == FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES

                        Text(
                            text = when {
                                collectingPartials && state.silent.size == 1 ->
                                    stringResource(Res.string.one_signer_has_not_signed)

                                collectingPartials -> stringResource(
                                    Res.string.signers_have_not_signed,
                                    state.silent.size
                                )

                                state.silent.size == 1 ->
                                    stringResource(Res.string.one_member_has_not_answered)

                                else -> stringResource(
                                    Res.string.members_have_not_answered,
                                    state.silent.size
                                )
                            },
                            style = MaterialTheme.typography.bodySmall
                        )

                        // Disabled rather than hidden while the cooldown runs. A
                        // control that vanishes after a tap reads as one that
                        // failed, and what happened is that it worked.
                        val waiting = state.resendAvailableAt != null

                        if (waiting) {
                            Text(
                                text = stringResource(Res.string.asked_again_recently),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            enabled = !waiting && !frostSigningViewModel.isActionPending.value,
                            onClick = {
                                frostSigningViewModel.resend { sent ->
                                    notify(
                                        when {
                                            sent && collectingPartials -> askedAgainToSign
                                            sent -> askedAgain
                                            collectingPartials -> everyoneSigned
                                            else -> everyoneAnswered
                                        }
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Default.Campaign, contentDescription = Decorative)
                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                            Text(stringResource(Res.string.ask_again))
                        }
                    }

                    // Asked rather than re-derived: the manager owns when a session
                    // is still waiting on its owner, and a second copy of that rule
                    // here is a second copy to keep in step.
                    if (FrostSigningManager.isAwaitingApproval(session, state.items)) {
                        HorizontalDivider()

                        Text(
                            text = "Nothing has been published from this device yet. Signing " +
                                "puts your share behind " +
                                (if (proposed.size > 1) "all ${proposed.size} of these events" else "this event") +
                                "; it cannot be taken back.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        // A batch can hide an event below the fold in a way one
                        // event cannot: what is off-screen is not further detail
                        // about the thing on screen, it is a different thing the
                        // member would also be signing. So a batch's Sign button
                        // waits until the list has been read to the end. maxValue
                        // is Int.MAX_VALUE until the first layout, and 0 when
                        // everything already fits.
                        val seenEverything = listed <= 1 ||
                            (scrollState.maxValue != Int.MAX_VALUE && scrollState.value >= scrollState.maxValue)

                        if (readable && !seenEverything) {
                            Text(
                                text = stringResource(Res.string.read_to_the_end_of_the_list_to_sign),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                enabled = readable && seenEverything &&
                                    !frostSigningViewModel.isActionPending.value,
                                onClick = { frostSigningViewModel.approve(onNavigateBack) }
                            ) {
                                Icon(Icons.Default.Draw, contentDescription = Decorative)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                Text(stringResource(Res.string.sign))
                            }

                            // Declining stays available whatever the screen could
                            // not show. A member who cannot check what they are
                            // being asked to sign should still be able to say no,
                            // and saying nothing is indistinguishable from a phone
                            // in a pocket -- which leaves the group waiting.
                            TextButton(
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                enabled = !frostSigningViewModel.isActionPending.value,
                                onClick = { frostSigningViewModel.decline(onNavigateBack) }
                            ) {
                                Text(stringResource(Res.string.don_t_sign))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading(padding: androidx.compose.foundation.layout.PaddingValues) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(padding).readableContent().padding(MaterialTheme.spacing.space250),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
        CircularProgressIndicator()
    }
}

/**
 * What the group is being asked to put its name to.
 *
 * One entry per event, each said as the thing it is rather than as an event --
 * see [ProposedEvent], which the room's list of proposals says it the same way
 * from. Nothing is described here that could not be read: a batch is
 * all-or-nothing, so a member who cannot see one of its events is being asked to
 * sign something they cannot check.
 */
@Composable
private fun WhatIsBeingSigned(events: List<Event>, expected: Int) {
    if (expected == 0 || events.size != expected) {
        Text(
            text = if (expected <= 1) {
                "This session's event could not be read, so there is nothing to check " +
                    "before signing. Don't sign it."
            } else {
                "Only ${events.size} of this session's $expected events could be read, so " +
                    "there is no way to check what you would be signing. Don't sign it."
            },
            color = MaterialTheme.colorScheme.error
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200)) {
        if (events.size > 1) {
            Text(
                text = stringResource(Res.string.events_signed_together, events.size),
                style = MaterialTheme.typography.labelMedium
            )
        }

        events.forEachIndexed { index, event ->
            if (index > 0) HorizontalDivider()

            OneThingBeingSigned(event)
        }

        Text(
            text = "Signed by the group, not by you. Once enough members sign, " +
                (if (events.size > 1) "these are" else "this is") +
                " published under the group's shared key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The spend, every output of it: what leaves the group and where to, what comes
 * back as change, and the fee. The batch rule holds with more force here -- a
 * member who cannot see every output must not be able to approve one -- so
 * every output is a row, change included, and nothing is behind a disclosure
 * but the coins being spent, which are the group's own and were each checked
 * against the wallet before this was drawn.
 */
/** A sweep: the address it pays, which is the group's own, and what reaches it. */
@Composable
private fun WhatIsBeingSwept(sweep: GroupSweep.Checked?) {
    if (sweep == null) {
        Text(text = stringResource(Res.string.this_sweep_could_not_be_checked), color = MaterialTheme.colorScheme.error)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200)) {
        Text(text = stringResource(Res.string.sweep_into_the_group_s_onchain_wallet), style = MaterialTheme.typography.labelMedium)
        Text(text = stringResource(Res.string.s_sats, sweep.amount.grouped()), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(Res.string.fee_s_sats_at_s_sats_per_byte, sweep.fee.grouped(), sweep.feeratePerVbyte.toString()),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(Res.string.sweeps_s_outputs_a_closed_channel_left_the_group, sweep.inputs.size.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WhatIsBeingSpent(spend: GroupSpend.Checked?) {
    if (spend == null) {
        Text(
            text = stringResource(Res.string.this_spend_could_not_be_checked),
            color = MaterialTheme.colorScheme.error
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200)) {
        Text(
            text = stringResource(Res.string.spend_from_the_group_s_wallet),
            style = MaterialTheme.typography.labelMedium
        )

        spend.outputs.forEach { output ->
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)) {
                Text(
                    text = ProposedSpend.addressOf(output.txOut.publicKeyScript, spend.account.chain),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (output.isChange) {
                        stringResource(Res.string.s_sats_back_to_the_group_as_change, output.txOut.amount.grouped())
                    } else {
                        stringResource(Res.string.s_sats, output.txOut.amount.grouped())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (output.isChange) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider()
        }

        Text(
            text = stringResource(
                Res.string.fee_s_sats_at_s_sats_per_byte,
                spend.fee.grouped(),
                spend.feeratePerVbyte.toString()
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(Res.string.spends_s_of_the_group_s_coins, spend.inputs.size.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(Res.string.signed_by_the_group_any_member_can_send_it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One event of the batch, described as the thing it is. */
@Composable
private fun OneThingBeingSigned(event: Event) {
    val summary = ProposedEvent.summarize(event)

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)) {
        Text(text = summary.label, style = MaterialTheme.typography.labelMedium)

        Text(text = summary.detail, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Where the session has got to, and -- once anybody has refused -- how many
 * refusals it has left in it. A t-of-n session is abandoned only when fewer
 * than t members could still sign, so "2 could still sign" beside "it takes 2"
 * is the reader being told the next refusal ends it.
 */
private fun statusOf(session: FrostSigningSession, declined: Int): String {
    val declinedHere = session.signDeclinedAt != null
    val willing = session.participantCount - declined

    return when (session.stage) {
        FrostSigningStage.COLLECTING_NONCES -> when {
            declinedHere ->
                "You declined to sign. It takes ${session.threshold} of ${session.participantCount} " +
                    "members, and $willing could still sign."

            declined > 0 ->
                "Waiting for ${session.threshold} of ${session.participantCount} members to take " +
                    "part. $declined declined, and $willing could still sign."

            else ->
                "Waiting for ${session.threshold} of ${session.participantCount} members to take part."
        }

        FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES -> when {
            session.isSigner() -> "You are one of the signers. Waiting on the rest of them."
            declinedHere -> "You declined to sign. Enough members took part without you. Waiting on them to sign."
            else -> "Enough members took part without needing you. Waiting on them to sign."
        }

        FrostSigningStage.COMPLETE -> "Signed."

        FrostSigningStage.FAILED -> "Abandoned. Nothing was signed, and it is safe to ask again."
    }
}

@ConformancePreviews
@Composable
private fun FrostSigningScreenPreview() {
    BennuTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            FrostSigningScreen(
                activeUserPublicKey = "",
                chatRoomId = "chatRoomId",
                sessionId = "sessionId",
                initialFrostSigningUIState = FrostSigningUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "chatRoomId",
                            userPublicKey = "",
                            subject = "#Ekklesia",
                            description = null,
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = null
                        )
                    )
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateBack = {}
            )
        }
    }
}
