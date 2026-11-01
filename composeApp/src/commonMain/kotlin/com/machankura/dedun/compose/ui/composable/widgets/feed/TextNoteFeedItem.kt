package com.machankura.dedun.compose.ui.composable.widgets.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.loading_author_information
import dedun.composeapp.generated.resources.loading_information
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TextNoteFeedItem(
    nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent,
    profile: com.machankura.dedun.compose.database.model.Profile?,
    localInReplyToNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalInReplyToNostrEvent?,
    localQuotedNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalQuotedNostrEvent?,
    mentionedProfiles: List<com.machankura.dedun.compose.database.model.intermdiate.LocalMention>,
    onNavigateToEvent: (HexKey) -> Unit
) {
    val expended = mutableStateOf(false)

    Column {

        if (localInReplyToNostrEvent != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = MaterialTheme.spacing.space250
                ),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "In reply to ",
                    style = MaterialTheme.typography.labelSmall
                )

                Text(
                    modifier = Modifier.minimumInteractiveComponentSize()
                                   .clickable(
                        onClick = {
                            onNavigateToEvent.invoke(localInReplyToNostrEvent.inReplyToRelation.inReplyToNostrEventId)
                        }
                    ),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileColor.fromPublicKey(localInReplyToNostrEvent.nostrEvent.pubKey),
                    text = localInReplyToNostrEvent.profile?.humanReadableNameOrPubkey() ?: stringResource(Res.string.loading_information)
                )
            }
        }

        ElevatedCard(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.space125
            ),
            onClick = {
                onNavigateToEvent.invoke(nostrEvent.id)
            }
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.space125).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (profile != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                        .clickable(
                            onClick = {
                                onNavigateToEvent.invoke(profile.nostrEventId)
                            }
                        ),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125, Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar(
                            publicKey = nostrEvent.pubKey,
                            profile = profile
                        )

                        Column(
                            modifier = Modifier.weight(1f)
                        ){
                            Text(
                                text = profile.humanReadableNameOrPubkey(),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            profile.staticIdentifier()?.let { staticIdentifier ->
                                Text(
                                    text = staticIdentifier,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileColor.fromProfile(profile)
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(Res.string.loading_author_information)
                    )
                }

                com.machankura.dedun.compose.ui.composable.widgets.content.RichContent(
                    modifier = Modifier.fillMaxWidth(),
                    nostrEvent = nostrEvent,
                    localQuotedNostrEvent = localQuotedNostrEvent,
                    mentionedProfiles = mentionedProfiles,
                    onNavigateToEvent = onNavigateToEvent
                )

                if (expended.value) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            MaterialTheme.spacing.space125,
                            alignment = Alignment.Start
                        )
                    ) {
                        item {
                            AssistChip(
                                label = {
                                    Text(
                                        text = nostrEvent.kind.toString() // TODO: Kind by name...
                                    )
                                },
                                onClick = {

                                }
                            )
                        }

                        items(
                            items = nostrEvent.tags,
                        ) { tag ->
                            SuggestionChip(
                                label = {
                                    Text(
                                        text = tag.joinToString(":"),
                                        maxLines = 1
                                    )
                                },
                                onClick = {

                                }
                            )
                        }
                    }
                }


                Text(
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End,
                    text = nostrEvent.createdAt.toFormattedTimeAndDateString()
                )
            }
        }
    }

}

@ConformancePreviews
@Composable
private fun TextNoteFeedItemPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            TextNoteFeedItem(
                nostrEvent = com.machankura.dedun.compose.database.model.NostrEvent(
                    id = "hex",
                    content = "You are either alive and proud or you are dead, and when you are dead, you can't care anyway. https://www.brainyquote.com/authors/steven-biko-quotes",
                    pubKey = "hexpubkeypubkeypubkeypubkey",
                    kind = 0,
                    sig = "hexsig",
                    tags = emptyArray()
                ),
                profile = com.machankura.dedun.compose.database.model.Profile(
                    publicKey = "",
                    nostrEventId = "referenced",
                    displayName = "Frank Talk",
                    nip05 = "frank@talk.at"
                ),
                localQuotedNostrEvent = null,
                localInReplyToNostrEvent = null,
                mentionedProfiles = emptyList(),
                onNavigateToEvent = {}
            )
        }
    }
}

