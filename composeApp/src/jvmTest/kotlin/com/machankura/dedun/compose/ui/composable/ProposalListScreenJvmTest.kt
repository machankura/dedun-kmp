package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.view.state.ProposalFilter
import com.machankura.dedun.compose.ui.view.state.ProposalListUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The chip row over the proposal list: what it offers, what it counts, and what
 * tapping one does to the rows beneath it.
 *
 * **Every state is offered, with its own number.** A chip is present at zero
 * too, which is the decision worth testing rather than the obvious one: the
 * alternative is a row that gains and loses chips as sessions advance, and
 * sessions advance while this screen is open -- somebody else signing is exactly
 * when a member comes to look at it.
 *
 * **A filter flattens the screen.** Unfiltered, the two sections stay, because
 * what is waiting on the reader reads differently from what the group has done.
 * Filtered, the headings go: the reader has already said which they want by
 * tapping, and heading a single-state list with "waiting for you" would answer a
 * question nobody asked.
 *
 * **An empty filter says which emptiness it is.** One shared "nothing found" is
 * how five different absences here came to say one thing. A group never asked to
 * sign and a queue the reader has worked through are not the same fact, and the
 * second is not an absence at all.
 */
@OptIn(ExperimentalTestApi::class)
class ProposalListScreenJvmTest {
    private val session = FrostSigningSession(
        id = "needs",
        chatRoomId = chatRoomId,
        coordinatorPublicKey = "c".repeat(64),
        userPublicKey = activeUserPublicKey,
        dkgSessionId = "ceremony",
        threshold = 2,
        participantCount = 3,
        signerId = 2,
    )

    private fun proposal(
        id: String,
        label: String,
        detail: String,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        awaitsYou: Boolean = false,
        approved: Boolean = false,
        signerIds: String? = null,
        nonces: Int = 0,
        signatures: Int = 0,
        owing: Int? = null,
        resentAt: kotlin.time.Instant? = null,
    ) = ProposalListUIState.Proposal(
        session = session.copy(
            id = id,
            stage = stage,
            signerIds = signerIds,
            resentAt = resentAt,
            signApprovedAt = Clock.System.now().takeIf { approved },
        ),
        proposerName = "Ada",
        signs = listOf(ProposedEvent.Summary(label, detail)),
        awaitsYou = awaitsYou,
        noncesOffered = nonces,
        partialSignatures = signatures,
        owing = owing,
    )

    private val needsYou = proposal("needs", "New chapter", "Genesis 1", awaitsYou = true)
    private val alsoNeedsYou = proposal("also", "New chapter", "Genesis 2", awaitsYou = true)
    private val open = proposal("open", "New translation", "isiZulu", approved = true, nonces = 1)
    private val beingSigned = proposal(
        "signing", "New chapter", "Genesis 4",
        stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
        approved = true,
        signerIds = "1,2",
        signatures = 1,
    )
    private val signed = proposal("signed", "New post", "A note", stage = FrostSigningStage.COMPLETE)

    private val mixed = listOf(needsYou, alsoNeedsYou, open, beingSigned, signed)

    /**
     * Scrolls a chip into view and returns it.
     *
     * The row scrolls sideways by design -- five counted chips do not fit the
     * sixty characters `readableContent` holds the column to -- so reaching the
     * last one is what the reader does as well.
     */
    private fun ComposeUiTest.chip(label: String) =
        onNodeWithText(label).performScrollTo().assertIsDisplayed()

    @Test
    fun `every state is offered with its own count, zero included`() = render(loaded(mixed)) {
        // Two in the queue, one short of members, one being signed, one signed --
        // and nothing abandoned, which still gets a chip rather than vanishing.
        chip("All")
        chip("Needs you · 2")
        chip("Waiting on members · 1")
        chip("Being signed · 1")
        chip("Signed · 1")
        chip("Abandoned · 0")
    }

    /**
     * Order is asserted on the chips the viewport actually lays out.
     *
     * The row scrolls, and a chip entirely past the right edge reports zero
     * bounds rather than an off-screen position -- so the ones beyond the fold
     * cannot be measured here and are not what this test is for. That the six
     * run in lifecycle order is `ProposalFilterTest`'s to say, where it is a
     * property of the enum and no viewport is involved.
     */
    @Test
    fun `the chips are offered above the rows, in the order the states run`() =
        render(loaded(mixed)) {
            val laidOut = listOf("All", "Needs you · 2", "Waiting on members · 1")
                .map { onNodeWithText(it).assertIsDisplayed().getBoundsInRoot().left }

            assertTrue(
                laidOut.zipWithNext().all { (left, right) -> left < right },
                "the chips were drawn out of order: $laidOut"
            )

            val chipBottom = onNodeWithText("All").getBoundsInRoot().bottom
            val firstRow = onNodeWithText("Genesis 1").getBoundsInRoot().top
            assertTrue(chipBottom <= firstRow, "the chips were drawn over the rows")
        }

