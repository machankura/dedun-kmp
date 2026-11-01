package com.machankura.dedun.compose.ui.composable.navigation

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.navigation.routes.HomeRoute
import com.machankura.dedun.compose.ui.theme.Breakpoint

/**
 * The navigation bar, collapsed rail or expanded rail, around whatever the nav host draws.
 *
 * **Which component, at which breakpoint**, straight from the layout foundation's table:
 *
 * | breakpoint          | component            |
 * |---------------------|----------------------|
 * | compact             | navigation bar       |
 * | medium, expanded    | collapsed rail       |
 * | large, extra-large  | expanded rail        |
 *
 * `NavigationSuiteScaffoldDefaults.navigationSuiteType` is not used, and the difference is
 * the last row: it stops at `WideNavigationRailCollapsed` and never returns the expanded
 * rail, because it classifies with the three-value window size class rather than the five
 * breakpoints the May 2026 revision published. Deriving the type from [Breakpoint] instead
 * keeps one source of truth for window width in the app and reaches the row the library's
 * default cannot.
 *
 * **Hidden on everything else.** [NavigationSuiteType.None] draws no component at all, and
 * that is the case for every screen that is not one of the three: onboarding, a chat room,
 * a signing screen, the desktop passphrase gate. A navigation bar belongs on the
 * destinations it switches between; on a screen reached by pushing a route and left by
 * coming back it is a permanent invitation to lose your place.
 *
 * @param navController the host's controller, read for the current destination and used to
 *   navigate. Passed rather than hoisted into callbacks because the top-level navigation
 *   options below -- `saveState`, `restoreState`, `launchSingleTop` -- are the point of the
 *   component and belong with it rather than at three call sites.
 * @param breakpoint the window's breakpoint, which decides the component.
 * @param activeUserPublicKey the signed-in key, or `null` before anybody has signed in --
 *   in which case no destination has a route and the component stays hidden.
 * @param activeProfileNostrEventId the metadata event addressing the active profile, or
 *   `null` until it has been read. Only [TopLevelDestination.Profile] needs it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BennuNavigationSuite(
    navController: NavHostController,
    breakpoint: Breakpoint,
    activeUserPublicKey: String?,
    activeProfileNostrEventId: String?,
    content: @Composable () -> Unit,
) {
    val currentDestination: NavDestination? =
        navController.currentBackStackEntryAsState().value?.destination
    val selected = TopLevelDestination.of(currentDestination)

    val suiteType = navigationSuiteTypeFor(
        breakpoint = breakpoint,
        // Not on a top-level destination, or nobody is signed in, so there is nothing for
        // the component to switch between.
        isTopLevel = selected != null && activeUserPublicKey != null,
    )

    NavigationSuiteScaffold(
        navigationSuiteType = suiteType,
        navigationItems = {
            if (suiteType == NavigationSuiteType.None || activeUserPublicKey == null) return@NavigationSuiteScaffold

            TopLevelDestination.entries.forEach { destination ->
                val route = destination.route(activeUserPublicKey, activeProfileNostrEventId)

                NavigationSuiteItem(
                    navigationSuiteType = suiteType,
                    selected = destination == selected,
                    // Disabled rather than absent while the profile's event id is still
                    // being read. An item that appears and then disappears moves the two
                    // beside it, and a bar whose items move under a thumb is worse than
                    // one with a briefly unavailable item.
                    enabled = route != null,
                    onClick = {
                        if (route != null && destination != selected) {
                            navController.navigate(route) {
                                // The standard top-level options, and each answers a
                                // failure this app would otherwise have. Without
                                // `popUpTo` the back stack grows by one every time
                                // somebody taps between the three, so back becomes a
                                // walk through their tapping history rather than a way
                                // out. Without `launchSingleTop` a second tap on the
                                // current item pushes a duplicate. `saveState` and
                                // `restoreState` are what keep a scrolled feed scrolled
                                // when you come back to it.
                                //
                                // Popped to `HomeRoute` and not to the graph's start
                                // destination, which is the shape the android docs give
                                // and which would be wrong here: this graph starts at
                                // `LoadingRoute`, and onboarding clears the stack with
                                // `popUpTo(0)` on its way to home -- so by the time these
                                // items exist the start destination is not on the stack at
                                // all, and popping to it would leave the loading screen
                                // underneath as the thing back returns to. Home is the
                                // root of the signed-in graph, and is what back should
                                // reach from either of the other two.
                                popUpTo<HomeRoute> { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            // The label is beside the icon in the bar and in the expanded
                            // rail, and a reader would hear the name twice. The collapsed
                            // rail is the exception, and it draws the label as a tooltip
                            // the accessibility tree still carries.
                            contentDescription = Decorative,
                        )
                    },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
        content = content,
    )
}

/**
 * The navigation component a window at [breakpoint] should carry, per the table above.
 *
 * Separate from the composable so the table can be asserted. A breakpoint-to-component
 * mapping is exactly the kind of thing that is wrong in one row and looks right in every
 * screenshot anybody takes: the two rail rows differ only in whether labels are shown, and
 * nobody opens a 1200dp window to check.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun navigationSuiteTypeFor(breakpoint: Breakpoint, isTopLevel: Boolean): NavigationSuiteType =
    when {
        !isTopLevel -> NavigationSuiteType.None
        breakpoint == Breakpoint.Compact -> NavigationSuiteType.ShortNavigationBarCompact
        breakpoint.isAtLeast(Breakpoint.Large) -> NavigationSuiteType.WideNavigationRailExpanded
        else -> NavigationSuiteType.WideNavigationRailCollapsed
    }
