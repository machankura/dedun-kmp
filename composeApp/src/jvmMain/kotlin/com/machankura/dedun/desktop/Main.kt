package com.machankura.dedun.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.security.JvmKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.DedunApp
import com.machankura.dedun.compose.DedunGlobal
import com.machankura.dedun.compose.PlatformContext
import com.machankura.dedun.compose.defaultDedunDir
import com.machankura.dedun.compose.ui.theme.DedunTheme
import java.awt.Dimension
import java.io.File

/**
 * The desktop entry point, named by the `compose.desktop` block in build.gradle.kts.
 *
 * Two things differ from `MainActivity` and `MainViewController`, and both follow from the
 * jvm having no operating system keystore:
 *
 *  - the wallet seed is protected by a passphrase, so nothing can start until one has been
 *    entered (see [PassphraseGate]);
 *  - one directory is handed to both the dedun and the phoenix context, so a single install
 *    keeps a single place on disk rather than two named after different projects.
 */
fun main() = application {
    val appDir = remember { defaultDedunDir().apply { mkdirs() } }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Dedun",
        // 1100dp is the expanded breakpoint -- 840 to 1199 -- which is the narrowest window
        // M3 recommends two panes in, and so the smallest opening size at which a desktop
        // user sees a desktop layout rather than a phone one stretched. It used to open at
        // 480dp with a comment apologising for it; that was honest while the layouts had
        // only ever been exercised at phone widths, and is no longer true.
        //
        // The content does not stretch to fill it. Screens are held to a readable measure
        // and centred (see `readableContent`), so the extra width is margin, which is what
        // M3's single-pane canonical layout does with a window wider than its content.
        state = rememberWindowState(width = 1100.dp, height = 800.dp),
    ) {
        var unlocked by remember { mutableStateOf(false) }

        // A floor rather than a preference. Compose Desktop has no minimum in WindowState,
        // so without this the window can be dragged narrower than any layout in the app was
        // written for -- and the compact breakpoint's own floor is a phone, not nothing.
        // 400x600 is the narrowest window the phase's acceptance widths name.
        LaunchedEffect(window) { window.minimumSize = Dimension(400, 600) }

        // DedunTheme wraps both branches, not just the app. PassphraseGate used to sit
        // beside DedunApp -- which applies the theme itself -- so it composed under the
        // default MaterialTheme and its colorScheme and typography were baseline M3
        // rather than this app's. It is the first screen a desktop user sees.
        //
        // DedunApp still applies DedunTheme, so the unlocked branch is wrapped twice.
        // That is deliberate: android and ios enter through DedunApp and would lose the
        // theme entirely if it moved out, and a second application of the same values
        // costs one composition of a CompositionLocalProvider.
        DedunTheme {
            if (unlocked) {
                val navController = rememberNavController()
                DedunApp(
                    navController = navController,
                    dedunGlobal = DedunGlobal(platformContext = PlatformContext(appDir)),
                    phoenixGlobal = PhoenixGlobal(
                        ctx = fr.acinq.phoenix.utils.PlatformContext(applicationDir = appDir),
                    ),
                )
            } else {
                PassphraseGate(appDir = appDir, onUnlocked = { unlocked = true })
            }
        }
    }
}

/**
 * Blocks the app until [JvmKeyStore] holds a derived key.
 *
 * Android and ios have no screen like this because their OS keystores serve keys without
 * asking anybody anything. Here the passphrase is the protection, so seed access cannot begin
 * before it is supplied: `keyStoreEncryption` and `keyStoreDecryption` throw until it is.
 *
 * **A new store accepts any passphrase**, since there is nothing yet to check one against. On
 * an existing store a wrong one is not detected here either -- it surfaces later, as a seed
 * that will not decrypt, when a data key fails to unwrap. That is a poor thing to learn after
 * the fact, and the fix belongs in the key store rather than in this screen.
 */
@Composable
private fun PassphraseGate(appDir: File, onUnlocked: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The first screen of a desktop app, whose entire content is one field. M3 asks for
    // an initial focus to be defined per screen; here there is only one candidate, and on
    // a desktop there is no tap to give it focus -- somebody who opens the app and starts
    // typing their passphrase should not have to reach for the mouse first.
    val passphraseFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { passphraseFocus.requestFocus() }

    fun submit() {
        if (busy || passphrase.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            // Argon2id at 64 MiB is deliberately slow. Off the ui thread, or the window stops
            // painting for as long as it takes.
            val failure = withContext(Dispatchers.Default) {
                runCatching {
                    val chars = passphrase.toCharArray()
                    try {
                        JvmKeyStore.unlock(chars, appDir)
                    } finally {
                        chars.fill(' ')
                    }
                }.exceptionOrNull()
            }
            busy = false
            if (failure == null) {
                passphrase = ""
                onUnlocked()
            } else {
                error = failure.message ?: failure::class.simpleName
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Unlock Dedun", style = MaterialTheme.typography.headlineSmall)
            Text(
                "This desktop build protects your seed with a passphrase rather than a " +
                    "hardware-backed keystore. Do not use it for real funds.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it; error = null },
                label = { Text("Passphrase") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(passphraseFocus),
            )
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (busy) {
                CircularProgressIndicator()
            } else {
                Button(onClick = { submit() }, enabled = passphrase.isNotEmpty()) {
                    Text("Unlock")
                }
            }
        }
    }
}
