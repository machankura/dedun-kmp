package com.machankura.dedun.compose.ui.composable.widgets.content

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.loading_note


@Composable
internal fun QuotedAddressableNote(
    localQuotedNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalQuotedNostrEvent?,
    kind: Int,
    dTag: String,
    author: String,
    relayHints: List<String>,
    onNoteClick: (String) -> Unit,
    onProfileClick: ((String) -> Unit)?,
    noteActions: NoteActions?,
    style: TextStyle
) {

    // TODO: Sync quoted event...

    if (localQuotedNostrEvent != null) {
        QuotedNote(
            localQuotedNostrEvent = localQuotedNostrEvent,
            onNavigateToEvent = onNoteClick,
            noteActions = noteActions
        )
    } else {
        // Loading state
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.space75)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(MaterialTheme.spacing.space175)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(14.dp)
                        .height(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(MaterialTheme.spacing.space100))
                Text(
                    text = stringResource(Res.string.loading_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
