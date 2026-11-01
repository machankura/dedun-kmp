package com.machankura.dedun.compose.ui.view.model

import androidx.lifecycle.viewModelScope
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.FrostSignerTally
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.view.state.ProposalFilter
import com.machankura.dedun.compose.ui.view.state.ProposalListUIState
import com.machankura.dedun.compose.ui.view.state.ProposalState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * What the proposal list's view model decides that nothing else can check.
 *
 * One thing above all, and it is the reason this file exists: the selected
 * filter has to outlive the state it is drawn on. `observeSessionsForChatRoom`
 * re-emits whenever any member's message advances any session in the room, and
 * the view model builds a whole new `Loaded` each time. A `Loaded` built with
 * the field's default would snap the chips back to "all" -- and would do it
 * exactly when somebody else signs, which is exactly when a member is sitting
 * on this screen watching. The bug is invisible in a static list and certain in
 * a busy room, so it is pinned here rather than left to be noticed.
 *
 * The rest is the ordinary shape of the screen: the room is looked up once, a
 * missing room is an error rather than an empty list, and a tap that lands
 * before the first emission is remembered rather than lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProposalListViewModelJvmTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)

    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = bob,
            subject = "Translation room",
            description = null,
            initialGiftWrapPayloadId = "sdfaer",
            mlsGroupState = "state",
        )
    )

    private fun chatRepository(room: LocalChatRoom? = localChatRoom) =
        object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? =
                room.takeIf { id == chatRoomId }
        }

    /** Who each session is still waiting on, and which sessions were resent. */
    private val owed = mutableMapOf<String, Set<HexKey>>()
    private val resent = mutableListOf<String>()

    /** Held open so a test can watch a resend that has not finished yet. */
    private var resendGate: CompletableDeferred<Boolean>? = null

    private fun frostRepository(
        sessions: Flow<List<LocalFrostSigningSession>>,
        tallies: Flow<List<FrostSignerTally>> = flowOf(emptyList()),
    ) = object : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        override fun observeSessionsForChatRoom(
            chatRoomId: String
        ): Flow<List<LocalFrostSigningSession>> = sessions

        override fun observeSignerTalliesForChatRoom(
            chatRoomId: String
        ): Flow<List<FrostSignerTally>> = tallies

        override suspend fun membersOwing(session: FrostSigningSession): Set<HexKey> =
            owed[session.id].orEmpty()

        override suspend fun resend(localChatRoom: LocalChatRoom, sessionId: String): Boolean {
            val gate = resendGate
            resent += sessionId
            return gate?.await() ?: true
        }

        /**
         * Unpaced on purpose. The real run spaces the sessions a second apart,
         * and that is `FrostSigningResendJvmTest`'s to say against the manager
         * that does it -- pacing a fake here would assert the fake and spend
         * seconds of the suite's wall time doing it. What this file asks is
         * which sessions the view model hands over, and what it does to the rows
         * while they are in flight.
         */
        override suspend fun resendAll(
            localChatRoom: LocalChatRoom,
            sessionIds: List<String>
        ): Int = sessionIds.count { resend(localChatRoom, it) }
    }

    private fun nonces(sessionId: String, members: Int) =
        FrostSignerTally(sessionId, FrostSigningEvents.NONCE, members)

    private fun signatures(sessionId: String, members: Int) =
        FrostSignerTally(sessionId, FrostSigningEvents.PARTIAL_SIGNATURE, members)

    private fun session(
        id: String,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        approved: Boolean = false,
        signerIds: String? = null,
        coordinator: String = ada,
    ) = FrostSigningSession(
        id = id,
        chatRoomId = chatRoomId,
        coordinatorPublicKey = coordinator,
        userPublicKey = bob,
        dkgSessionId = "ceremony",
        threshold = 2,
        participantCount = 3,
        signerId = 2,
        stage = stage,
        signerIds = signerIds,
        signApprovedAt = Clock.System.now().takeIf { approved },
    )

    /** One event of a session's batch, readable so the row can name it. */
    private fun item(sessionId: String, itemIndex: Int) = FrostSigningItem(
        sessionId = sessionId,
        itemIndex = itemIndex,
        unsignedEventJson = """{"id":"${"e".repeat(64)}","pubkey":"$chatRoomId",""" +
            """"created_at":1700000000,"kind":1,"tags":[],"content":"a chapter","sig":""}""",
        eventId = "e".repeat(64),
        nonceRandom = "00".repeat(32),
    )

    /** One session as the repository hands it over, with [items] events in it. */
    private fun local(
        id: String,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        approved: Boolean = false,
        signerIds: String? = null,
        coordinator: String = ada,
        items: Int = 1,
    ) = LocalFrostSigningSession(
        session = session(id, stage, approved, signerIds, coordinator),
        unorderedItems = (0 until items).map { index -> item(id, index) },
    )

    /** A room with one of each state in it. */
    private val mixed = listOf(
        local("needs"),
        local("open", approved = true),
        local(
            "signing",
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            approved = true,
            signerIds = "1,2",
        ),
        local("signed", stage = FrostSigningStage.COMPLETE),
        local("gone", stage = FrostSigningStage.FAILED),
    )

    private fun viewModel(
        sessions: Flow<List<LocalFrostSigningSession>>,
        room: LocalChatRoom? = localChatRoom,
        initialFilter: ProposalFilter = ProposalFilter.ALL,
        tallies: Flow<List<FrostSignerTally>> = flowOf(emptyList()),
    ) = ProposalListViewModel(
        chatRoomId = chatRoomId,
        initialProposalListUIState = ProposalListUIState.Loading,
        chatRepository = chatRepository(room),
        frostSigningRepository = frostRepository(sessions, tallies),
        initialFilter = initialFilter,
    )

    /**
     * Waits for the state to satisfy [predicate], or says what it was waiting for.
     *
     * `initiate` collects on `Dispatchers.IO`, so the wait is a real one rather
     * than a virtual-time one -- the same shape the other view-model tests use.
     */
    private suspend fun ProposalListViewModel.await(
        what: String,
        predicate: (ProposalListUIState) -> Boolean,
    ) {
        try {
            withTimeout(5_000) {
                while (!predicate(proposalListUIState)) delay(10)
            }
        } catch (_: TimeoutCancellationException) {
            fail("timed out waiting for $what; the state was $proposalListUIState")
        }
    }

    private suspend fun ProposalListViewModel.awaitLoaded(): ProposalListUIState.Loaded {
        await("the room to load") { it is ProposalListUIState.Loaded }
        return loaded()
    }

    @Test
    fun `the room's proposals arrive classified, unfiltered by default`() = runBlocking {
        val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        flow.emit(mixed)

        val model = viewModel(flow)
        model.initiate()

        val loaded = model.awaitLoaded()

        assertEquals(ProposalFilter.ALL, loaded.filter)
        assertEquals(5, loaded.visible.size)
        assertEquals(
            listOf(
                ProposalState.NEEDS_YOU,
                ProposalState.WAITING_ON_MEMBERS,
                ProposalState.BEING_SIGNED,
                ProposalState.SIGNED,
                ProposalState.ABANDONED,
            ),
            loaded.proposals.map { it.state }
        )
        assertEquals(1, loaded.counts[ProposalState.NEEDS_YOU])

        model.viewModelScope.cancel()
    }

    @Test
    fun `selecting a filter narrows the rows at once, without waiting for the room to move`() =
        runBlocking {
            val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
            flow.emit(mixed)

            val model = viewModel(flow)
            model.initiate()
            model.awaitLoaded()

            model.select(ProposalFilter.SIGNED)

            val loaded = assertIs<ProposalListUIState.Loaded>(model.proposalListUIState)
            assertEquals(ProposalFilter.SIGNED, loaded.filter)
            assertEquals(listOf("signed"), loaded.visible.map { it.session.id })
            assertEquals(5, loaded.proposals.size, "the room is still whole behind the filter")

            model.viewModelScope.cancel()
        }

    /**
     * The one this file is for.
     *
     * A second emission is what happens when somebody else in the room signs,
     * declines or proposes. It must not take the reader's filter with it.
     */
    @Test
    fun `a session advancing elsewhere in the room does not reset the filter`() = runBlocking {
        val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        flow.emit(mixed)

        val model = viewModel(flow)
        model.initiate()
        model.awaitLoaded()

        model.select(ProposalFilter.NEEDS_YOU)
        assertEquals(listOf("needs"), model.loaded().visible.map { it.session.id })

        // The coordinator fixes the signer set on the open one, and a fresh
        // proposal arrives. The first is a move between two chips, which is the
        // emission most likely to arrive while somebody is reading.
        flow.emit(
            mixed.map {
                if (it.session.id == "open") {
                    local(
                        "open",
                        FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
                        approved = true,
                        signerIds = "1,2",
                    )
                } else {
                    it
                }
            } + local("fresh")
        )

        model.await("the new proposal") {
            it is ProposalListUIState.Loaded && it.proposals.size == 6
        }

        val after = model.loaded()
        assertEquals(ProposalFilter.NEEDS_YOU, after.filter, "the filter was reset by an emission")
        assertEquals(
            listOf("needs", "fresh"),
            after.visible.map { it.session.id },
            "the filter was kept but not applied to the new rows"
        )
        assertEquals(
            0,
            after.counts[ProposalState.WAITING_ON_MEMBERS],
            "the counts did not follow the room"
        )
        assertEquals(2, after.counts[ProposalState.BEING_SIGNED])

        model.viewModelScope.cancel()
    }

    /**
     * Working through the queue empties the filter under the reader, and that is
     * the intended end of it: the rows go as they are answered and what is left
     * is the empty state saying nothing is waiting. The counts behind it still
     * show where everything went.
     */
    @Test
    fun `answering the last proposal in a filter empties it rather than widening it`() =
        runBlocking {
            val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
            flow.emit(listOf(local("needs")))

            val model = viewModel(flow)
            model.initiate()
            model.awaitLoaded()

            model.select(ProposalFilter.NEEDS_YOU)
            assertEquals(1, model.loaded().visible.size)

            flow.emit(listOf(local("needs", approved = true)))

            model.await("the answer to land") {
                it is ProposalListUIState.Loaded &&
                    it.counts[ProposalState.NEEDS_YOU] == 0
            }

            val after = model.loaded()
            assertEquals(ProposalFilter.NEEDS_YOU, after.filter)
            assertTrue(after.visible.isEmpty())
            assertEquals(1, after.proposals.size)
            assertEquals(1, after.counts[ProposalState.WAITING_ON_MEMBERS])

            model.viewModelScope.cancel()
        }

    /**
     * A filter chosen before the screen has loaded, which is what a deep link
     * into the queue is: the first emission is already filtered, so the reader
     * never sees the unfiltered list flash past.
     */
    @Test
    fun `an initial filter applies to the very first emission`() = runBlocking {
        val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        flow.emit(mixed)

        val model = viewModel(flow, initialFilter = ProposalFilter.NEEDS_YOU)
        model.initiate()

        val loaded = model.awaitLoaded()

        assertEquals(ProposalFilter.NEEDS_YOU, loaded.filter)
        assertEquals(listOf("needs"), loaded.visible.map { it.session.id })

        model.viewModelScope.cancel()
    }

    /** A tap that lands while the room is still being looked up is remembered. */
    @Test
    fun `selecting before the first emission is not lost`() = runBlocking {
        val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)

        val model = viewModel(flow)
        model.initiate()

        model.select(ProposalFilter.ABANDONED)
        flow.emit(mixed)

        val loaded = model.awaitLoaded()

        assertEquals(ProposalFilter.ABANDONED, loaded.filter)
        assertEquals(listOf("gone"), loaded.visible.map { it.session.id })

        model.viewModelScope.cancel()
    }

    @Test
    fun `a room that cannot be found is an error, not an empty list`() = runBlocking {
        val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        flow.emit(mixed)

        val model = viewModel(flow, room = null)
        model.initiate()

        model.await("the error") { it is ProposalListUIState.Error }
        assertIs<ProposalListUIState.Error>(model.proposalListUIState)

        model.viewModelScope.cancel()
    }

    // --- the progress counts ------------------------------------------------------

    /**
     * The counts arrive on a flow of their own and have to land on the right
     * rows. A tally is keyed by session, and the rooms this screen is for have
     * several open at once -- putting one session's nonces on another's row
     * would be the quietest possible way to be wrong.
     */
    @Test
    fun `each session gets its own counts, by session and by kind`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        val tallies = MutableSharedFlow<List<FrostSignerTally>>(replay = 1)
        sessions.emit(mixed)
        tallies.emit(
            listOf(
                nonces("open", 1),
                signatures("signing", 2),
                // A kind the other session has too, to catch a row that took the
                // first tally it found rather than its own.
                nonces("signing", 3),
            )
        )

        val model = viewModel(sessions, tallies = tallies)
        model.initiate()

        val loaded = model.awaitLoaded()
        val byId = loaded.proposals.associateBy { it.session.id }

        assertEquals(1, byId.getValue("open").noncesOffered)
        assertEquals(0, byId.getValue("open").partialSignatures)
        assertEquals(3, byId.getValue("signing").noncesOffered)
        assertEquals(2, byId.getValue("signing").partialSignatures)

        // And the figure each row actually shows is its stage's.
        assertEquals(
            ProposalListUIState.Progress.Of.MEMBERS,
            byId.getValue("open").progress?.of
        )
        assertEquals(2, byId.getValue("signing").progress?.have)
        assertEquals(
            ProposalListUIState.Progress.Of.SIGNATURES,
            byId.getValue("signing").progress?.of
        )

        model.viewModelScope.cancel()
    }

    @Test
    fun `a session nobody has answered counts zero rather than nothing`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        sessions.emit(listOf(local("open", approved = true)))

        val model = viewModel(sessions)
        model.initiate()

        val proposal = model.awaitLoaded().proposals.single()

        assertEquals(0, proposal.noncesOffered)
        assertEquals(0, proposal.progress?.have)

        model.viewModelScope.cancel()
    }

    /**
     * The counts move on their own flow, and far more often than the rows do --
     * every member's nonce moves one. A count arriving must reach the screen
     * without the session rows changing at all.
     */
    @Test
    fun `a nonce landing updates the row without the session rows moving`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        val tallies = MutableSharedFlow<List<FrostSignerTally>>(replay = 1)
        sessions.emit(listOf(local("open", approved = true)))
        tallies.emit(listOf(nonces("open", 1)))

        val model = viewModel(sessions, tallies = tallies)
        model.initiate()

        assertEquals(1, model.awaitLoaded().proposals.single().progress?.have)

        tallies.emit(listOf(nonces("open", 2)))

        model.await("the second nonce") {
            it is ProposalListUIState.Loaded &&
                it.proposals.single().noncesOffered == 2
        }

        model.viewModelScope.cancel()
    }

    /** A selection survives a count arriving, the same as it survives a row moving. */
    @Test
    fun `a count arriving does not reset the filter either`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        val tallies = MutableSharedFlow<List<FrostSignerTally>>(replay = 1)
        sessions.emit(mixed)
        tallies.emit(emptyList())

        val model = viewModel(sessions, tallies = tallies)
        model.initiate()
        model.awaitLoaded()

        model.select(ProposalFilter.WAITING_ON_MEMBERS)
        tallies.emit(listOf(nonces("open", 1)))

        model.await("the count") {
            it is ProposalListUIState.Loaded &&
                it.proposals.any { proposal -> proposal.noncesOffered == 1 }
        }

        assertEquals(ProposalFilter.WAITING_ON_MEMBERS, model.loaded().filter)
        assertEquals(listOf("open"), model.loaded().visible.map { it.session.id })

        model.viewModelScope.cancel()
    }

    // --- asking the quiet members again ---------------------------------------------

    /**
     * The one the signing screen gates its button on, reached from the row.
     *
     * Not ours to send and nobody left to send to are different facts, and only
     * the second could ever become a button later. The row keeps them apart as
     * null against zero so no arithmetic can turn one into the other.
     */
    @Test
    fun `only a session this device proposed is ours to ask again for`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        owed["mine"] = setOf(bob)
        owed["theirs"] = setOf(bob)
        sessions.emit(
            listOf(
                local("mine", approved = true, coordinator = bob),
                local("theirs", approved = true, coordinator = ada),
            )
        )

        val model = viewModel(sessions)
        model.initiate()

        val byId = model.awaitLoaded().proposals.associateBy { it.session.id }

        // `local` builds the row as bob's device sees it -- userPublicKey is bob
        // -- so the one bob coordinates is the one with an offer on it.
        assertEquals(1, byId.getValue("mine").owing)
        assertTrue(byId.getValue("mine").canAskAgain)

        assertNull(byId.getValue("theirs").owing, "a session we do not coordinate was costed")
        assertFalse(byId.getValue("theirs").canAskAgain)

        model.viewModelScope.cancel()
    }

    @Test
    fun `a session waiting on nobody is ours but has nobody to ask`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        owed["mine"] = emptySet()
        sessions.emit(listOf(local("mine", approved = true, coordinator = bob)))

        val model = viewModel(sessions)
        model.initiate()

        val proposal = model.awaitLoaded().proposals.single()

        assertEquals(0, proposal.owing, "ours to send is not the same as nothing to send")
        assertFalse(proposal.canAskAgain)

        model.viewModelScope.cancel()
    }

    /**
     * An ended session is left out although the manager would resend it: a
     * completed one can be re-sent to deliver the signature to a member who
     * never applied it, which is worth a button on the one proposal's screen
     * rather than a query per row on a list that only grows.
     */
    @Test
    fun `an ended session is not costed, so a growing history stays one query`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        owed["done"] = setOf(bob)
        owed["gone"] = setOf(bob)
        sessions.emit(
            listOf(
                local("done", stage = FrostSigningStage.COMPLETE, coordinator = bob),
                local("gone", stage = FrostSigningStage.FAILED, coordinator = bob),
            )
        )

        val model = viewModel(sessions)
        model.initiate()

        assertTrue(model.awaitLoaded().proposals.all { it.owing == null })

        model.viewModelScope.cancel()
    }

    /** Nothing to resend: a session whose proposal has not landed has no events to send. */
    @Test
    fun `a session holding no events is not offered an ask`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        owed["empty"] = setOf(bob)
        sessions.emit(listOf(local("empty", coordinator = bob, items = 0)))

        val model = viewModel(sessions)
        model.initiate()

        assertNull(model.awaitLoaded().proposals.single().owing)

        model.viewModelScope.cancel()
    }

    @Test
    fun `asking again resends that session and says whether anything went out`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        owed["mine"] = setOf(bob)
        sessions.emit(listOf(local("mine", approved = true, coordinator = bob)))

        val model = viewModel(sessions)
        model.initiate()
        model.awaitLoaded()

        var sent: Boolean? = null
        model.askAgain("mine") { sent = it }

        withTimeout(5_000) { while (sent == null) delay(10) }

        assertEquals(listOf("mine"), resent)
        assertEquals(true, sent)

        model.viewModelScope.cancel()
    }

    /**
     * Guarded per session rather than by one flag for the screen. These rooms
     * have several proposals open at once, and a coordinator working down the
     * list should not be locked out of the second by the first still publishing.
     */
    @Test
    fun `a second tap on the same row publishes nothing, while another row still may`() =
        runBlocking {
            val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
            owed["one"] = setOf(bob)
            owed["two"] = setOf(bob)
            sessions.emit(
                listOf(
                    local("one", approved = true, coordinator = bob),
                    local("two", approved = true, coordinator = bob),
                )
            )

            val model = viewModel(sessions)
            model.initiate()
            model.awaitLoaded()

            val gate = CompletableDeferred<Boolean>()
            resendGate = gate

            model.askAgain("one") {}
            withTimeout(5_000) { while (!model.isAsking("one")) delay(10) }

            // The same row again, while the first is still going out.
            model.askAgain("one") {}
            delay(50)
            assertEquals(listOf("one"), resent, "a second tap published again")

            // A different row is not locked out by it.
            model.askAgain("two") {}
            withTimeout(5_000) { while (resent.size < 2) delay(10) }
            assertEquals(listOf("one", "two"), resent)

            gate.complete(true)
            withTimeout(5_000) { while (model.isAsking("one")) delay(10) }

            // And once it has finished, the row may be asked again.
            resendGate = null
            model.askAgain("one") {}
            withTimeout(5_000) { while (resent.size < 3) delay(10) }
            assertEquals(listOf("one", "two", "one"), resent)

            model.viewModelScope.cancel()
        }

    @Test
    fun `a resend that published nothing is reported as such`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        owed["mine"] = setOf(bob)
        sessions.emit(listOf(local("mine", approved = true, coordinator = bob)))

        val model = viewModel(sessions)
        model.initiate()
        model.awaitLoaded()

        resendGate = CompletableDeferred<Boolean>().also { it.complete(false) }

        var sent: Boolean? = null
        model.askAgain("mine") { sent = it }
        withTimeout(5_000) { while (sent == null) delay(10) }

        assertEquals(false, sent)

        model.viewModelScope.cancel()
    }

    // --- asking about all of them at once -------------------------------------------

    /** What the bulk offer covers, as the filter chose it. */
    private suspend fun askableModel(): ProposalListViewModel {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        owed["one"] = setOf(ada)
        owed["two"] = setOf(ada)
        owed["three"] = setOf(ada)
        sessions.emit(
            listOf(
                local("one", approved = true, coordinator = bob),
                local("two", approved = true, coordinator = bob),
                local(
                    "three",
                    stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
                    approved = true,
                    signerIds = "1,2",
                    coordinator = bob,
                ),
            )
        )

        val model = viewModel(sessions)
        model.initiate()
        model.awaitLoaded()
        return model
    }

    @Test
    fun `asking about all of them sends one per session, in order`() = runBlocking {
        val model = askableModel()

        var result: Pair<Int, Int>? = null
        model.askAllAgain { sent, of -> result = sent to of }

        withTimeout(10_000) { while (result == null) delay(10) }

        assertEquals(listOf("one", "two", "three"), resent)
        assertEquals(3 to 3, result)

        model.viewModelScope.cancel()
    }

    /**
     * Every covered session is pending for the whole run, not just for its turn.
     * A row whose turn has not come is still about to publish, and a row button
     * offering to send it separately in the meantime would send it twice.
     */
    @Test
    fun `every covered row goes quiet for the whole run, not just for its turn`() = runBlocking {
        val model = askableModel()

        // Held open on the first session, which is what a paced run looks like
        // from the rows' point of view: one publishing, the rest about to.
        val gate = CompletableDeferred<Boolean>()
        resendGate = gate

        model.askAllAgain { _, _ -> }

        // Waited for the call, not for the pending mark: the mark is set before
        // the coroutine starts, so the two are not the same moment.
        withTimeout(5_000) { while (resent.isEmpty()) delay(10) }

        assertEquals(listOf("one"), resent, "the run did not wait for the first to finish")
        assertTrue(model.isAsking("two"), "a row waiting its turn was still offering to send")
        assertTrue(model.isAsking("three"))

        // And the row's own button cannot slip a second copy in alongside.
        model.askAgain("three") {}
        delay(50)
        assertEquals(0, resent.count { it == "three" }, "a row published out of turn")

        gate.complete(true)
        resendGate = null

        withTimeout(5_000) { while (model.isAsking("three")) delay(10) }
        assertEquals(listOf("one", "two", "three"), resent)

        model.viewModelScope.cancel()
    }

    @Test
    fun `a session already being asked separately is not asked again by the bulk run`() =
        runBlocking {
            val model = askableModel()

            val gate = CompletableDeferred<Boolean>()
            resendGate = gate

            model.askAgain("one") {}
            withTimeout(5_000) { while (resent.isEmpty()) delay(10) }

            var result: Pair<Int, Int>? = null
            model.askAllAgain { sent, of -> result = sent to of }

            gate.complete(true)
            withTimeout(5_000) { while (result == null) delay(10) }

            assertEquals(2 to 2, result, "the bulk run counted a session already in flight")
            assertEquals(
                listOf("one", "two", "three"),
                resent,
                "the session already in flight was published twice"
            )

            model.viewModelScope.cancel()
        }

    /** A run where something settled in between says so rather than claiming all. */
    @Test
    fun `a run that could not send them all reports the shortfall`() = runBlocking {
        val model = askableModel()

        resendGate = CompletableDeferred<Boolean>().also { it.complete(false) }

        var result: Pair<Int, Int>? = null
        model.askAllAgain { sent, of -> result = sent to of }

        withTimeout(10_000) { while (result == null) delay(10) }

        assertEquals(0 to 3, result)

        model.viewModelScope.cancel()
    }

    @Test
    fun `asking about all of them when nothing is askable publishes nothing`() = runBlocking {
        val sessions = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        owed["theirs"] = setOf(ada)
        sessions.emit(listOf(local("theirs", approved = true, coordinator = ada)))

        val model = viewModel(sessions)
        model.initiate()
        model.awaitLoaded()

        var called = false
        model.askAllAgain { _, _ -> called = true }
        delay(100)

        assertTrue(resent.isEmpty())
        assertFalse(called, "a run with nothing in it reported a result")

        model.viewModelScope.cancel()
    }

    private fun ProposalListViewModel.loaded(): ProposalListUIState.Loaded =
        assertIs<ProposalListUIState.Loaded>(proposalListUIState)
}