    @Test
    fun `unfiltered, the sections stay and every proposal is drawn`() = render(loaded(mixed)) {
        onNodeWithText("Waiting for you").assertIsDisplayed()
        onNodeWithText("Everything else").assertIsDisplayed()

        onNodeWithText("Genesis 1").assertIsDisplayed()
        onNodeWithText("Genesis 2").assertIsDisplayed()
        onNodeWithText("isiZulu").assertIsDisplayed()
        onNodeWithText("Genesis 4").assertIsDisplayed()
        onNodeWithText("A note").assertIsDisplayed()
    }

    /**
     * The two open stages are two chips, and they hold different proposals.
     *
     * Short of members, somebody may have to chase it -- refusals can still end
     * it, and the coordinator's resend is for exactly this. Once the signer set
     * is fixed there is nothing to do but wait. One chip over both would file
     * those together.
     */
    @Test
    fun `the two open stages are told apart`() = render(loaded(mixed)) {
        chip("Waiting on members · 1").performClick()
        onNodeWithText("isiZulu").assertIsDisplayed()
        onAllNodesWithText("Genesis 4").assertCountEquals(0)

        chip("Being signed · 1").performClick()
        onNodeWithText("Genesis 4").assertIsDisplayed()
        onAllNodesWithText("isiZulu").assertCountEquals(0)
    }

    @Test
    fun `tapping a chip narrows the rows to its state`() = render(loaded(mixed)) {
        chip("Signed · 1").performClick()

        onNodeWithText("A note").assertIsDisplayed()
        onAllNodesWithText("Genesis 1").assertCountEquals(0)
        onAllNodesWithText("Genesis 2").assertCountEquals(0)
        onAllNodesWithText("isiZulu").assertCountEquals(0)
        onAllNodesWithText("Genesis 4").assertCountEquals(0)
    }

    @Test
    fun `a filtered list drops the section headings`() = render(loaded(mixed)) {
        chip("Needs you · 2").performClick()

        onNodeWithText("Genesis 1").assertIsDisplayed()
        onNodeWithText("Genesis 2").assertIsDisplayed()
        onAllNodesWithText("Waiting for you").assertCountEquals(0)
        onAllNodesWithText("Everything else").assertCountEquals(0)
    }

    @Test
    fun `going back to all restores the sections and the rest of the rows`() =
        render(loaded(mixed)) {
            chip("Signed · 1").performClick()
            onAllNodesWithText("Genesis 1").assertCountEquals(0)

            chip("All").performClick()

            onNodeWithText("Waiting for you").assertIsDisplayed()
            onNodeWithText("Genesis 1").assertIsDisplayed()
            onNodeWithText("A note").assertIsDisplayed()
        }

    /** The counts are the room's, so narrowing the rows must not narrow them. */
    @Test
    fun `the counts stay put when the filter moves`() = render(loaded(mixed)) {
        chip("Signed · 1").performClick()

        chip("Needs you · 2")
        chip("Waiting on members · 1")
        chip("Being signed · 1")
        chip("Abandoned · 0")
    }

    @Test
    fun `a filter with nothing in it says which emptiness it is`() = render(loaded(mixed)) {
        chip("Abandoned · 0").performClick()

        onNodeWithText("No proposal has been abandoned.").assertIsDisplayed()
        // Not the whole-screen message, which would be a different and false claim.
        onAllNodesWithText("This group has not been asked to sign anything yet.")
            .assertCountEquals(0)
    }

    /**
     * The queue running dry is the end of the reader's work, not a blank screen,
     * and the chips behind it still say where everything went.
     */
    @Test
    fun `a worked-through queue reads as finished rather than as missing`() =
        render(loaded(listOf(open, signed))) {
            chip("Needs you · 0").performClick()

            onNodeWithText("Nothing is waiting for your signature.").assertIsDisplayed()
            chip("Waiting on members · 1")
            chip("Signed · 1")
        }

    @Test
    fun `each filter has its own words for being empty`() {
        render(loaded(listOf(needsYou))) {
            chip("Waiting on members · 0").performClick()
            onNodeWithText("No proposal is waiting on members.").assertIsDisplayed()
        }

        render(loaded(listOf(needsYou))) {
            chip("Being signed · 0").performClick()
            onNodeWithText("No proposal is being signed.").assertIsDisplayed()
        }

        render(loaded(listOf(needsYou))) {
            chip("Signed · 0").performClick()
            onNodeWithText("This group has not signed anything yet.").assertIsDisplayed()
        }
    }

