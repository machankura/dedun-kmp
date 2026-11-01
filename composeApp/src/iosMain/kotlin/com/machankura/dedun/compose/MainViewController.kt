package com.machankura.dedun.compose

import androidx.compose.ui.window.ComposeUIViewController
import androidx.navigation.compose.rememberNavController
import fr.acinq.phoenix.PhoenixGlobal

fun MainViewController() = ComposeUIViewController {
    val navController = rememberNavController()

    DedunApp(
        navController = navController,
        dedunGlobal = DedunGlobal(
            platformContext = PlatformContext()
        ),
        phoenixGlobal = PhoenixGlobal(
            ctx = fr.acinq.phoenix.utils.PlatformContext()
        )
    )
}