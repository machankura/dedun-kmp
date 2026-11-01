package com.machankura.dedun.compose.ui.composable.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.messages
import bennu.composeapp.generated.resources.profile
import bennu.composeapp.generated.resources.search
import org.jetbrains.compose.resources.StringResource
import com.machankura.dedun.compose.ui.composable.navigation.routes.ActiveProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.HomeRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.navigation.routes.SearchRoute

/**
 * The three destinations the navigation component switches between.
 *
 * The app has one screen that is genuinely a top-level destination -- [Messages], which
 * is the home feed -- and two surfaces that were one tap from it through the home screen's
 * app bar: the profile avatar in the leading slot and the search icon in the trailing one.
 * Promoting those two is an information-architecture decision rather than a component
 * swap, and it was taken deliberately: M3's caution is to swap only functionally
 * equivalent components, and a navigation bar holding a single item would have been
 * strictly worse than the app bar it replaced.
 *
 * The consequence is that the home screen's app bar no longer carries either icon. Two
 * routes to the same destination is what the caution is about; the navigation component is
 * now the one route, at every breakpoint.
 *
 * @property icon the item's icon, and the only thing shown when the rail is collapsed.
 * @property label the item's text, shown in the bar and in the expanded rail.
 */
enum class TopLevelDestination(val icon: ImageVector, val label: StringResource) {
    Messages(Icons.Default.Forum, Res.string.messages),
    Search(Icons.Default.Search, Res.string.search),
    Profile(Icons.Default.Person, Res.string.profile);

    companion object {
        /**
         * Which destination [destination] belongs to, or `null` for everything else.
         *
         * `null` is what hides the navigation component. A navigation bar belongs on the
         * screens it switches between and nowhere else -- it has no meaning during
         * onboarding, in a chat room, or on a signing screen, all of which are reached by
         * pushing a route and left by coming back.
         */
        fun of(destination: NavDestination?): TopLevelDestination? = when {
            destination == null -> null
            destination.hasRoute(HomeRoute::class) -> Messages
            destination.hasRoute(SearchRoute::class) -> Search
            destination.hasRoute(ActiveProfileRoute::class) -> Profile
            else -> null
        }
    }

    /**
     * The route this item navigates to, or `null` when it cannot yet be built.
     *
     * Only [Profile] ever returns `null`, and only before the active profile's metadata
     * event has been read from the database. `ActiveProfileRoute` is addressed by event id
     * rather than by public key, so until that read lands there is no route to construct --
     * and an item that navigates nowhere is worse than one that is visibly disabled.
     */
    fun route(activeUserPublicKey: String, activeProfileNostrEventId: String?): Route? =
        when (this) {
            Messages -> HomeRoute(activeUserPublicKey = activeUserPublicKey)
            Search -> SearchRoute(activeUserPublicKey = activeUserPublicKey)
            Profile -> activeProfileNostrEventId?.let {
                ActiveProfileRoute(
                    activeUserPublicKey = activeUserPublicKey,
                    nostrEventId = it,
                )
            }
        }
}
