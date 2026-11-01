package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.text.ProposedSpend
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.database.model.intermdiate.FrostSignerTally
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.extensions.shortened
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.ui.view.state.ProposalFilter
import com.machankura.dedun.compose.ui.view.state.ProposalListUIState

/**
 * Everything a group has asked its shared key to sign.
 *
 * A room signs repeatedly, and can have two sessions open at once -- proposing a
 * chapter and the translation that depends on it is two. Watching the whole list
 * rather than the latest session is the point: which of them is waiting on the
 * reader is a fact about each one, and there is no ordering of them in which the
 * second is not worth showing.
 */
class ProposalListViewModel(
    val chatRoomId: String,
    initialProposalListUIState: ProposalListUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
    initialFilter: ProposalFilter = ProposalFilter.ALL,
): ViewModel() {

    var proposalListUIState: ProposalListUIState by mutableStateOf(initialProposalListUIState)
        private set

    /**
     * Which proposals the reader asked for, kept here rather than only on the
     * state it is drawn from.
     *
     * This is the load-bearing part. [initiate] rebuilds a whole
     * [ProposalListUIState.Loaded] on every emission, and the flow emits
     * whenever any member's message advances any session in the room -- which is
     * precisely when somebody is sitting on this screen watching. A `Loaded`
     * built with the field's default would snap the chips back to
     * [ProposalFilter.ALL] mid-scroll, and would do it most often in the busiest
     * rooms. So the selection outlives the state it appears on, and every
     * emission reads it.
     */
    private var filter: ProposalFilter = initialFilter

    /**
     * The sessions whose "ask again" is in flight, so a second tap on one row
     * does not publish twice while the first is still going out.
     */
    private val pending = mutableStateSetOf<String>()

    private val logger = Logger.withTag(TAG)

    init {
        // A `Loaded` handed straight in -- a preview, a screen test -- carries a
        // filter of its own, and [initialFilter] is the caller's. The caller's
        // wins, so no screen can open with its chips saying one thing and its
        // rows showing another.
        select(initialFilter)
    }

    /**
     * Loads the room, then watches its proposals for as long as the screen lives.
     *
     * Every one of them moves on messages arriving from other members, so a
     * screen that read the list once would go stale the moment somebody else
     * signed -- which is exactly when a member is likely to be looking at it.
     */
    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                proposalListUIState = ProposalListUIState.Error("Couldn't find the group")
                return@launch
            }

            // Two flows rather than one, combined here. The rows and the counts
            // move on different things -- a row changes when a session advances,
            // a count every time any member's message lands -- and the counts
            // are a `GROUP BY` that would carry every nonce payload the room has
            // ever exchanged if it travelled as the messages themselves.
            combine(
                frostSigningRepository.observeSessionsForChatRoom(chatRoomId),
                frostSigningRepository.observeSignerTalliesForChatRoom(chatRoomId),
            ) { sessions, tallies ->
                sessions to tallies.groupBy { it.sessionId }
            }.collect { (sessions, talliesBySession) ->
                logger.d("${sessions.size} proposals in $chatRoomId")

                proposalListUIState = ProposalListUIState.Loaded(
                    localChatRoom = localChatRoom,
                    proposals = sessions.map {
                        asProposal(it, talliesBySession[it.session.id].orEmpty())
                    },
                    filter = filter
                )
            }
        }
    }

    /**
     * Shows only the proposals in one state, or all of them again.
     *
     * Applied to the state in hand as well as remembered for the next emission,
     * so the chips answer the tap rather than waiting for a session somewhere in
     * the room to move. A tap arriving while the screen is still loading is not
     * lost either -- it is remembered, and [initiate]'s first emission is
     * already filtered, which is what a deep link into a filter relies on.
     */
    fun select(filter: ProposalFilter) {
        this.filter = filter

        val current = proposalListUIState
        if (current is ProposalListUIState.Loaded) {
            proposalListUIState = current.copy(filter = filter)
        }
    }

    /**
     * One session as a row.
     *
     * Whether it is waiting on the reader is asked of the manager rather than
     * worked out here, so the list and the signing screen cannot disagree about
     * which proposals still have a decision in them.
     */
    private suspend fun asProposal(
        local: LocalFrostSigningSession,
        tallies: List<FrostSignerTally>,
    ): ProposalListUIState.Proposal {
        val items = local.items
        val events = items.mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }

        // A session nobody has answered has no row in the tally at all, which is
        // a count of zero rather than a missing one.
        fun counted(kind: Kind) = tallies.firstOrNull { it.kind == kind }?.members ?: 0

        return ProposalListUIState.Proposal(
            session = local.session,
            proposerName = when {
                local.session.isCoordinator() -> "You"
                else -> local.proposer?.humanReadableNameOrPubkey()
                    ?: local.session.coordinatorPublicKey.shortened()
            },
            signs = if (local.session.subject == FrostSigningSubject.TRANSACTION) {
                listOf(
                    frostSigningRepository.checkedSpend(local.session.id)
                        ?.let { ProposedSpend.summarize(it, it.account.chain) }
                        ?: ProposedSpend.UNREADABLE
                )
            } else if (local.session.subject == FrostSigningSubject.SWEEP) {
                listOf(frostSigningRepository.checkedSweep(local.session.id)?.let(ProposedSpend::summarize) ?: ProposedSpend.UNREADABLE_SWEEP)
            } else {
                events.map { ProposedEvent.summarize(it) }
            },
            // A spend's inputs are not events, and none of them is unreadable on
            // its own: the spend is checked whole, and says so in its summary.
            unreadable = if (local.session.subject != FrostSigningSubject.EVENTS) 0 else items.size - events.size,
            awaitsYou = FrostSigningManager.isAwaitingApproval(local.session, items),
            noncesOffered = counted(FrostSigningEvents.NONCE),
            partialSignatures = counted(FrostSigningEvents.PARTIAL_SIGNATURE),
            owing = owingFor(local)
        )
    }

    /**
     * How many members a session is still waiting on, when asking them again is
     * this device's to offer.
     *
     * A query per row, and the only one on this screen -- so it is asked for as
     * few rows as possible. Not ours to send, nothing to send, or already ended:
     * all three answer null without touching the database.
     *
     * An ended session is left out although `FrostSigningManager.resend` would
     * take it: a completed one can be re-sent to deliver the signature to a
     * member who never applied it, and that is worth a button on the one
     * proposal's screen rather than on every row of a list that only grows. The
     * signing screen still offers it.
     */
    private suspend fun owingFor(local: LocalFrostSigningSession): Int? {
        val session = local.session

        if (!session.isCoordinator() || local.items.isEmpty()) return null

        return when (session.stage) {
            FrostSigningStage.COLLECTING_NONCES,
            FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES ->
                frostSigningRepository.membersOwing(session).size

            FrostSigningStage.COMPLETE, FrostSigningStage.FAILED -> null
        }
    }

    /**
     * Asks the members a proposal is still waiting on to take another look.
     *
     * Guarded per session rather than by one flag for the screen, because the
     * rooms this screen is for have several proposals open at once and a
     * coordinator working down the list should not be locked out of the second
     * by the first still publishing.
     *
     * [onResult] is told whether anything went out. `resend` answers false for
     * a cooldown that has not run out, a session waiting on nobody and a stage
     * that cannot be resent, and the row that offered the button has no way to
     * know which -- so what it says afterwards comes from here.
     */
    fun askAgain(sessionId: String, onResult: (sent: Boolean) -> Unit) {
        val state = proposalListUIState as? ProposalListUIState.Loaded ?: return

        if (!pending.add(sessionId)) return

        viewModelScope.launch(Dispatchers.IO) {
            val sent = frostSigningRepository.resend(
                localChatRoom = state.localChatRoom,
                sessionId = sessionId
            )

            pending.remove(sessionId)

            viewModelScope.launch(Dispatchers.Main) { onResult.invoke(sent) }
        }
    }

    /**
     * Asks again about every visible proposal that can be, one after another.
     *
     * The list of sessions comes from the state rather than from the caller, so
     * what goes out is what the filter had in front of the reader when they
     * tapped -- a bulk control that acted on rows somebody could not see would
     * be the one thing it must never do.
     *
     * Each session is marked pending for the whole run, not just for its own
     * turn. The run is paced -- see `FrostSigningManager.RESEND_SPACING` -- so a
     * row whose turn has not come yet is still a row that is about to publish,
     * and a button offering to send it separately in the meantime would send it
     * twice.
     *
     * [onResult] is told how many went out and how many were tried. They differ
     * when a session stopped being askable between the tap and its turn, which a
     * paced run leaves room for: somebody else's signature can land in that
     * second and settle it.
     */
    fun askAllAgain(onResult: (sent: Int, of: Int) -> Unit) {
        val state = proposalListUIState as? ProposalListUIState.Loaded ?: return

        val sessionIds = state.askableVisible
            .map { it.session.id }
            .filterNot { it in pending }

        if (sessionIds.isEmpty()) return

        pending.addAll(sessionIds)

        viewModelScope.launch(Dispatchers.IO) {
            val sent = frostSigningRepository.resendAll(
                localChatRoom = state.localChatRoom,
                sessionIds = sessionIds
            )

            pending.removeAll(sessionIds.toSet())

            viewModelScope.launch(Dispatchers.Main) {
                onResult.invoke(sent, sessionIds.size)
            }
        }
    }

    /** Whether an ask for this session is still in flight. */
    fun isAsking(sessionId: String): Boolean = sessionId in pending

    companion object {
        private const val TAG = "ProposalListViewModel"

        fun factory(
            chatRoomId: String,
            initialProposalListUIState: ProposalListUIState = ProposalListUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
            initialFilter: ProposalFilter = ProposalFilter.ALL,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProposalListViewModel(
                    chatRoomId = chatRoomId,
                    initialProposalListUIState = initialProposalListUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository,
                    initialFilter = initialFilter
                )
            }
        }
    }
}
