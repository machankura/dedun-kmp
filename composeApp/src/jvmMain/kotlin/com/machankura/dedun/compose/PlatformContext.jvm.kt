package com.machankura.dedun.compose

import java.io.File
import java.util.Locale

/**
 * Android wraps a `Context`, which is where the database and files directories come from.
 * The jvm has no such object, so the location is carried explicitly.
 *
 * This is deliberately *not* the same class as `fr.acinq.phoenix.utils.PlatformContext`,
 * matching how android and ios keep the two apart -- `MainActivity` and
 * `MainViewController` each construct both. A desktop entry point should hand the same
 * directory to both so one install keeps one place on disk.
 */
actual class PlatformContext(
    val applicationDir: File = defaultDedunDir(),
)

/**
 * The conventional per-user application data directory for the host os.
 *
 * Pointedly not `java.io.tmpdir`, which is what the abandoned Aux implementation used
 * behind a TODO: most systems clear it on reboot, and the chat history and marmot group
 * state live here.
 */
internal fun defaultDedunDir(): File {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return when {
        os.contains("win") ->
            (System.getenv("LOCALAPPDATA") ?: System.getenv("APPDATA"))
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it, APP_DIR_NAME) }
                ?: File(home, "AppData/Local/$APP_DIR_NAME")

        os.contains("mac") || os.contains("darwin") ->
            File(home, "Library/Application Support/$APP_DIR_NAME")

        else ->
            System.getenv("XDG_DATA_HOME")
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it, APP_DIR_NAME) }
                ?: File(home, ".local/share/$APP_DIR_NAME")
    }
}

/**
 * The directory name kept the old brand through the rebrands to Curare and Bennu, for the same
 * reason the `mantra/` hash prefixes did: its spelling is load-bearing for state that
 * already exists. It names the one directory holding the Room database, the marmot
 * group state and the phoenix wallet, so renaming it does not move an install -- it
 * silently starts a new one beside it, and the old chat history and the old seed stay
 * in a directory nothing reads any more.
 *
 * Renaming it is a migration -- copy or move on first run, then flip this -- and not
 * part of a rebrand. Until that exists, the desktop app is `curated` on disk and
 * `Bennu` everywhere a user can read.
 */
private const val APP_DIR_NAME = "curated"
