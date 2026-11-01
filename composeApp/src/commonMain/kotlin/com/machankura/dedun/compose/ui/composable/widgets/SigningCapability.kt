package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import com.machankura.dedun.compose.identity.Identity

/**
 * Whether the identity the app is running as can sign -- the one question every write
 * entrance asks before it is drawn.
 *
 * A composition local rather than a parameter for the snackbar host's reason: screens
 * do not receive the identity, `MetadataEventDetail` -- where *follow* and *send message*
 * live -- is several composables below anything that could be handed more, and a
 * parameter threaded through twenty-six lists is forgotten in the twenty-seventh.
 * [ProvideSigningCapability] sits once, above every screen, in the nav host.
 *
 * The default is `true` rather than an error, and that is the one way this differs from
 * [LocalSnackbarHostState]: the provider cannot be forgotten per screen, so the only
 * things composed outside it are previews and tests, and those should render as they
 * always have.
 *
 * A capability, not a kind. A screen asks "can this identity sign?", not "is this an
 * npub?", because the answer is what it needs -- and because a remote signer, which can
 * sign and holds no local key, would otherwise be a fourth value in every `when`. See
 * docs/npub-sign-in.md, Phase 5.
 */
val LocalCanSign: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/** Provides [LocalCanSign] from the active identity for everything inside [content]. */
@Composable
fun ProvideSigningCapability(
    activeIdentity: StateFlow<Identity?>,
    content: @Composable () -> Unit,
) {
    val identity by activeIdentity.collectAsState()
    // No identity yet -- startup, landing, sign-in -- is not read-only; it is nobody. Those
    // screens have no write entrances to hide, and `true` keeps them drawn as they were.
    val canSign = identity?.canSign ?: true
    CompositionLocalProvider(LocalCanSign provides canSign) {
        content()
    }
}
