package com.machankura.dedun.compose

import androidx.compose.ui.window.ComposeUIViewController
import androidx.navigation.compose.rememberNavController
import fr.acinq.phoenix.PhoenixGlobal

/**
 * The process's runtime on iOS. A background task reaches the same one through here, so a
 * refresh that runs while the app is in memory shares its database and relay pool with the UI
 * rather than opening a second of each. docs/notifications-and-background-sync.md, D4.
 */
fun dedunRuntime(): DedunRuntime = DedunRuntime.getOrCreate {
    DedunRuntime(
        dedunGlobal = DedunGlobal(platformContext = PlatformContext()),
        phoenixGlobal = PhoenixGlobal(ctx = fr.acinq.phoenix.utils.PlatformContext()),
    )
}

fun MainViewController() = ComposeUIViewController {
    val navController = rememberNavController()

    DedunApp(
        navController = navController,
        runtime = dedunRuntime(),
    )
}
