package com.machankura.dedun.compose.ui.composable.widgets.buttons

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.machankura.dedun.compose.ui.theme.spacing

/**
 * A screen's one primary action, as an extended FAB with a label beside its icon.
 *
 * Every bottom bar in the app ends in one of these -- propose, broadcast, next,
 * create -- and this is the one place their shape is decided. Two decisions:
 *
 *  - **The gap between icon and label is drawn here.** M3's `ExtendedFloatingActionButton`
 *    comes in two overloads. The `text`/`icon` one spaces them 12dp apart, but wraps the
 *    label in `clearAndSetSemantics`, so a screen reader is told only what the icon says
 *    and a test cannot find the button by its label; the content-lambda one keeps the
 *    label in the tree and puts nothing between the two. This uses the second and adds
 *    the first's gap, `space150`, which is the 12dp M3 names `ExtendedFabEndIconPadding`.
 *    The label is what the button says, so it has to be what the button *announces*.
 *  - **A FAB has no `enabled`, so unavailable is a look and a refusal, not a state.**
 *    M3 gives the FAB no such parameter. Here [enabled] false borrows the disabled
 *    colours every other button in the app uses rather than inventing a shade, marks the
 *    node disabled so a screen reader does not announce a button it is happy to press,
 *    and drops the press -- looking unavailable is not being unavailable, and each of
 *    those three has to be done for the look to be honest.
 *
 * @param label what the button says. Text, so from the catalogue; drawn after the icon.
 * @param icon what sits before the label. Usually an `Icon` -- whose description is the
 *   caller's decision, `Decorative` when the label says it all and a real one when a test
 *   reaches for the button by it -- and sometimes a progress indicator in its place while
 *   the action it started is out.
 * @param enabled whether a press does anything. False also changes the look, as above.
 */
@Composable
fun ExtendedFab(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val buttonColors = ButtonDefaults.buttonColors()

    ExtendedFloatingActionButton(
        modifier = if (enabled) {
            modifier
        } else {
            modifier.semantics { disabled() }
        },
        containerColor = if (enabled) {
            FloatingActionButtonDefaults.containerColor
        } else {
            buttonColors.disabledContainerColor
        },
        contentColor = if (enabled) {
            contentColorFor(FloatingActionButtonDefaults.containerColor)
        } else {
            buttonColors.disabledContentColor
        },
        onClick = {
            if (enabled) onClick()
        }
    ) {
        icon()

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.space150))

        Text(label)
    }
}
