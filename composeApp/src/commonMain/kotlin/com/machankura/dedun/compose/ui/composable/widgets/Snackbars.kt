package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Somewhere to say what just happened.
 *
 * The app had **no** `Snackbar`, `SnackbarHost` or `SnackbarHostState` anywhere, across 26
 * `Scaffold`s. Every transient outcome -- an invite that failed, a key package published,
 * a message that did not send -- had nowhere to be reported, so the code either said
 * nothing or navigated away and hoped.
 *
 * M3 puts a snackbar host in the `Scaffold` for this, and the reason it is a composition
 * local rather than a parameter is where the reporting happens: a view model coroutine
 * finishing a network call is several composables below the `Scaffold` that owns the host.
 * Threading a `SnackbarHostState` down through every screen's parameter list would be the
 * same plumbing repeated 26 times, and the parameter would be forgotten on the 27th.
 *
 * ```
 * Scaffold(snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) }) { … }
 * …
 * val notify = rememberNotifier()
 * notify("Invite sent")
 * ```
 */
val LocalSnackbarHostState: ProvidableCompositionLocal<SnackbarHostState> =
    staticCompositionLocalOf {
        error(
            "No SnackbarHostState. Wrap the screen in ProvideSnackbarHost, or hand the " +
                "Scaffold a snackbarHost of its own."
        )
    }

/**
 * Provides a host state for everything inside [content].
 *
 * Deliberately throws rather than defaulting to a detached `SnackbarHostState()`. A
 * default would make `notify(...)` a silent no-op on any screen that forgot the host,
 * which is the failure this whole file exists to end -- a message with nowhere to go is
 * exactly what the app already had.
 */
@Composable
fun ProvideSnackbarHost(
    hostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (SnackbarHostState) -> Unit,
) {
    CompositionLocalProvider(LocalSnackbarHostState provides hostState) {
        content(hostState)
    }
}

/**
 * A function for showing a message, callable from anywhere under a [ProvideSnackbarHost].
 *
 * Takes the scope from the caller so the message survives the composable that sent it
 * going away -- which it usually does, since "saved" is shown as the screen navigates
 * back.
 */
@Composable
fun rememberNotifier(scope: CoroutineScope): (String) -> Unit {
    val hostState = LocalSnackbarHostState.current
    return remember(hostState, scope) {
        { message ->
            scope.launch {
                // Short by default. A snackbar is for something the user does not have to
                // act on; anything they must read belongs in a dialog or on the screen.
                hostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            }
        }
    }
}
