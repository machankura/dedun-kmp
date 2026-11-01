package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.time.Clock
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.view.model.ProposalListViewModel
import com.machankura.dedun.compose.ui.view.state.ProposalFilter
import com.machankura.dedun.compose.ui.view.state.ProposalListUIState
import com.machankura.dedun.compose.ui.view.state.ProposalState
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.abandoned
import bennu.composeapp.generated.resources.some_proposals_had_already_settled
import bennu.composeapp.generated.resources.asked_again_about_all
import bennu.composeapp.generated.resources.ask_all_again
import bennu.composeapp.generated.resources.everyone_has_signed_this_session
import bennu.composeapp.generated.resources.asked_again_to_sign
import bennu.composeapp.generated.resources.asked_again_recently
import bennu.composeapp.generated.resources.asked_again
import bennu.composeapp.generated.resources.ask_again
import bennu.composeapp.generated.resources.you_declined
import bennu.composeapp.generated.resources.you_are_signing
import bennu.composeapp.generated.resources.you_agreed
import bennu.composeapp.generated.resources.signed_by_the_group
import bennu.composeapp.generated.resources.of_signatures_are_in
import bennu.composeapp.generated.resources.of_members_have_offered_to_sign
import bennu.composeapp.generated.resources.needs_your_review
import bennu.composeapp.generated.resources.enough_members_took_part_without_you
import bennu.composeapp.generated.resources.abandoned_nothing_was_signed
import bennu.composeapp.generated.resources.everything_else
import bennu.composeapp.generated.resources.filter_all
import bennu.composeapp.generated.resources.filter_and_count
import bennu.composeapp.generated.resources.being_signed
import bennu.composeapp.generated.resources.needs_you
import bennu.composeapp.generated.resources.no_proposal_has_been_abandoned
import bennu.composeapp.generated.resources.no_proposal_is_being_signed
import bennu.composeapp.generated.resources.no_proposal_is_waiting_on_members
import bennu.composeapp.generated.resources.nothing_is_waiting_for_your_signature
import bennu.composeapp.generated.resources.signed
import bennu.composeapp.generated.resources.this_group_has_not_signed_anything_yet
import bennu.composeapp.generated.resources.waiting_on_members
import bennu.composeapp.generated.resources.proposals
import bennu.composeapp.generated.resources.review
import bennu.composeapp.generated.resources.this_group_has_not_been_asked_to_sign
import bennu.composeapp.generated.resources.waiting_for_you
import bennu.composeapp.generated.resources.of_them_could_not_be_read
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

