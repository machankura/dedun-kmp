package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.repository.AdminRoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomDetailRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.MemberActionUnavailableRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.MemberAction
import com.machankura.dedun.compose.ui.view.state.MemberActionBlocker
import com.machankura.dedun.compose.ui.view.state.MemberActionUnavailableUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
internal object MemberActionRouteTestStart

/**
 * The screen a member row's closed action opens: what is in the way, what would
 * change it, and the one thing to do about it where there is one.
 * docs/member-row-actions.md, Phase 4.
 */
@OptIn(ExperimentalTestApi::class)
class MemberActionUnavailableScreenJvmTest {

    private val activeUserPublicKey = "a".repeat(64)
    private val chatRoomId = "f".repeat(64)
    private val nomvula = "b".repeat(64)
    private val thabo = "c".repeat(64)

    private fun blocked(vararg blockers: MemberActionBlocker, adminRoomId: String? = null) =
        blocked(*blockers, adminRooms = listOfNotNull(adminRoomId?.let { AdminRoom(name = "&Checkers", chatRoomId = it) }))

    private fun blocked(vararg blockers: MemberActionBlocker, adminRooms: List<AdminRoom>) = MemberActionUnavailableUIState.Blocked(
        memberName = "Nomvula",
        action = MemberAction.MAKE_ADMIN,
        blockers = blockers.toList(),
        names = mapOf(thabo to "Thabo"),
        adminRooms = adminRooms,
    )

    @Test
    fun `every reason says what is in the way and what would change it`() {
        val expected = mapOf(
            MemberActionBlocker.NoAdminsInThisRoom to "This room has no admins",
            MemberActionBlocker.LegacyRoom to "This group uses an older format",
            MemberActionBlocker.Disbanded to "This group was disbanded",
            MemberActionBlocker.YouLeft to "You've left this group",
            MemberActionBlocker.NotAnAdmin(setOf(thabo)) to "Only admins can do this",
            MemberActionBlocker.SignaturesNotRequired(emptySet()) to "The group has to sign changes first",
            MemberActionBlocker.ShareRotationUnavailable to "Not in this version of Dedun yet",
            MemberActionBlocker.NotInGroupYet to "Nomvula isn't fully in the group",
            MemberActionBlocker.OnlyAdmin to "Nomvula is the only admin",
            MemberActionBlocker.BelowFloor(3) to "A group with a shared key needs at least 3 admins",
            MemberActionBlocker.ChangePending to "An earlier change is still on its way",
            MemberActionBlocker.OfferPending to "Nomvula has already been asked",
        )
        expected.forEach { (blocker, title) ->
            render(blocked(blocker)) {
                onNodeWithText(title).assertExists()
            }
        }
    }

    @Test
    fun `a member who is not an admin is told who to ask, by name`() {
        render(blocked(MemberActionBlocker.NotAnAdmin(setOf(thabo)))) {
            onNodeWithText("Ask one of the group's admins: Thabo.").assertExists()
        }
    }

    @Test
    fun `a keyed room waiting on devices names them`() {
        render(blocked(MemberActionBlocker.SignaturesNotRequired(setOf(thabo)))) {
            onNodeWithText("That waits until Thabo update Dedun.").assertExists()
        }
    }

    @Test
    fun `the first reason leads, and the rest are under it`() {
        render(blocked(MemberActionBlocker.Disbanded, MemberActionBlocker.NotAnAdmin(setOf(thabo)))) {
            onNodeWithText("This group was disbanded").assertExists()
            onNodeWithText("Also in the way").assertExists()
            onNodeWithText("Only admins can do this").assertExists()
        }
        render(blocked(MemberActionBlocker.Disbanded)) {
            onNodeWithText("Also in the way").assertDoesNotExist()
        }
    }