    /**
     * A room with nothing in it has nothing to filter, and five chips reading 0
     * would be five ways of saying what the empty state says once.
     */
    @Test
    fun `an untouched room offers no chips at all, only the reason`() = render(loaded(emptyList())) {
        onNodeWithText("This group has not been asked to sign anything yet.").assertIsDisplayed()

        onAllNodesWithText("All").assertCountEquals(0)
        onAllNodesWithText("Needs you · 0").assertCountEquals(0)
        onAllNodesWithText("Abandoned · 0").assertCountEquals(0)
    }

    @Test
    fun `an initial filter is the one selected when the screen opens`() =
        render(loaded(mixed), initialFilter = ProposalFilter.NEEDS_YOU) {
            onNodeWithText("Genesis 1").assertIsDisplayed()
            onNodeWithText("Genesis 2").assertIsDisplayed()
            onAllNodesWithText("A note").assertCountEquals(0)
            onAllNodesWithText("Genesis 4").assertCountEquals(0)
            onAllNodesWithText("Waiting for you").assertCountEquals(0)
        }

    // --- the progress line --------------------------------------------------------

    /**
     * The fraction each row shows is its own stage's, in that stage's words.
     *
     * The session gathering nonces counts members against the threshold -- two
     * of the three in the room, because the threshold is what the coordinator
     * gates on. The one being signed counts signatures against the signer set.
     */
    @Test
    fun `a gathering proposal says how far it has got, in its stage's terms`() =
        render(loaded(mixed)) {
            onNodeWithText("1 of 2 members have offered to sign").assertIsDisplayed()
            onNodeWithText("1 of 2 signatures are in").assertIsDisplayed()
        }

    /**
     * The one the reader has to answer shows it too: whether they are the
     * deciding vote is the most useful thing the row can tell them, and it is
     * the row they are most likely to act on.
     */
    @Test
    fun `a proposal waiting on you shows its count as well`() =
        render(loaded(listOf(proposal("a", "New chapter", "Genesis 9", awaitsYou = true, nonces = 1)))) {
            onNodeWithText("Needs your review.").assertIsDisplayed()
            onNodeWithText("1 of 2 members have offered to sign").assertIsDisplayed()
        }

    /** Nothing left to gather, so nothing counted. */
    @Test
    fun `a signed proposal shows no count`() = render(loaded(listOf(signed))) {
        onNodeWithText("Signed by the group.").assertIsDisplayed()
        onAllNodesWithText("signatures are in", substring = true).assertCountEquals(0)
        onAllNodesWithText("have offered to sign", substring = true).assertCountEquals(0)
    }

    /**
     * An abandoned one shows none either: its counts are frozen where they
     * stopped, and a fraction beside a failure invites the reader to wait for a
     * part that is never coming. The reason is what that row has to say.
     */
    @Test
    fun `an abandoned proposal shows its reason and no count`() {
        val gone = proposal(
            "gone", "New chapter", "Genesis 8",
            stage = FrostSigningStage.FAILED,
            nonces = 1,
        ).let { it.copy(session = it.session.copy(failureReason = "The group could not agree.")) }

        render(loaded(listOf(gone))) {
            onNodeWithText("The group could not agree.").assertIsDisplayed()
            onAllNodesWithText("have offered to sign", substring = true).assertCountEquals(0)
        }
    }

    /**
     * The status line carries no numbers of its own any more. It used to say
     * "waiting for 2 of 3 members to take part", which is the rule rather than
     * the progress -- a row said it on the day a proposal was made and on the
     * day before it completed. Two fractions meaning different things on one row
     * would be worse than the one that never moved.
     */
    @Test
    fun `the status line says who you are in it, and leaves the counting alone`() =
        render(loaded(mixed)) {
            onNodeWithText("You agreed.").assertIsDisplayed()
            onNodeWithText("You are signing.").assertIsDisplayed()

            onAllNodesWithText("to take part", substring = true).assertCountEquals(0)
            onAllNodesWithText("Waiting on the rest", substring = true).assertCountEquals(0)
        }

    // --- asking the quiet members again ---------------------------------------------