/**
 * Everything the group has asked its shared key to sign.
 *
 * The transcript shows a proposal as it happens, in among everything else that
 * was said, and answers one question about it: is this still asking something of
 * you. That is enough for a room signing one thing at a time and not enough for
 * a room signing two -- a chapter and its translation go out as two sessions,
 * and the transcript has no way to show that the second is still open once the
 * first is done.
 *
 * So: one row per proposal, each carrying its own state, oldest kept rather than
 * dropped. The ones still waiting on the reader come first, because those are
 * the only ones with anything to do in them; the rest are history, and history
 * is the other half of what this screen is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalListScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    initialProposalListUIState: ProposalListUIState = ProposalListUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (Route) -> Unit,
    initialFilter: ProposalFilter = ProposalFilter.ALL,
) {
    val proposalListViewModel: ProposalListViewModel = viewModel(
        factory = ProposalListViewModel.factory(
            chatRoomId = chatRoomId,
            initialProposalListUIState = initialProposalListUIState,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository,
            initialFilter = initialFilter
        )
    )

    LaunchedEffect(true) {
        if (initialProposalListUIState == ProposalListUIState.Loading) {
            proposalListViewModel.initiate()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.proposals),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(proposalListViewModel.proposalListUIState) { uiState ->
            when (val state = uiState) {
                is ProposalListUIState.Loading -> LoadingDataIndicator(
                    modifier = Modifier.fillMaxSize().padding(padding).readableContent()
                )

                is ProposalListUIState.Error -> ErrorState(
                    message = state.message,
                    // Nothing to retry: the room either exists on this device or
                    // it does not, and looking again will not change that.
                    onRetry = null,
                    modifier = Modifier.fillMaxSize().padding(padding).readableContent()
                )

                is ProposalListUIState.Loaded -> {
                    // Read above the handlers, because `rememberNotifier` and
                    // `stringResource` are composable and an onClick is not. The
                    // notifier takes this screen's scope on purpose: a resend is
                    // often the last thing a coordinator does before leaving.
                    val notify = rememberNotifier(rememberCoroutineScope())
                    val asked = stringResource(Res.string.asked_again)
                    val askedToSign = stringResource(Res.string.asked_again_to_sign)
                    val nobodyLeft = stringResource(Res.string.everyone_has_signed_this_session)

                    // Parameterised only on the count the composition already
                    // knows -- how many were asked about -- so both are read here
                    // with the rest. How many of them went out is not known until
                    // the run finishes, and the one message that would want it is
                    // for a case rare enough to say in words: a proposal settles
                    // between the tap and its turn only if somebody else's
                    // signature lands in that second.
                    val askedAbout = state.askableVisible.size
                    val askedAll = stringResource(Res.string.asked_again_about_all, askedAbout)
                    val someSettled = stringResource(Res.string.some_proposals_had_already_settled)

                    val onAskAll: () -> Unit = {
                        proposalListViewModel.askAllAgain { sent, of ->
                            notify(if (sent == of) askedAll else someSettled)
                        }
                    }

                    val askAgain: (ProposalListUIState.Proposal) -> Unit = { proposal ->
                        val signing = proposal.state == ProposalState.BEING_SIGNED

                        proposalListViewModel.askAgain(proposal.session.id) { sent ->
                            notify(
                                when {
                                    sent && signing -> askedToSign
                                    sent -> asked
                                    // Nothing went out. The row cannot tell a
                                    // cooldown from an empty queue, so it says
                                    // the one the button is only offered under.
                                    else -> nobodyLeft
                                }
                            )
                        }
                    }

                    val open: (ProposalListUIState.Proposal) -> Unit = { proposal ->
                        onNavigateToRoute.invoke(
                            FrostSigningRoute(
                                activeUserPublicKey = activeUserPublicKey,
                                chatRoomId = chatRoomId,
                                sessionId = proposal.session.id
                            )
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).readableContent()
                    ) {
                        // Outside the list rather than its first item, because a
                        // control that scrolls away is a control the reader has to
                        // scroll back for -- and the list it filters is as long as
                        // the group's whole signing history.
                        //
                        // A room with nothing in it has nothing to filter, and five
                        // chips reading 0 over an empty screen would be five ways of
                        // saying what the empty state says once.
                        if (state.proposals.isNotEmpty()) {
                            ProposalFilterRow(
                                selected = state.filter,
                                counts = state.counts,
                                onSelect = proposalListViewModel::select
                            )
                        }

                        // Under the chips, because what it acts on is what the
                        // chips chose. From two up: one is what the row's own
                        // button is for, and a bulk control over a single
                        // proposal is a second way to do one thing, further from
                        // the row it affects.
                        if (state.canAskAll) {
                            AskAllAgainButton(
                                count = askedAbout,
                                asking = state.askableVisible.any {
                                    proposalListViewModel.isAsking(it.session.id)
                                },
                                onAskAll = onAskAll
                            )
                        }

                        ProposalList(
                            state = state,
                            onOpen = open,
                            onAskAgain = askAgain,
                            isAsking = proposalListViewModel::isAsking,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The rows the current filter asks for.
 *
 * Two layouts behind one list, and which one is drawn is the filter's doing.
 * Unfiltered, the sections stay: what is waiting on the reader is a different
 * kind of thing to read from what the group has done, and burying the first in
 * the second is what this screen was written to stop. Filtered, the sections go
 * -- "waiting for you" against "everything else" is a distinction the reader has
 * already made by tapping a chip, and heading a single-state list with it would
 * be answering a question nobody asked.
 */