    @Test
    fun `a room with no admins opens the admin room, when it knows which`() {
        var opened: Route? = null
        render(blocked(MemberActionBlocker.NoAdminsInThisRoom, adminRoomId = "9".repeat(64)), onNavigateToRoute = { opened = it }) {
            onNodeWithText("Open the admin room").performClick()
        }
        assertEquals(ChatRoomDetailRoute(activeUserPublicKey, "9".repeat(64), null), opened)

        render(blocked(MemberActionBlocker.NoAdminsInThisRoom)) {
            onNodeWithText("Open the admin room").assertDoesNotExist()
        }
        render(blocked(MemberActionBlocker.Disbanded, adminRoomId = "9".repeat(64))) {
            onNodeWithText("Open the admin room").assertDoesNotExist()
        }
    }

    /**
     * The same people may run several groups from one NIP-17 room, each from an admin
     * room of its own; which one the member meant is theirs to say.
     * docs/nip17-multiple-ceremonies.md, Phase 3.
     */
    @Test
    fun `a room whose members run several groups offers each admin room by name`() {
        val rooms = listOf(
            AdminRoom(name = "&Checkers", chatRoomId = "8".repeat(64)),
            AdminRoom(name = "&Chess", chatRoomId = "9".repeat(64)),
        )
        var opened: Route? = null
        render(blocked(MemberActionBlocker.NoAdminsInThisRoom, adminRooms = rooms), onNavigateToRoute = { opened = it }) {
            onNodeWithText("Open the admin room").assertDoesNotExist()
            onNodeWithText("Open {Checkers}").assertExists()
            onNodeWithText("Open {Chess}").performClick()
        }
        assertEquals(ChatRoomDetailRoute(activeUserPublicKey, "9".repeat(64), null), opened)
    }

    @Test
    fun `a reason that cleared says so, and goes back to the group`() {
        var wentBack = false
        render(MemberActionUnavailableUIState.NowAvailable("Nomvula", MemberAction.MAKE_ADMIN), onNavigateBack = { wentBack = true }) {
            onNodeWithText("You can do this now").assertExists()
            onNodeWithText("Back to the group").performClick()
        }
        assertTrue(wentBack)
    }

    @Test
    fun `the title is the action, and there is a way back on a desktop`() {
        var wentBack = false
        render(blocked(MemberActionBlocker.OnlyAdmin), onNavigateBack = { wentBack = true }) {
            onNodeWithText("Make admin").assertExists()
            onNodeWithContentDescription("Back").performClick()
        }
        assertTrue(wentBack)
    }

    @Test
    fun `a change since the row was opened says the action no longer applies`() {
        render(MemberActionUnavailableUIState.NoLongerApplies) {
            onNodeWithText("This no longer applies", substring = true).assertExists()
        }
    }

    /**
     * The route survives navigation-compose's encoding with each action: an enum in
     * a type-safe route is the part of D5 a library upgrade could break.
     */
    @Test
    fun `the route carries every action through navigation`() {
        MemberAction.entries.forEach { action ->
            val route = MemberActionUnavailableRoute(activeUserPublicKey, chatRoomId, nomvula, action)
            var arrived: MemberActionUnavailableRoute? = null
            runDesktopComposeUiTest {
                lateinit var controller: NavHostController
                setContent {
                    controller = rememberNavController()
                    NavHost(navController = controller, startDestination = MemberActionRouteTestStart) {
                        composable<MemberActionRouteTestStart> { Text("start") }
                        composable<MemberActionUnavailableRoute> { entry -> arrived = entry.toRoute() }
                    }
                }
                runOnUiThread { controller.navigate(route) }
                waitForIdle()
            }
            assertEquals(route, arrived)
        }
    }

    private fun render(
        state: MemberActionUnavailableUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        onNavigateBack: () -> Unit = {},
        assertions: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 400, height = 1600) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MemberActionUnavailableScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            memberPublicKey = nomvula,
                            action = MemberAction.MAKE_ADMIN,
                            initialUIState = state,
                            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            onNavigateToRoute = onNavigateToRoute,
                            onNavigateBack = onNavigateBack,
                        )
                    }
                }
            }
        }

        assertions()
    }
}
