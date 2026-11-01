package com.machankura.dedun.compose.ui.composable.widgets.content

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.loading_note
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@Composable
fun QuotedNote(
    localQuotedNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalQuotedNostrEvent? = null,
    onNavigateToEvent:(HexKey) -> Unit,
    noteActions: NoteActions? = null
) {
    // Observe versions so we recompose when data arrives from relays

    // TODO: Trigger on-demand fetch if the quoted event isn't cached
//    LaunchedEffect(eventId) {
//        if (eventRepo.getEvent(eventId) == null) {
//            eventRepo.requestQuotedEvent(eventId, relayHints)
//        }
//    }

    val effectiveNoteClick = noteActions?.onNoteClick ?: onNavigateToEvent

    // Fetch poll votes when quoted event is a poll
//    LaunchedEffect(event?.id, event?.kind) {
//        if (event != null && event.kind == com.wisp.app.nostr.Nip88.KIND_POLL) {
//            eventRepo.requestPollVotes(event.id)
//        }
//        if (event != null && event.kind == com.wisp.app.nostr.Nip69.KIND_ZAP_POLL) {
//            eventRepo.requestZapPollVotes(event.id)
//        }
//    }

    if (localQuotedNostrEvent != null && noteActions != null) {
        // Full rendering with all interactive features

        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.space75)
        ) {
            localQuotedNostrEvent.RenderNotePreview(
                onNavigateToEvent = onNavigateToEvent
            )
//            quotedNostrEvent.RenderNotePreview()
        }
    } else if (localQuotedNostrEvent != null) {
        // Simple fallback rendering (no noteActions available)
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    Modifier.minimumInteractiveComponentSize()
                        .clickable { effectiveNoteClick(localQuotedNostrEvent.nostrEvent.id) }
                )
        ) {
            localQuotedNostrEvent.RenderNotePreview(
                onNavigateToEvent = onNavigateToEvent
            )
        }
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

@ConformancePreviews
@Composable
private fun QuotedNotePreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {

            QuotedNote(
                localQuotedNostrEvent = com.machankura.dedun.compose.database.model.intermdiate.LocalQuotedNostrEvent(
                    nostrEvent = com.machankura.dedun.compose.database.model.NostrEvent(
                        id = "1c7213118d4e17c6bad5c6b11125f005619bc3e43374affb9cd6483f665fd5e6",
                        content = "God is not in the habit of coming down from heaven to solve people’s problems on earth.",
                        pubKey = "e5e3ad0272d1fbeead8e439a4ad3453374ca4969c8d2be84025ba4ef8ae5a522",
                        kind = 0,
                        sig = "hexsig",
                        tags = emptyArray()
                    ),
                    quotedRelation = com.machankura.dedun.compose.database.model.QuotedRelation(
                        quotingNostrEventId = "e5e3ad0272d1fbeead8e439a4ad3453374ca4969c8d2be84025ba4ef8ae5a520",
                        quotedProfilePublicKey = "e5e3ad0272d1fbeead8e439a4ad3453374ca4969c8d2be84025ba4ef8ae5a522",
                        quotedNostrEventId = "1c7213118d4e17c6bad5c6b11125f005619bc3e43374affb9cd6483f665fd5e6"
                    ),
                    profile = com.machankura.dedun.compose.database.model.Profile(
                        publicKey = "e5e3ad0272d1fbeead8e439a4ad3453374ca4969c8d2be84025ba4ef8ae5a522",
                        nostrEventId = "referenced",
                        displayName = "Steve Biko"
                    ),
                ),
                onNavigateToEvent = { },
                noteActions = null
            )
        }
    }

}