@Composable
private fun ProposalList(
    state: ProposalListUIState.Loaded,
    onOpen: (ProposalListUIState.Proposal) -> Unit,
    onAskAgain: (ProposalListUIState.Proposal) -> Unit,
    isAsking: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    // Said rather than defaulted, because five different absences used to say one
    // thing here. A filter with nothing in it is not the same fact as a group that
    // has never been asked to sign, and the queue running dry is not an absence at
    // all -- it is the reader having finished.
    if (state.visible.isEmpty()) {
        EmptyState(
            message = when (state.filter) {
                ProposalFilter.ALL -> stringResource(Res.string.this_group_has_not_been_asked_to_sign)
                ProposalFilter.NEEDS_YOU -> stringResource(Res.string.nothing_is_waiting_for_your_signature)
                ProposalFilter.WAITING_ON_MEMBERS ->
                    stringResource(Res.string.no_proposal_is_waiting_on_members)
                ProposalFilter.BEING_SIGNED -> stringResource(Res.string.no_proposal_is_being_signed)
                ProposalFilter.SIGNED -> stringResource(Res.string.this_group_has_not_signed_anything_yet)
                ProposalFilter.ABANDONED -> stringResource(Res.string.no_proposal_has_been_abandoned)
            },
            icon = when (state.filter) {
                // The queue emptying is the end of the work, not a blank screen,
                // so it keeps the icon the rows it replaced were carrying.
                ProposalFilter.NEEDS_YOU -> Icons.Default.Draw
                else -> Icons.Default.Inbox
            },
            modifier = modifier.fillMaxSize()
        )

        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(MaterialTheme.spacing.containerPadding),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
    ) {
        if (state.filter != ProposalFilter.ALL) {
            items(items = state.visible, key = { it.session.id }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    onClick = { onOpen(proposal) },
                    onAskAgain = { onAskAgain(proposal) },
                    asking = isAsking(proposal.session.id)
                )
            }

            return@LazyColumn
        }

        if (state.waitingForYou.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(Res.string.waiting_for_you),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(
                items = state.waitingForYou,
                key = { it.session.id }
            ) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    onClick = { onOpen(proposal) },
                    onAskAgain = { onAskAgain(proposal) },
                    asking = isAsking(proposal.session.id)
                )
            }
        }

        if (state.rest.isNotEmpty()) {
            // Headed only when there is a section above to tell it
            // apart from; on its own it is the whole screen, which
            // the title already says.
            if (state.waitingForYou.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(Res.string.everything_else),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            items(
                items = state.rest,
                key = { it.session.id }
            ) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    onClick = { onOpen(proposal) },
                    onAskAgain = { onAskAgain(proposal) },
                    asking = isAsking(proposal.session.id)
                )
            }
        }
    }
}

/**
 * One chip per state, and one for all of them.
 *
 * Scrolls sideways rather than wrapping. Five chips carrying counts will not fit
 * 360dp at the largest text size the reader can ask for, and a row that reflows
 * to a second line moves the list under the thumb that was about to tap it. The
 * first chip is [ProposalFilter.NEEDS_YOU]'s neighbour on purpose: the reason to
 * arrive here with a filter in mind is almost always that one.
 *
 * Every chip is present even at zero, which is the same reasoning from the other
 * side -- chips that come and go as sessions advance move the row, and sessions
 * advance while this screen is open.
 */
@Composable
private fun ProposalFilterRow(
    selected: ProposalFilter,
    counts: Map<ProposalState, Int>,
    onSelect: (ProposalFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = MaterialTheme.spacing.containerPadding,
                vertical = MaterialTheme.spacing.compactPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
    ) {
        for (filter in ProposalFilter.entries) {
            val label = labelOf(filter)

            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        text = when (val state = filter.state) {
                            // "All" carries no number: it is every chip's counts
                            // added up, which the chips beside it already say.
                            null -> label
                            else -> stringResource(
                                Res.string.filter_and_count,
                                label,
                                counts[state] ?: 0
                            )
                        }
                    )
                }
            )
        }
    }
}

/** What a chip calls its state, in sentence case because M3 says so. */
@Composable
private fun labelOf(filter: ProposalFilter): String = when (filter) {
    ProposalFilter.ALL -> stringResource(Res.string.filter_all)
    ProposalFilter.NEEDS_YOU -> stringResource(Res.string.needs_you)
    ProposalFilter.WAITING_ON_MEMBERS -> stringResource(Res.string.waiting_on_members)
    ProposalFilter.BEING_SIGNED -> stringResource(Res.string.being_signed)
    ProposalFilter.SIGNED -> stringResource(Res.string.signed)
    ProposalFilter.ABANDONED -> stringResource(Res.string.abandoned)
}

/**
 * One proposal, named after the first thing it signs.
 *
 * A batch is named after its first item because that is the one the rest hang
 * off -- a chapter's chunks carry the chapter's id, so the chapter is what the
 * proposal is about and the chunks are how big it is. The count says the rest.
 */
