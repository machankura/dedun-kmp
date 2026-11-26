package com.machankura.dedun.compose.ui.composable.navigation

import androidx.navigation.NavType
import androidx.savedstate.SavedState
import com.machankura.dedun.compose.ui.view.state.ProposalFilter
import kotlin.enums.EnumEntries
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * The `NavType`s a route's arguments need beyond the ones navigation knows.
 *
 * Navigation derives an argument's type from its serial descriptor, and on Android
 * an enum is one of those it knows -- it finds the class by reflection. Off Android
 * there is no reflection to find it with: on the desktop and on iOS the enum case
 * returns `UNKNOWN`, and building the graph throws on the first route carrying one.
 * The app compiled, ran on a phone, and crashed on the desktop as soon as it was
 * unlocked.
 *
 * So each enum or custom-typed argument is named here, and the `composable<…>` for
 * any route carrying one passes this map. `RouteNavTypesJvmTest` builds a destination
 * for every route with it, and checks that `DedunNavHost` passes it wherever a route
 * cannot be built without it.
 */
val routeTypeMap: Map<KType, NavType<*>> = mapOf(
    typeOf<ProposalFilter>() to EnumNavType(ProposalFilter.entries),
)

/**
 * An enum argument, written by its name.
 *
 * By name rather than ordinal so that a back stack saved before an entry was added or
 * reordered still reads as the state it meant, and so a route string is legible. It
 * is stored as a string, through [NavType.StringType], which every platform has.
 */
class EnumNavType<E : Enum<E>>(private val entries: EnumEntries<E>) : NavType<E>(isNullableAllowed = false) {

    override val name: String = entries.first()::class.simpleName ?: "enum"

    override fun put(bundle: SavedState, key: String, value: E) {
        StringType.put(bundle, key, value.name)
    }

    override fun get(bundle: SavedState, key: String): E? =
        StringType[bundle, key]?.let(::parseValue)

    override fun parseValue(value: String): E =
        entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("$value is not one of ${entries.map { it.name }}")

    override fun serializeAsValue(value: E): String = value.name
}