    /**
     * The button a coordinator came to the list for.
     *
     * It says what the signing screen's says, deliberately: somebody who found
     * it there and then found this should not have to work out that two labels
     * are one button. What the list adds is that filtering to "being signed"
     * puts every stalled proposal in front of them at once, rather than one tap
     * into each.
     */
    @Test
    fun `a stalled proposal we coordinate offers the ask, on the being signed chip`() {
        val stalled = proposal(
            "signing", "New chapter", "Genesis 4",
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            approved = true, signerIds = "1,2", signatures = 1, owing = 1,
        )

        render(loaded(listOf(stalled, signed))) {
            chip("Being signed · 1").performClick()

            onNodeWithText("Genesis 4").assertIsDisplayed()
            onNodeWithText("Ask again").assertIsDisplayed().assertIsEnabled()
        }
    }

    @Test
    fun `tapping it asks for that proposal and no other`() {
        val stalled = proposal(
            "signing", "New chapter", "Genesis 4",
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            approved = true, signerIds = "1,2", owing = 1,
        )
        val other = proposal(
            "also", "New chapter", "Genesis 5",
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            approved = true, signerIds = "1,2", owing = 1,
        )

        render(loaded(listOf(stalled, other))) {
            onAllNodesWithText("Ask again")[0].performClick()
            waitUntil { resent.isNotEmpty() }
        }

        assertEquals(listOf("signing"), resent)
    }

    /** Opening the proposal and asking again are different taps on one row. */
    @Test
    fun `asking again does not open the proposal`() {
        var opened: Route? = null
        val stalled = proposal(
            "signing", "New chapter", "Genesis 4",
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            approved = true, signerIds = "1,2", owing = 1,
        )

        render(loaded(listOf(stalled)), onNavigateToRoute = { opened = it }) {
            onNodeWithText("Ask again").performClick()
            waitUntil { resent.isNotEmpty() }
        }

        assertEquals(listOf("signing"), resent)
        assertNull(opened, "the card behind the button took the tap too")
    }

    /**
     * The offer is the coordinator's and nobody else's: a resend from anybody
     * else would have a member who was missing the session believe the resender
     * coordinates, and then drop the real coordinator's signer set.
     */
    @Test
    fun `a proposal that is not ours to send offers no button`() {
        val theirs = proposal(
            "signing", "New chapter", "Genesis 4",
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            approved = true, signerIds = "1,2", owing = null,
        )

        render(loaded(listOf(theirs))) {
            onNodeWithText("Genesis 4").assertIsDisplayed()
            onAllNodesWithText("Ask again").assertCountEquals(0)
        }
    }

    @Test
    fun `a proposal waiting on nobody offers no button`() {
        val settled = proposal(
            "signing", "New chapter", "Genesis 4",
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            approved = true, signerIds = "1,2", owing = 0,
        )

        render(loaded(listOf(settled))) {
            onAllNodesWithText("Ask again").assertCountEquals(0)
        }
    }

    /**
     * Disabled rather than hidden while the cooldown runs. A control that
     * vanishes after a tap reads as one that failed, and what happened is that
     * it worked -- so the button stays and says why it is quiet.
     */
    @Test
    fun `an ask sent just now leaves the button in place, disabled, and says why`() {
        val justAsked = proposal(
            "signing", "New chapter", "Genesis 4",
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            approved = true, signerIds = "1,2", owing = 1,
            resentAt = Clock.System.now(),
        )

        render(loaded(listOf(justAsked))) {
            onNodeWithText("Ask again").assertIsDisplayed().assertIsNotEnabled()
            onNodeWithText("Asked again just now. You can ask again in a few minutes.")
                .assertIsDisplayed()
        }
    }

    /** It is offered on the earlier stage too, where the knock is for the quiet. */
    @Test
    fun `a proposal still short of members offers it as well`() {
        val short = proposal(
            "open", "New translation", "isiZulu",
            approved = true, nonces = 1, owing = 2,
        )

        render(loaded(listOf(short))) {
            onNodeWithText("Ask again").assertIsDisplayed().assertIsEnabled()
        }
    }

    @Test
    fun `a signed proposal offers nothing to ask about`() = render(loaded(listOf(signed))) {
        onAllNodesWithText("Ask again").assertCountEquals(0)
    }

    // --- asking about all of them at once -------------------------------------------

    private fun stalled(id: String, detail: String, owing: Int? = 1) = proposal(
        id, "New chapter", detail,
        stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
        approved = true, signerIds = "1,2", signatures = 1, owing = owing,
    )

    /**
     * The screen the filters made: three rows stalled for one reason, and one
     * tap to say one thing about all of them.
     */
    @Test
    fun `the being signed screen offers to ask about all of them`() {
        render(loaded(listOf(stalled("a", "Genesis 4"), stalled("b", "Genesis 5")))) {
            chip("Being signed · 2").performClick()

            onNodeWithText("Ask all 2 again").assertIsDisplayed().assertIsEnabled()
        }
    }