@Composable
private fun ProposalCard(
    proposal: ProposalListUIState.Proposal,
    onClick: () -> Unit,
    onAskAgain: (() -> Unit)? = null,
    asking: Boolean = false,
) {
    val lead = proposal.signs.firstOrNull()

    // A row is named after its lead, and has to say something when there is no
    // lead to name it after. Two ways for that to happen and they are different
    // situations: a session can exist before its proposal has arrived, and a
    // proposal can arrive holding events this build cannot read.
    val label = lead?.label ?: "Proposal"
    val detail = lead?.detail?.takeIf { it.isNotBlank() }
        ?: if (proposal.eventCount == 0) {
            "Nothing has arrived to sign yet"
        } else {
            "None of its events could be read"
        }

    // Every colour inside this card is derived from the card, rather than reached for
    // independently, because ListItem does not inherit LocalContentColor -- its headline
    // comes from ListTokens.ItemLabelTextColor, which is `onSurface`. With the container
    // at `primaryContainer` and the headline at `onSurface`, the light scheme drew
    // #1B1B1B on #1B1B1B: 1.00:1, invisible, and applied to exactly the proposals that
    // await your signature. The icon tint (`primary`) was 1.22:1, the supporting text
    // 1.84:1 and the error line 2.67:1. Only the dark scheme was legible, because there
    // `primaryContainer` is black.
    val cardColors = if (proposal.awaitsYou) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    } else {
        CardDefaults.cardColors()
    }
    // `primaryContainer` is kept as the highlight so this stays a fix rather than a
    // restyle. It is a near-black card in the light scheme, and `secondaryContainer` --
    // the brand gold, 4.56:1 against its own content -- would read more like "this needs
    // you". That is a design call, not an accessibility one.
    val cardContentColor = cardColors.contentColor

    Card(
        onClick = onClick,
        colors = cardColors
    ) {
        ListItem(
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = cardContentColor,
                overlineColor = cardContentColor,
                supportingColor = cardContentColor,
                leadingIconColor = cardContentColor,
                trailingIconColor = cardContentColor,
            ),
            leadingContent = {
                Icon(
                    imageVector = when (proposal.state) {
                        ProposalState.NEEDS_YOU -> Icons.Default.Draw
                        ProposalState.SIGNED -> Icons.Default.CheckCircle
                        ProposalState.ABANDONED -> Icons.Default.ErrorOutline
                        // People against machinery: one is short of members
                        // willing to sign, the other is the chosen ones
                        // finishing. The shapes say which without the colour,
                        // which is the only signal a monochrome display has.
                        ProposalState.WAITING_ON_MEMBERS -> Icons.Default.Groups
                        ProposalState.BEING_SIGNED -> Icons.Default.HourglassEmpty
                    },
                    // Carries the stage on its own, and carries it alone on the
                    // highlighted card where the failure state has no colour to spare.
                    contentDescription = when (proposal.state) {
                        ProposalState.NEEDS_YOU -> "Awaiting your signature"
                        ProposalState.SIGNED -> "Signed"
                        ProposalState.ABANDONED -> "Failed"
                        ProposalState.WAITING_ON_MEMBERS -> "Waiting on members"
                        ProposalState.BEING_SIGNED -> "Being signed"
                    },
                    tint = when (proposal.state) {
                        // `error` is 2.67:1 on the highlighted card, so on that one the
                        // failure is carried by the icon shape and the supporting line
                        // rather than by colour -- which is the more robust signal
                        // anyway, and the only one available to a monochrome display.
                        // A failed session is never NEEDS_YOU, so that card is never
                        // the highlighted one and the exception no longer needs stating.
                        ProposalState.ABANDONED -> MaterialTheme.colorScheme.error
                        else -> cardContentColor
                    }
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The same word the transcript uses for the same thing, so a
                    // member who came here looking for the line they tapped finds
                    // it saying what it said there.
                    if (proposal.awaitsYou) {
                        Text(
                            text = stringResource(Res.string.review),
                            style = MaterialTheme.typography.labelLarge,
                            color = cardContentColor
                        )
                    }

                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Open this proposal"
                    )
                }
            },
            overlineContent = {
                Text(text = label)
            },
            headlineContent = {
                Text(
                    text = detail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space25)) {
                    if (proposal.eventCount > 1) {
                        Text(
                            text = "with ${proposal.eventCount - 1} more " +
                                (if (proposal.eventCount == 2) "event" else "events") +
                                ", signed together"
                        )
                    }

                    // A batch is all-or-nothing, so an item nobody can read is a
                    // reason to refuse the whole proposal. It is said on the row
                    // rather than left for the screen behind it.
                    if (proposal.unreadable > 0) {
                        Text(
                            text = stringResource(Res.string.of_them_could_not_be_read, proposal.unreadable),
                            color = if (proposal.awaitsYou) {
                                cardContentColor
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }

                    Text(text = statusOf(proposal), color = cardContentColor)

                    proposal.progress?.let { progress ->
                        ProgressLine(progress = progress, color = cardContentColor)
                    }

                    Text(
                        text = "${proposal.proposerName} · " +
                            proposal.session.createdAt.toFormattedTimeAndDateString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (onAskAgain != null && proposal.canAskAgain) {
                        AskAgainButton(
                            proposal = proposal,
                            asking = asking,
                            onAskAgain = onAskAgain
                        )
                    }
                }
            }
        )
    }
}

