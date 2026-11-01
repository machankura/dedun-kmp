package com.machankura.dedun.compose.ui.composable.navigation

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import com.machankura.dedun.compose.ui.composable.navigation.routes.ActiveProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.HomeRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SearchRoute
import com.machankura.dedun.compose.ui.theme.Breakpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The navigation component per breakpoint, and the routes its three items lead to.
 *
 * The mapping is the kind of thing that is wrong in one row and right in every screenshot
 * anybody takes: the collapsed and expanded rails differ only in whether labels are drawn,
 * and nobody opens a 1200dp window on purpose.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class NavigationSuiteTest {

    private val publicKey = "de1a1e64d1c4e0d6bd97b0e73d4dfd0e1ec1cdd1e6d8d0e2b9a7f3c5d0e1a2b3"
    private val profileEventId = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"

    @Test
    fun `each breakpoint takes the component the layout foundation gives it`() {
        // m3.material.io/foundations/layout/applying-layout/window-size-classes: navigation
        // bar under 600dp, collapsed rail through medium and expanded, expanded rail from
        // 1200dp. The library's own `NavigationSuiteScaffoldDefaults.navigationSuiteType`
        // cannot produce the last row -- it classifies with the three-value window size
        // class -- which is the whole reason this function exists.
        val expected = mapOf(
            Breakpoint.Compact to NavigationSuiteType.ShortNavigationBarCompact,
            Breakpoint.Medium to NavigationSuiteType.WideNavigationRailCollapsed,
            Breakpoint.Expanded to NavigationSuiteType.WideNavigationRailCollapsed,
            Breakpoint.Large to NavigationSuiteType.WideNavigationRailExpanded,
            Breakpoint.ExtraLarge to NavigationSuiteType.WideNavigationRailExpanded,
        )

        assertEquals(Breakpoint.entries.size, expected.size, "a breakpoint has no component")
        expected.forEach { (breakpoint, component) ->
            assertEquals(component, navigationSuiteTypeFor(breakpoint, isTopLevel = true), "$breakpoint")
        }
    }

    @Test
    fun `nothing is drawn off a top-level destination at any width`() {
        // The component belongs on the destinations it switches between. On a chat room, a
        // signing screen or an onboarding step -- reached by pushing a route and left by
        // coming back -- a persistent bar is an invitation to lose your place.
        Breakpoint.entries.forEach { breakpoint ->
            assertEquals(
                NavigationSuiteType.None,
                navigationSuiteTypeFor(breakpoint, isTopLevel = false),
                "$breakpoint drew a navigation component off a top-level destination",
            )
        }
    }

    @Test
    fun `every destination leads to the route it names`() {
        assertEquals(
            HomeRoute(activeUserPublicKey = publicKey),
            TopLevelDestination.Messages.route(publicKey, profileEventId),
        )
        assertEquals(
            SearchRoute(activeUserPublicKey = publicKey),
            TopLevelDestination.Search.route(publicKey, profileEventId),
        )
        assertEquals(
            ActiveProfileRoute(activeUserPublicKey = publicKey, nostrEventId = profileEventId),
            TopLevelDestination.Profile.route(publicKey, profileEventId),
        )
    }

    @Test
    fun `only the profile item is unavailable before its event has been read`() {
        // `ActiveProfileRoute` is addressed by metadata event id rather than by public key,
        // so between signing in and that row arriving there is no route to build. The item
        // is disabled for those moments; the other two are never affected, which is what
        // stops a "wait for everything" guard being put around the whole component.
        assertNull(TopLevelDestination.Profile.route(publicKey, activeProfileNostrEventId = null))
        listOf(TopLevelDestination.Messages, TopLevelDestination.Search).forEach {
            assertEquals(
                it.route(publicKey, profileEventId),
                it.route(publicKey, activeProfileNostrEventId = null),
                "$it changed with the profile event id, which it does not use",
            )
        }
    }
}
