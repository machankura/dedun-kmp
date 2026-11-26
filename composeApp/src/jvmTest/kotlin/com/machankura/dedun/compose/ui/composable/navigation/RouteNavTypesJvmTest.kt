package com.machankura.dedun.compose.ui.composable.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigation.NavDestinationBuilder
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.NavigatorProvider
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.serializerOrNull
import com.machankura.dedun.compose.ui.composable.navigation.routes.HomeRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.LoadingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProposalListRoute
import com.machankura.dedun.compose.ui.view.state.ProposalFilter
import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every route can be placed in a nav graph on the desktop.
 *
 * Navigation works out each argument's `NavType` from the route's serial descriptor
 * when the graph is built, and off Android it knows only the primitives, strings and
 * their lists. An enum argument compiled, ran on a phone, and threw on the desktop the
 * moment `DedunNavHost` composed -- which is right after unlocking, so the desktop app
 * could not be used at all. Nothing ran the graph on the JVM before this, so nothing
 * said so.
 *
 * The routes are found on the classpath rather than listed, so a new route is checked
 * without anyone remembering to add it here.
 */
@OptIn(ExperimentalTestApi::class)
class RouteNavTypesJvmTest {

    private val publicKey = "de1a1e64d1c4e0d6bd97b0e73d4dfd0e1ec1cdd1e6d8d0e2b9a7f3c5d0e1a2b3"
    private val roomId = "d4c3b2a1".repeat(8)

    @Test
    fun `every route builds into one graph with the app's type map`() {
        val routes = serializableRoutes()
        assertTrue(
            routes.size >= 50,
            "found only ${routes.size} routes on the classpath; the scan is probably broken: $routes"
        )

        val failures = routes.mapNotNull { route ->
            runCatching { destination(route, routeTypeMap) }
                .exceptionOrNull()
                ?.let { "${route.simpleName}: ${it.message}" }
        }
        assertEquals(emptyList(), failures, "routes that cannot be put in a desktop nav graph")

        val navigator = ComposeNavigator()
        val provider = NavigatorProvider().apply {
            addNavigator(navigator)
            addNavigator(NavGraphNavigator(this))
        }
        val graph = NavGraphBuilder(
            provider,
            startDestination = LoadingRoute::class,
            route = null,
            typeMap = emptyMap(),
        ).apply {
            routes.forEach { addDestination(destination(it, routeTypeMap, navigator)) }
        }.build()

        assertEquals(routes.size, graph.nodes.size())
    }

    /**
     * The map only helps where it is passed. Each route that cannot be built without it
     * has to be registered with it, and the registration is in `DedunNavHost`'s source.
     *
     * This is also the control: [ProposalListRoute] is the route that crashed, and if it
     * ever builds without the map then the platform has learned enums and this can go.
     */
    @Test
    fun `DedunNavHost passes the type map to every route that needs one`() {
        val needingMap = serializableRoutes().filter { route ->
            runCatching { destination(route, emptyMap()) }.isFailure
        }
        assertTrue(ProposalListRoute::class in needingMap, "ProposalListRoute now builds without a type map")

        val navHost = sourceRoot()
            .resolve("commonMain/kotlin/com/machankura/dedun/compose/ui/composable/navigation/DedunNavHost.kt")
            .readText()

        val unregistered = needingMap.filterNot { route ->
            Regex("""composable<(?:[\w.]+\.)?${route.simpleName}>\(\s*typeMap\s*=\s*routeTypeMap""")
                .containsMatchIn(navHost)
        }
        assertEquals(
            emptyList(),
            unregistered.map { it.simpleName },
            "these routes need `composable<…>(typeMap = routeTypeMap)` in DedunNavHost"
        )
    }

    @Test
    fun `without the type map the desktop cannot place a proposal filter`() {
        val error = assertFailsWith<IllegalArgumentException> {
            destination(ProposalListRoute::class, emptyMap())
        }
        assertTrue(error.message.orEmpty().contains("filter"), error.message)
    }

    /**
     * Through a real controller: the filter is written into the route string, read back
     * out of the saved state, and decoded into the route the screen is given.
     */
    @Test
    fun `a proposal list opens on the filter it was sent with`() = runDesktopComposeUiTest(400, 400) {
        lateinit var controller: NavHostController
        setContent {
            controller = rememberNavController()
            NavHost(navController = controller, startDestination = HomeRoute(publicKey)) {
                composable<HomeRoute> { Text("home") }
                composable<ProposalListRoute>(typeMap = routeTypeMap) { Text("proposals") }
            }
        }

        for (filter in ProposalFilter.entries) {
            runOnIdle {
                controller.navigate(
                    ProposalListRoute(activeUserPublicKey = publicKey, chatRoomId = roomId, filter = filter)
                )
            }
            waitForIdle()
            runOnIdle {
                val route = controller.currentBackStackEntry!!.toRoute<ProposalListRoute>()
                assertEquals(ProposalListRoute(publicKey, roomId, filter), route)
            }
        }

        // And with none named, which is how most callers arrive.
        runOnIdle { controller.navigate(ProposalListRoute(activeUserPublicKey = publicKey, chatRoomId = roomId)) }
        waitForIdle()
        runOnIdle {
            assertEquals(
                ProposalFilter.ALL,
                controller.currentBackStackEntry!!.toRoute<ProposalListRoute>().filter
            )
        }
    }

    @Test
    fun `an enum is written by name and refuses a name it does not have`() {
        val type = EnumNavType(ProposalFilter.entries)

        for (filter in ProposalFilter.entries) {
            assertEquals(filter.name, type.serializeAsValue(filter))
            assertEquals(filter, type.parseValue(filter.name))
        }
        assertFailsWith<IllegalArgumentException> { type.parseValue("SOMETIMES") }
    }

    private fun destination(
        route: KClass<*>,
        typeMap: Map<KType, NavType<*>>,
        navigator: ComposeNavigator = ComposeNavigator(),
    ) = NavDestinationBuilder(navigator, route, typeMap).build()

    /** Every `@Serializable` top-level class in the routes package. */
    private fun serializableRoutes(): List<KClass<*>> {
        val packageName = ProposalListRoute::class.java.packageName
        val path = packageName.replace('.', '/')
        val loader = javaClass.classLoader

        val classNames = loader.getResources(path).toList().flatMap { url ->
            when (url.protocol) {
                "file" -> File(url.toURI()).listFiles().orEmpty().map { it.name }
                "jar" -> (url.openConnection() as JarURLConnection).jarFile.entries().toList()
                    .map { it.name }
                    .filter { it.startsWith("$path/") && it.count { c -> c == '/' } == path.count { c -> c == '/' } + 1 }
                    .map { it.substringAfterLast('/') }
                else -> fail("cannot list classes from $url")
            }
        }
            .filter { it.endsWith(".class") && '$' !in it }
            .map { "$packageName.${it.removeSuffix(".class")}" }
            .distinct()

        return classNames
            .map { Class.forName(it, false, loader) }
            .filter { !it.isInterface && !Modifier.isAbstract(it.modifiers) && !it.isEnum }
            .filter { serializerOrNull(it) != null }
            .map { it.kotlin }
            .sortedBy { it.simpleName }
    }

    private fun sourceRoot(): File =
        File("src").takeIf { it.resolve("commonMain").isDirectory } ?: File("composeApp/src")
}
