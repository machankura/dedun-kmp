package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.something_went_wrong
import dedun.composeapp.generated.resources.try_again
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.ui.theme.reducedMotion
import com.machankura.dedun.compose.ui.theme.spacing

/**
 * The two states every list and every screen has, and neither of which had a home.
 *
 * Before this file the tree held **16 copies** of
 *
 *     Column(horizontalAlignment = CenterHorizontally) {
 *         Spacer(Modifier.height(48.dp))
 *         Text("Something went wrong")
 *     }
 *
 * and five of the same shape saying "No events were found". Not one of the sixteen offered
 * a retry, so every failure in this app was a dead end: the message named no cause, and
 * the only way out was the back button.
 *
 * M3's content guidance asks the opposite -- "emphasize the results of the user's
 * potential action", "tell users what will happen ... and how they can undo it" -- and its
 * structure guidance asks that a screen say what to do next rather than only what
 * happened.
 *
 * These two composables are deliberately plain: an icon, a line, and for [ErrorState] an
 * action when the caller can offer one. They are the thing 21 call sites collapse into,
 * not a design in their own right.
 */
@Composable
fun ErrorState(
    modifier: Modifier = Modifier,
    message: String = stringResource(Res.string.something_went_wrong),
    /**
     * `null` where the caller genuinely has nothing to retry -- a screen whose state came
     * from a navigation argument that was already wrong. Passing null is a decision;
     * omitting a retry that exists is the thing this parameter is here to make visible.
     */
    onRetry: (() -> Unit)? = null,
) {
    StateMessage(
        modifier = modifier,
        icon = Icons.Default.ErrorOutline,
        message = message,
        // The icon is decorative: the message beside it says what happened, and naming
        // this one would have a screen reader announce the trouble twice.
        iconDescription = Decorative,
        action = onRetry?.let {
            { TextButton(onClick = it) { Text(stringResource(Res.string.try_again)) } }
        },
    )
}

/**
 * A list with nothing in it yet.
 *
 * The message is required rather than defaulted, because "No events were found" told the
 * user nothing about which list was empty or what would fill it, and a shared default
 * would preserve exactly that. Say what is missing and, where there is one, how to get
 * some.
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Inbox,
    action: (@Composable () -> Unit)? = null,
) {
    StateMessage(
        modifier = modifier,
        icon = icon,
        message = message,
        iconDescription = Decorative,
        action = action,
    )
}

@Composable
private fun StateMessage(
    modifier: Modifier,
    icon: ImageVector,
    message: String,
    iconDescription: String?,
    action: (@Composable () -> Unit)?,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.screenMargin,
                vertical = MaterialTheme.spacing.emphasisGap,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = iconDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/**
 * Fades between a screen's states instead of cutting.
 *
 * Every screen in this app is a `when` over a UI state -- loading, error, empty, loaded --
 * and every one of those transitions was an unannounced cut: the spinner is there in one
 * frame and the content is there in the next, with nothing saying they are the same screen
 * answering the same question.
 *
 * M3's fade-through, which is the transition for content that replaces other content
 * without being spatially related to it: the outgoing state fades out, the incoming one
 * fades in and grows the last few percent into place. `SizeTransform` is off, so a tall
 * loaded state does not stretch a short spinner on its way in.
 *
 * **The content key is the state's class, not the state.** Without it a screen would
 * re-run the whole transition every time its loaded data changed -- a message arriving, a
 * list growing -- which is a fade over content that did not change state at all. With it,
 * the animation runs when the state does and the data flows through untouched.
 *
 * **`AnimatedContent` is a layout node**, so it must wrap a `when` that is a composable's
 * whole body. Where the `when` sits inside a `Column` whose branches use
 * `Modifier.weight`, wrapping it would take the branches out of `ColumnScope`; those
 * screens are left alone and the reason is here rather than repeated at each of them.
 *
 * @param state the screen's current UI state.
 * @param label named for the animation inspector; the screen's own name is the useful one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T : Any> ScreenStateTransition(
    state: T,
    modifier: Modifier = Modifier,
    label: String = "screen state",
    content: @Composable (T) -> Unit,
) {
    val reduced = MaterialTheme.reducedMotion
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    AnimatedContent(
        targetState = state,
        modifier = modifier,
        contentKey = { it::class },
        transitionSpec = {
            if (reduced) {
                // Still a transition, still not a cut -- what goes is the movement. The
                // scale is the only spatial part of a fade-through.
                fadeIn(effects) togetherWith fadeOut(effects) using SizeTransform(clip = false)
            } else {
                (fadeIn(effects) + scaleIn(spatial, initialScale = EnteringScale))
                    .togetherWith(fadeOut(effects))
                    .using(SizeTransform(clip = false))
            }
        },
        label = label,
    ) { targetState -> content(targetState) }
}

/**
 * How small the arriving state starts.
 *
 * M3's fade-through grows the incoming content from 92%. Any less reads as a zoom, which
 * says the two states are the same thing seen closer rather than one replacing the other.
 */
private const val EnteringScale = 0.92f