/**
 * Asks again about every proposal the filter has in front of the reader.
 *
 * The bulk form of the row's own button, and it earns its place on exactly the
 * screen the filters made: "being signed" with three rows in it is three
 * proposals stalled for the same reason, and asking about them one at a time is
 * three taps to say one thing.
 *
 * It says how many, because that is how many publishes the tap causes. The run
 * itself is paced a second apart -- see `FrostSigningManager.RESEND_SPACING` --
 * so what the button starts is not a burst, and the rows it covers go quiet
 * together for its duration rather than one at a time.
 */
@Composable
private fun AskAllAgainButton(
    count: Int,
    asking: Boolean,
    onAskAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.containerPadding),
    ) {
        TextButton(enabled = !asking, onClick = onAskAll) {
            Icon(Icons.Default.Campaign, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.itemGap))
            Text(stringResource(Res.string.ask_all_again, count))
        }
    }
}

/**
 * Asks the members a proposal is still waiting on to take another look.
 *
 * The same offer the signing screen makes, and deliberately the same words: a
 * coordinator who went looking for it there and found it here should not have
 * to work out that "re-broadcast" and "ask again" are one button. What the list
 * adds is reach -- the one proposal that has stalled is found by filtering to
 * it, rather than by opening each in turn.
 *
 * Disabled rather than hidden while the cooldown runs. A control that vanishes
 * after a tap reads as one that failed, and what happened is that it worked.
 */
@Composable
private fun AskAgainButton(
    proposal: ProposalListUIState.Proposal,
    asking: Boolean,
    onAskAgain: () -> Unit,
) {
    val waiting = proposal.askAgainAt != null

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space25)) {
        if (waiting) {
            Text(
                text = stringResource(Res.string.asked_again_recently),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TextButton(
            enabled = !waiting && !asking,
            onClick = onAskAgain,
            contentPadding = PaddingValues(
                horizontal = MaterialTheme.spacing.compactPadding,
                vertical = MaterialTheme.spacing.space25,
            )
        ) {
            Icon(Icons.Default.Campaign, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.itemGap))
            Text(stringResource(Res.string.ask_again))
        }
    }
}

/**
 * Who the reader is in this proposal, in a line.
 *
 * Shorter than the signing screen's version of the same question, and it has to
 * be: this is one row of a list somebody is scanning, not the page they are
 * deciding on. What it must never do is call a session finished when it is not,
 * or waiting when the decision has gone.
 *
 * It no longer carries any numbers. It used to say "waiting for 2 of 3 members
 * to take part", which is the rule rather than the progress -- a row said the
 * same thing on the day a proposal was made and on the day before it completed.
 * The fraction that does move is [ProgressLine]'s, and two fractions on one row
 * meaning different things would be worse than the one that said nothing.
 *
 * Still switches on the stage rather than on `Proposal.state`, and still has to,
 * though for less than it used to. What is left collapsed inside a state is who
 * the reader was in it: whether they agreed or declined, under
 * [ProposalState.WAITING_ON_MEMBERS], and whether they were picked to sign,
 * under [ProposalState.BEING_SIGNED]. Those are facts about the reader rather
 * than about the proposal, which is why they are a sentence on the row and not a
 * chip above it.
 */
