package com.machankura.dedun.compose.ui.composable.widgets.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import kotlin.time.Instant
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.loading_author_information_2
import bennu.composeapp.generated.resources.malformed_note
import bennu.composeapp.generated.resources.reposted
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@Composable
fun com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent.ListView(
    onNavigateToEvent: (HexKey) -> Unit
) {
    when (nostrEvent.kind) {
        RepostEvent.KIND -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacing.space125
                        ).minimumInteractiveComponentSize()
                        .clickable(
                            enabled = true,
                            onClick = {
                                onNavigateToEvent.invoke(profile?.nostrEventId ?: nostrEvent.id)
                            }
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                ) {
                    Icon(
                        modifier = Modifier.size(
                            22.dp
                        ),
                        imageVector = Icons.Default.Repeat,
                        contentDescription = "repost icon",

                    )
                    Text(
                        text = profile?.humanReadableNameOrPubkey() ?: nostrEvent.pubKey,
                        style = MaterialTheme.typography.labelSmall,
                        color = com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileColor.fromPublicKey(profile?.publicKey ?: nostrEvent.pubKey)
                    )
                    Text(
                        text = stringResource(Res.string.reposted),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (localRepostedNostrEvent != null) {
                    TextNoteFeedItem(
                        nostrEvent = localRepostedNostrEvent.nostrEvent,
                        profile = localRepostedNostrEvent.profile,
                        localQuotedNostrEvent = null,
                        localInReplyToNostrEvent = null,
                        mentionedProfiles = localRepostedNostrEvent.mentionedProfiles,
                        onNavigateToEvent = onNavigateToEvent
                    )
                } else {

                    val containedPost = RepostEvent(
                        id = nostrEvent.id,
                        pubKey = nostrEvent.pubKey,
                        createdAt = nostrEvent.createdAt.epochSeconds,
                        content = nostrEvent.content,
                        sig = nostrEvent.sig,
                        tags = nostrEvent.tags
                    ).containedPost()

                    if (containedPost != null) {
                        Text(stringResource(Res.string.loading_author_information_2))
                        Text(
                            containedPost.content
                        )
                        Text(
                            text = Instant.fromEpochSeconds(containedPost.createdAt).toFormattedTimeAndDateString()
                        )
                    } else {
                        Text(stringResource(Res.string.malformed_note))
                    }

                }
            }
        }
        // TODO: Support other kinds...
        else -> {
            TextNoteFeedItem(
                nostrEvent = nostrEvent,
                profile = profile,
                localQuotedNostrEvent = localQuotedNostrEvent,
                localInReplyToNostrEvent = localInReplyToNostrEvent,
                mentionedProfiles = mentionedProfiles,
                onNavigateToEvent = onNavigateToEvent
            )
        }
    }
}


@ConformancePreviews
@Composable
private fun EventListViewRepostedPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent(
                nostrEvent = com.machankura.dedun.compose.database.model.NostrEvent(
                    id = "hex",
                    content = "Reposting",
                    pubKey = "hexpubkeypubkeypubkeypubkey",
                    kind = RepostEvent.KIND,
                    sig = "hexsig",
                    tags = emptyArray()
                ),
                localRepostedNostrEvent = com.machankura.dedun.compose.database.model.intermdiate.LocalRepostedNostrEvent(
                    repostedRelation = com.machankura.dedun.compose.database.model.RepostedRelation(
                        repostingNostrEventId = "hex",
                        repostedNostrEventId = "repostedhex",
                        repostedProfilePublicKey = "reposterHex"
                    ),
                    nostrEvent = com.machankura.dedun.compose.database.model.NostrEvent(
                        id = "hex",
                        content = "Something something something",
                        pubKey = "hexpubkeypubkeypubkeypubkey",
                        kind = 0,
                        sig = "hexsig",
                        tags = emptyArray()
                    ),
                    profile = com.machankura.dedun.compose.database.model.Profile(
                        publicKey = "hesd",
                        nostrEventId = "referenced",
                        displayName = "Frank Talk",
                        nip05 = "frank@talk.at"
                    ),
                    mentionedProfiles = emptyList()
                ),
                profile = com.machankura.dedun.compose.database.model.Profile(
                    publicKey = "",
                    nostrEventId = "referenced",
                    displayName = "Steve Biko",
                    nip05 = "steve@bck.co.za"
                ),
            ).ListView(
                onNavigateToEvent = {}
            )
        }
    }
}

@ConformancePreviews
@Composable
private fun EventListViewInReplyPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent(
                nostrEvent = com.machankura.dedun.compose.database.model.NostrEvent(
                    id = "hex",
                    content = "Reposting",
                    pubKey = "hexpubkeypubkeypubkeypubkey",
                    kind = TextNoteEvent.KIND,
                    sig = "hexsig",
                    tags = emptyArray()
                ),
                localInReplyToNostrEvent = com.machankura.dedun.compose.database.model.intermdiate.LocalInReplyToNostrEvent(
                    inReplyToRelation = com.machankura.dedun.compose.database.model.InReplyToRelation(
                        inReplyToNostrEventId = "nostrEvent",
                        inReplyToRootNostrEventId = "rootId",
                        inReplyToProfilePublicKey = "inReplyToProfileKey",
                        inReplyToRootProfilePublicKey = "rootPubKey",
                        replyingNostrEventId = "hex"
                    ),
                    nostrEvent = com.machankura.dedun.compose.database.model.NostrEvent(
                        id = "hex",
                        content = "Something something something",
                        pubKey = "hexpubkeypubkeypubkeypubkey",
                        kind = 0,
                        sig = "hexsig",
                        tags = emptyArray()
                    ),
                    profile = com.machankura.dedun.compose.database.model.Profile(
                        publicKey = "hesd",
                        nostrEventId = "referenced",
                        displayName = "Frank Talk",
                        nip05 = "frank@talk.at"
                    ),
                    mentionedProfiles = emptyList()
                ),
                profile = com.machankura.dedun.compose.database.model.Profile(
                    publicKey = "",
                    nostrEventId = "referenced",
                    displayName = "Steve Biko",
                    nip05 = "steve@bck.co.za"
                ),
            ).ListView(
                onNavigateToEvent = {}
            )
        }
    }
}