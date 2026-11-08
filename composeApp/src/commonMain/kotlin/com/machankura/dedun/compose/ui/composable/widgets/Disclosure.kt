package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import com.machankura.dedun.compose.ui.theme.reducedMotion
import com.machankura.dedun.compose.ui.theme.spacing

/**
 * A label that opens to show the detail under it.
 *
 * M3's disclosure pattern, and the thing `ui/composable/widgets/` did not have: the four files that
 * animate a section open each did it inline for their own case, so a fifth would have been a fifth
 * copy. Named `Disclosure` rather than the plan's `DisclosureCard` because it draws no surface of its
 * own -- its first caller is already inside a `Card`, and a card inside a card is two elevations
 * saying one thing.
 *
 * [openFor] is for a value a reader must not miss. It opens the section, and opens it again whenever
 * it becomes true, so a figure that starts agreeing and later disagrees does not stay folded away.
 * A member can still collapse it; the next time the condition fires it comes back. Without that,
 * hiding anything behind a label would be a way to hide bad news, which is the one way a disclosure
 * can do harm.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ColumnScope.Disclosure(
    label: String,
    modifier: Modifier = Modifier,
    openFor: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var isOpen by rememberSaveable { mutableStateOf(openFor) }
    LaunchedEffect(openFor) { if (openFor) isOpen = true }
    Row(
        // One line, both calls: `m3-touch-targets.py` reads the clickable's line and the one above
        // it, so a minimum on the line below reads as no minimum at all. It is also the shape
        // CLAUDE.md gives.
        modifier = modifier
            .fillMaxWidth()
            .clickable { isOpen = !isOpen }.minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Decorative: the label beside it is the control's name, and the row's own semantics carry
        // the click. An icon described as "expand" would make the row read as two things.
        Icon(
            if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = Decorative,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Spatial for the height, effects for the fade, which is the distinction the scheme draws. Under
    // reduced motion the section still appears and disappears -- it just stops growing.
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val reduced = MaterialTheme.reducedMotion
    AnimatedVisibility(
        visible = isOpen,
        enter = if (reduced) fadeIn(effects) else expandVertically(spatial) + fadeIn(effects),
        exit = if (reduced) fadeOut(effects) else shrinkVertically(spatial) + fadeOut(effects),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) { content() }
    }
}