@Composable
private fun statusOf(proposal: ProposalListUIState.Proposal): String {
    val session = proposal.session

    if (proposal.state == ProposalState.NEEDS_YOU) {
        return stringResource(Res.string.needs_your_review)
    }

    return when (session.stage) {
        // Not awaiting the reader and still collecting means they answered, one
        // way or the other. A refusal leaves the session open -- a t-of-n key
        // can do without this member -- so it is still a session in progress,
        // and the row has to say which answer was given.
        FrostSigningStage.COLLECTING_NONCES -> when (session.signDeclinedAt) {
            null -> stringResource(Res.string.you_agreed)
            else -> stringResource(Res.string.you_declined)
        }

        FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES -> when {
            session.isSigner() -> stringResource(Res.string.you_are_signing)
            else -> stringResource(Res.string.enough_members_took_part_without_you)
        }

        FrostSigningStage.COMPLETE -> stringResource(Res.string.signed_by_the_group)

        // The reason is the whole content of a failure -- "declined on this
        // device" and "the group could not agree" are different things to
        // have happened -- so it stands in for the word "abandoned" whenever
        // there is one.
        FrostSigningStage.FAILED -> session.failureReason
            ?: stringResource(Res.string.abandoned_nothing_was_signed)
    }
}

/**
 * How much of what the proposal is waiting for has arrived.
 *
 * A line of its own rather than a clause of the status line, because it is the
 * only thing on the row that moves while the reader is looking at it -- a
 * member's nonce landing changes this and nothing else -- and because a reader
 * scanning a list for the one that is nearly there should not have to read a
 * sentence to find out.
 *
 * Drawn only while something is being gathered: see
 * `ProposalListUIState.Proposal.progress`, which is null for both endings.
 */
@Composable
private fun ProgressLine(
    progress: ProposalListUIState.Progress,
    color: Color,
) {
    Text(
        text = when (progress.of) {
            ProposalListUIState.Progress.Of.MEMBERS -> stringResource(
                Res.string.of_members_have_offered_to_sign,
                progress.have,
                progress.need
            )

            ProposalListUIState.Progress.Of.SIGNATURES -> stringResource(
                Res.string.of_signatures_are_in,
                progress.have,
                progress.need
            )
        },
        color = color
    )
}

@ConformancePreviews
@Composable
private fun ProposalListScreenPreview() {
    val session = FrostSigningSession(
        id = "sessionId",
        chatRoomId = "chatRoomId",
        coordinatorPublicKey = "c".repeat(64),
        userPublicKey = "u".repeat(64),
        dkgSessionId = "dkgSessionId",
        threshold = 2,
        participantCount = 3,
        signerId = 1
    )

    BennuTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ProposalListScreen(
                activeUserPublicKey = "u".repeat(64),
                chatRoomId = "chatRoomId",
                initialProposalListUIState = ProposalListUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "chatRoomId",
                            userPublicKey = "u".repeat(64),
                            subject = "#Ekklesia",
                            description = null,
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = null
                        )
                    ),
                    proposals = listOf(
                        ProposalListUIState.Proposal(
                            session = session,
                            proposerName = "Ada",
                            signs = listOf(
                                ProposedEvent.Summary("New chapter", "Genesis 1 · 797 words · 31 chunks")
                            ),
                            awaitsYou = true
                        ),
                        ProposalListUIState.Proposal(
                            session = session.copy(
                                id = "openSessionId",
                                signApprovedAt = Clock.System.now()
                            ),
                            proposerName = "Grace",
                            signs = listOf(
                                ProposedEvent.Summary("New chapter", "Genesis 2 · 641 words · 24 chunks")
                            )
                        ),
                        ProposalListUIState.Proposal(
                            session = session.copy(
                                id = "olderSessionId",
                                stage = FrostSigningStage.COMPLETE
                            ),
                            proposerName = "You",
                            signs = listOf(
                                ProposedEvent.Summary("New translation", "isiZulu · public · CC BY-SA")
                            )
                        ),
                        // One of each state, so the conformance sweep renders every
                        // icon, every status line and -- from phase 3 -- every chip
                        // with something behind it, in all six schemes.
                        ProposalListUIState.Proposal(
                            session = session.copy(
                                id = "abandonedSessionId",
                                stage = FrostSigningStage.FAILED,
                                failureReason = "The group could not agree in time."
                            ),
                            proposerName = "Ada",
                            signs = listOf(
                                ProposedEvent.Summary("New chapter", "Genesis 3 · 512 words · 19 chunks")
                            )
                        )
                    )
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateBack = {},
                onNavigateToRoute = {}
            )
        }
    }
}