@ConformancePreviews
@Composable
private fun TextNoteFeedItemInReplyToPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            TextNoteFeedItem(
                nostrEvent = com.machankura.dedun.compose.database.model.NostrEvent(
                    id = "hex",
                    content = "It is better to die for an idea that will live, than to live for an idea that will die",
                    pubKey = "hexpubkeypubkeypubkeypubkey",
                    kind = 0,
                    sig = "hexsig",
                    tags = emptyArray()
                ),
                profile = com.machankura.dedun.compose.database.model.Profile(
                    publicKey = "",
                    nostrEventId = "referenced",
                    displayName = "Frank Talk",
                    nip05 = "frank@talk.at"
                ),
                localQuotedNostrEvent = null,
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
                        pubKey = "hexie",
                        kind = 0,
                        sig = "hexsig",
                        tags = emptyArray()
                    ),
                    profile = com.machankura.dedun.compose.database.model.Profile(
                        publicKey = "hesd",
                        nostrEventId = "referenced",
                        displayName = "Steve Biko",
                        nip05 = "steve@bcm.co.za"
                    ),
                    mentionedProfiles = emptyList()
                ),
                mentionedProfiles = emptyList(),
                onNavigateToEvent = {}
            )
        }
    }
}

@ConformancePreviews
@Composable
private fun TextNoteFeedItemQuoteNostrEventPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            TextNoteFeedItem(
                nostrEvent = com.machankura.dedun.compose.database.model.NostrEvent(
                    id = "hex",
                    content = "The revolutionary sees his task as liberation not only of the oppressed but also of the oppressor. Happiness can never truly exist in a state of tension.\n" +
                            "Read more at nostr:npub1uh366qnj68a7atvwgwdy4569xd6v5jtferftapqztwjwlzh9553q47pqsx\nnostr:nevent1qqspcusnzxx5u97xht2udvg3yhcq2cvmc0jrxa90lwwdvjplve0atespzpmhxue69uhkummnw3ezuamfdejsyg8h5g639qhuv803hzwexfwhyud6t6suxfu364anddqvrmn9j4cahvxksr8q",
                    pubKey = "hexpubkeypubkeypubkeypubkey",
                    kind = 0,
                    sig = "hexsig",
                    tags = emptyArray()
                ),
                profile = com.machankura.dedun.compose.database.model.Profile(
                    publicKey = "",
                    nostrEventId = "referenced",
                    displayName = "Frank Talk",
                    nip05 = "frank@talk.at"
                ),
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
                        pubKey = "hexie",
                        kind = 0,
                        sig = "hexsig",
                        tags = emptyArray()
                    ),
                    profile = com.machankura.dedun.compose.database.model.Profile(
                        publicKey = "hesd",
                        nostrEventId = "referenced",
                        displayName = "Steve Biko",
                        nip05 = "steve@bcm.co.za"
                    ),
                    mentionedProfiles = emptyList()
                ),
                mentionedProfiles = listOf(
                    com.machankura.dedun.compose.database.model.intermdiate.LocalMention(
                        mention = com.machankura.dedun.compose.database.model.Mention(
                            mentioningNostrEventId = "000067b3f15cac88ba066b28c370083a4fc80dacc054e554ab1dc0def974c89c",
                            mentionedPublicKey = "e5e3ad0272d1fbeead8e439a4ad3453374ca4969c8d2be84025ba4ef8ae5a522"
                        ),
                        profile = com.machankura.dedun.compose.database.model.Profile(
                            publicKey = "e5e3ad0272d1fbeead8e439a4ad3453374ca4969c8d2be84025ba4ef8ae5a522",
                            nostrEventId = "referenced",
                            displayName = "Steve Biko",
                            nip05 = "steve@bcm.co.za"
                        ),
                    )
                ),
                onNavigateToEvent = {}
            )
        }
    }
}