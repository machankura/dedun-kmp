package com.machankura.dedun.compose.ui.composable.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.machankura.dedun.compose.ui.composable.navigation.routes.ActiveProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.HomeRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SearchRoute
import com.machankura.dedun.compose.ui.theme.Breakpoint
import com.machankura.dedun.compose.ui.theme.BennuTheme
import kotlin.test.Test

/**
 * The navigation component composed around a real nav graph.
 *
 * [NavigationSuiteTest] asserts the mapping; this asserts that the pieces meet. Three
 * things here can only go wrong in a composition, and each of them fails silently:
 *
 *  - `TopLevelDestination.of` matches by `NavDestination.hasRoute`, which is reflection
 *    over the serialized route. A route renamed, or a `@Serializable` dropped, stops
 *    matching, and the consequence is a navigation component that is simply never shown;
 *  - which item reads as selected follows the same `hasRoute` match, so a graph whose
 *    routes stopped matching would show a component with nothing selected in it;
 *  - the profile item is disabled until its metadata event id arrives, and "disabled"
 *    is a state that reads identically to "enabled" in source.
 *
 * The window is 1400 pixels at density 1, so the component is the expanded rail and the
 * three labels are drawn. In the compact bar they are drawn too, so the assertions hold
 * either way; the width is fixed so the test is not about the mapping.
 *
 * **Navigation is driven through the controller rather than by tapping an item.** Not a
 * preference: a click handler that navigates trips navigation-compose's own main-thread
 * assertion under `runDesktopComposeUiTest`, and it does so with twenty lines containing
 * no app code -- `NavHost`, two routes, and a `TextButton` that navigates. The harness
 * dispatches the click off the main thread and `LifecycleRegistry` refuses it. So what an
 * item's `onClick` builds is asserted in [NavigationSuiteTest], where it is a pure
 * function, and what the component does with a destination is asserted here.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationSuiteRenderJvmTest {

    private val publicKey = "de1a1e64d1c4e0d6bd97b0e73d4dfd0e1ec1cdd1e6d8d0e2b9a7f3c5d0e1a2b3"
    private val profileEventId = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"

    @Test
    fun `the three items are shown on a top-level destination`() = runDesktopComposeUiTest(1400, 900) {
        setContent { Harness(profileNostrEventId = profileEventId) }

        onNodeWithText("Messages").assertIsDisplayed()
        onNodeWithText("Search").assertIsDisplayed()
        onNodeWithText("Profile").assertIsDisplayed()
    }

    @Test
    fun `the item matching the current destination is the selected one`() =
        runDesktopComposeUiTest(1400, 900) {
            lateinit var controller: NavHostController
            setContent {
                controller = rememberNavController()
                Harness(profileNostrEventId = profileEventId, navController = controller)
            }

            onNodeWithText("Messages").assertIsSelected()
            onNodeWithText("Search").assertIsNotSelected()

            runOnIdle { controller.navigate(SearchRoute(publicKey)) }
            waitForIdle()

            onNodeWithText("search screen").assertIsDisplayed()
            onNodeWithText("Search").assertIsSelected()
            onNodeWithText("Messages").assertIsNotSelected()
        }

    @Test
    fun `nothing is drawn once a pushed route is on top`() = runDesktopComposeUiTest(1400, 900) {
        lateinit var controller: NavHostController
        setContent {
            controller = rememberNavController()
            Harness(profileNostrEventId = profileEventId, navController = controller)
        }

        onNodeWithText("Messages").assertIsDisplayed()

        runOnIdle {
            controller.navigate(
                ChatRoomMessagingRoute(
                    activeUserPublicKey = publicKey,
                    chatRoomId = "room",
                    relayHint = null,
                )
            )
        }
        waitForIdle()

        onNodeWithText("chat room").assertIsDisplayed()
        onNodeWithText("Messages").assertDoesNotExist()
        onNodeWithText("Profile").assertDoesNotExist()
    }

    @Test
    fun `the profile item waits for its event id, and the other two do not`() =
        runDesktopComposeUiTest(1400, 900) {
            setContent { Harness(profileNostrEventId = null) }

            onNodeWithText("Profile").assertIsNotEnabled()
            onNodeWithText("Messages").assertIsEnabled()
            onNodeWithText("Search").assertIsEnabled()
        }

    /**
     * The three top-level routes and one pushed route, each rendering only its own name.
     *
     * A stand-in for the app's graph rather than the graph itself: `BennuNavHost` builds
     * a database, six repositories and four view models on the way to its first screen,
     * and none of that decides whether the navigation component appears.
     */
    @Composable
    private fun Harness(
        profileNostrEventId: String?,
        navController: NavHostController = rememberNavController(),
    ) {
        BennuTheme {
            BennuNavigationSuite(
                navController = navController,
                breakpoint = Breakpoint.Large,
                activeUserPublicKey = publicKey,
                activeProfileNostrEventId = profileNostrEventId,
            ) {
                NavHost(navController, startDestination = HomeRoute(publicKey)) {
                    composable<HomeRoute> { Text("home screen") }
                    composable<SearchRoute> { Text("search screen") }
                    composable<ActiveProfileRoute> { Text("profile screen") }
                    composable<ChatRoomMessagingRoute> { Text("chat room") }
                }
            }
        }
    }
}
