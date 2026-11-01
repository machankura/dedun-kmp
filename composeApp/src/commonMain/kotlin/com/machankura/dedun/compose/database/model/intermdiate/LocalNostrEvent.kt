package com.machankura.dedun.compose.database.model.intermdiate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room3.Embedded
import androidx.room3.Relation
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.composable.widgets.content.RichContent
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar

data class LocalNostrEvent(
    @Embedded val nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent,

    @Relation(
        parentColumns = ["pubKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.machankura.dedun.compose.database.model.Profile?,

    @Relation(
        entity = com.machankura.dedun.compose.database.model.RepostedRelation::class,
        parentColumns = ["id"],
        entityColumns = ["repostingNostrEventId"]
    )
    val localRepostedNostrEvent: LocalRepostedNostrEvent? = null,

    @Relation(
        entity = com.machankura.dedun.compose.database.model.QuotedRelation::class,
        parentColumns = ["id"],
        entityColumns = ["quotingNostrEventId"]
    )
    val localQuotedNostrEvent: LocalQuotedNostrEvent? = null,

    @Relation(
        entity = com.machankura.dedun.compose.database.model.InReplyToRelation::class,
        parentColumns = ["id"],
        entityColumns = ["replyingNostrEventId"]
    )
    val localInReplyToNostrEvent: LocalInReplyToNostrEvent? = null,

    @Relation(
        entity = com.machankura.dedun.compose.database.model.Mention::class,
        parentColumns = ["id"],
        entityColumns = ["mentioningNostrEventId"],
    )
    val mentionedProfiles: List<LocalMention> = emptyList(),
) {
    @Composable
    fun RenderNotePreview(
        onNavigateToEvent: (HexKey) -> Unit
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val profile = profile

                ProfileAvatar(
                    profile = profile,
                    publicKey = nostrEvent.pubKey
                )

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = profile?.displayName ?: profile?.userName ?: nostrEvent.pubKey,
                        style = MaterialTheme.typography.labelLarge
                    )

                    profile?.nip05?.let {
                        Text(
                            text = profile.nip05 ?: "",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            RichContent(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = 20.dp
                ),
                style = MaterialTheme.typography.labelSmall,
                nostrEvent = nostrEvent,
                localQuotedNostrEvent = localQuotedNostrEvent,
                mentionedProfiles = mentionedProfiles,
                onNavigateToEvent = onNavigateToEvent
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = nostrEvent.createdAt.toFormattedTimeAndDateString(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}