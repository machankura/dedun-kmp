package com.machankura.dedun.compose.notifications

import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.unread_count
import org.jetbrains.compose.resources.stringResource

/**
 * A room's unread count, as an M3 large badge. A muted room's is drawn in the quieter secondary
 * pair: still counted, not asking for attention. Read aloud as a sentence rather than a bare number,
 * which beside a timestamp means nothing. docs/notifications-and-background-sync.md, Phase 4.
 */
@Composable
fun UnreadBadge(count: Int, muted: Boolean, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val description = stringResource(Res.string.unread_count, count)
    Badge(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        containerColor = if (muted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.error,
        contentColor = if (muted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onError,
    ) {
        Text(badgeText(count))
    }
}

/** What fits in a badge: the count, or 999+ past it. */
internal fun badgeText(count: Int): String = if (count > MAX_SHOWN) "$MAX_SHOWN+" else count.toString()

private const val MAX_SHOWN = 999