    @Test
    fun `tapping it asks about every row the filter shows`() {
        render(
            loaded(
                listOf(stalled("a", "Genesis 4"), stalled("b", "Genesis 5"), open, signed)
            )
        ) {
            chip("Being signed · 2").performClick()
            onNodeWithText("Ask all 2 again").performClick()

            waitUntil(timeoutMillis = 10_000) { resent.size == 2 }
        }

        assertEquals(listOf("a", "b"), resent, "the bulk tap reached past the filter")
    }

    /** One is what the row's own button is for. */
    @Test
    fun `a single stalled proposal gets no bulk button`() {
        render(loaded(listOf(stalled("a", "Genesis 4")))) {
            chip("Being signed · 1").performClick()

            onNodeWithText("Ask again").assertIsDisplayed()
            onAllNodesWithText("Ask all 1 again").assertCountEquals(0)
        }
    }

    @Test
    fun `rows that are not ours to send leave no bulk button`() {
        render(loaded(listOf(stalled("a", "Genesis 4", owing = null), stalled("b", "Genesis 5", owing = null)))) {
            chip("Being signed · 2").performClick()

            onAllNodesWithText("Ask all 2 again").assertCountEquals(0)
            onAllNodesWithText("Ask again").assertCountEquals(0)
        }
    }

    /**
     * The count on the button is what it can actually send, so a cooling-down
     * row lowers it rather than being skipped silently inside it.
     */
    @Test
    fun `a cooling-down row is not counted in the bulk offer`() {
        val cooling = proposal(
            "c", "New chapter", "Genesis 6",
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            approved = true, signerIds = "1,2", owing = 1,
            resentAt = Clock.System.now(),
        )

        render(loaded(listOf(stalled("a", "Genesis 4"), stalled("b", "Genesis 5"), cooling))) {
            chip("Being signed · 3").performClick()

            onNodeWithText("Ask all 2 again").assertIsDisplayed()
        }
    }

    /** It is offered on any filter that shows two it can act on, not only that one. */
    @Test
    fun `the unfiltered screen offers it too when two rows can take it`() {
        render(loaded(listOf(stalled("a", "Genesis 4"), stalled("b", "Genesis 5")))) {
            onNodeWithText("Ask all 2 again").assertIsDisplayed()
        }
    }

    @Test
    fun `a pushed screen keeps its way back, chips or no chips`() {
        render(loaded(mixed)) { onNodeWithContentDescription("Back").assertIsDisplayed() }
        render(loaded(emptyList())) { onNodeWithContentDescription("Back").assertIsDisplayed() }
    }

    @Test
    fun `the room that could not be found says so, with nothing to filter`() {
        render(ProposalListUIState.Error("Couldn't find the group")) {
            onNodeWithText("Couldn't find the group").assertIsDisplayed()
            onAllNodesWithText("All").assertCountEquals(0)
            // Nothing to retry, so nothing offering to.
            onAllNodesWithText("Try again").assertCountEquals(0)
        }
    }

    private fun loaded(proposals: List<ProposalListUIState.Proposal>) =
        ProposalListUIState.Loaded(
            localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
            proposals = proposals,
        )

    /**
     * The sessions a render asked again for, in order.
     *
     * Recorded through the repository the screen is given rather than through a
     * callback added for the test, so what these assert is the path the app
     * takes: the button, the view model's guard, and the repository call.
     */
    private val resent = mutableListOf<String>()

    private fun recordingRepository() =
        object : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
            override suspend fun resend(
                localChatRoom: LocalChatRoom,
                sessionId: String
            ): Boolean {
                resent += sessionId
                return true
            }

            /**
             * Unpaced here on purpose. What this file asks of a bulk tap is
             * which rows it covers; that the real run spaces them a second apart
             * is `FrostSigningResendJvmTest`'s to say, against the manager that
             * does the spacing.
             */
            override suspend fun resendAll(
                localChatRoom: LocalChatRoom,
                sessionIds: List<String>
            ): Int = sessionIds.count { resend(localChatRoom, it) }
        }

    private fun render(
        uiState: ProposalListUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        initialFilter: ProposalFilter = ProposalFilter.ALL,
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 500, height = 1600) {
        setContent {
            BennuTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ProposalListScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            initialProposalListUIState = uiState,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            frostSigningRepository = recordingRepository(),
                            onNavigateBack = {},
                            onNavigateToRoute = onNavigateToRoute,
                            initialFilter = initialFilter,
                        )
                    }
                }
            }
        }

        assertions()
    }
}
