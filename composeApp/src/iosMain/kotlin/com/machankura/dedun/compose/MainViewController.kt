package com.machankura.dedun.compose

import androidx.compose.ui.window.ComposeUIViewController
import androidx.navigation.compose.rememberNavController
import fr.acinq.phoenix.PhoenixGlobal

fun MainViewController() = ComposeUIViewController {
    val navController = rememberNavController()

    BennuApp(
        navController = navController,
        bennuGlobal = BennuGlobal(
            platformContext = PlatformContext()
        ),
        phoenixGlobal = PhoenixGlobal(
            ctx = fr.acinq.phoenix.utils.PlatformContext()
        )
    )
}