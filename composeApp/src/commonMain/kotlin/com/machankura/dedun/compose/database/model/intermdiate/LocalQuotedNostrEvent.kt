package com.machankura.dedun.compose.database.model.intermdiate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room3.Embedded
import androidx.room3.Relation
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.QuotedRelation
import com.machankura.dedun.compose.ui.composable.widgets.content.RichContent
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

data class LocalQuotedNostrEvent(
    @Embedded val quotedRelation: com.machankura.dedun.compose.database.model.QuotedRelation,

    @Relation(
        parentColumns = ["quotedNostrEventId"],
        entityColumns = ["id"]
    )
    val nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent,

    @Relation(
        parentColumns = ["quotedProfilePublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.machankura.dedun.compose.database.model.Profile?,

    @Relation(
        entity = com.machankura.dedun.compose.database.model.Mention::class,
        parentColumns = ["quotedNostrEventId"],
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
                modifier = Modifier.fillMaxWidth().padding(10.dp)
//                    .clickable(
//                    onClick = {
//                        onNavigateToEvent.invoke(profile.nostrEventId)
//                    }
//                )
                ,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAvatar(
                    publicKey = nostrEvent.pubKey,
                    profile = profile
                )

                Column(
                    modifier = Modifier.weight(1f)
                ){
                    Text(
                        text = profile?.humanReadableNameOrPubkey() ?: nostrEvent.pubKey,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    profile?.staticIdentifier()?.let { staticIdentifier ->
                        Text(
                            text = staticIdentifier,
                            style = MaterialTheme.typography.bodySmall,
                            color = com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileColor.fromProfile(profile)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = 10.dp
                )
            ) {
                RichContent(
                    nostrEvent = nostrEvent,
                    localQuotedNostrEvent = null,
                    mentionedProfiles = mentionedProfiles,
                    onNavigateToEvent = onNavigateToEvent
                )
            }

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

@ConformancePreviews
@Composable
private fun LocalQuotedNostrEventPreview() {
    DedunTheme {
        Surface(
            modifier = Modifier.padding(20.dp)
        ) {
            LocalQuotedNostrEvent(
                nostrEvent = NostrEvent(
                    id = "1c7213118d4e17c6bad5c6b11125f005619bc3e43374affb9cd6483f665fd5e6",
                    content = "God is not in the habit of coming down from heaven to solve people’s problems on earth.",
                    pubKey = "e5e3ad0272d1fbeead8e439a4ad3453374ca4969c8d2be84025ba4ef8ae5a522",
                    kind = 0,
                    sig = "hexsig",
                    tags = emptyArray()
                ),
                quotedRelation = QuotedRelation(
                    quotingNostrEventId = "e5e3ad0272d1fbeead8e439a4ad3453374ca4969c8d2be84025ba4ef8ae5a520",
                    quotedProfilePublicKey = "e5e3ad0272d1fbeead8e439a4ad3453374ca4969c8d2be84025ba4ef8ae5a522",
                    quotedNostrEventId = "1c7213118d4e17c6bad5c6b11125f005619bc3e43374affb9cd6483f665fd5e6"
                ),
                profile = Profile(
                    publicKey = "e5e3ad0272d1fbeead8e439a4ad3453374ca4969c8d2be84025ba4ef8ae5a522",
                    nostrEventId = "referenced",
                    displayName = "Steve Biko"
                ),
            ).RenderNotePreview(
                onNavigateToEvent = {}
            )
        }
    }
}