package com.machankura.dedun.compose.database.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip51Lists.relayLists.RelayFeedsListEvent
import com.machankura.dedun.compose.ui.composable.navigation.routes.NostrEventDetailRoute
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileColor
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.database.GENESIS_AT
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = NostrEvent::class,
            parentColumns = ["id"],
            childColumns = ["nostrEventId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("nostrEventId"),
    ],
)
data class Profile(
    @PrimaryKey
    val publicKey: String,
    val userName: String? = null, // name in the nostr event...
    val displayName: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val website: String? = null,
    val about: String? = null,
    val bot: Boolean? = null,
    val pronouns: String? = null,
    val nip05: String? = null,
    val domain: String? = null,
    val lud06: String? = null,
    val lud16: String? = null,
    val twitter: String? = null,

    override val nostrEventId: String,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
    override val savedAt: Instant = Clock.System.now(),
    override val viewedAt: Instant? = null,
    override val deletedAt: Instant? = null,
    override val broadcastedAt: Instant? = null,
): com.machankura.dedun.compose.database.model.traits.NostrEventEntity,
    com.machankura.dedun.compose.database.model.traits.TimestampedEntity,
    com.machankura.dedun.compose.database.model.traits.LocalStoreEntity,
    com.machankura.dedun.compose.database.model.traits.UserViewableEntity,
    com.machankura.dedun.compose.database.model.traits.BroadcastableEntity,
    com.machankura.dedun.compose.database.model.traits.SoftDeletableEntity {
    /**
     * Read off a kind 0, as against the "LOADING..." placeholder minted when a pubkey
     * is first seen (`NostrDao.indexNostrEvent`), which is stamped [GENESIS_AT] so that
     * the two can be told apart. A missing row and a placeholder row mean the same
     * thing to a reader: nobody has read a kind 0 for that pubkey.
     */
    fun isResolved(): Boolean = createdAt > GENESIS_AT

    fun humanReadableNameOrPubkey(): String {
        return if (!displayName.isNullOrBlank()) {
            displayName
        } else if (!userName.isNullOrBlank()) {
            userName
        } else if (!nip05.isNullOrBlank()) {
            nip05
        } else if (!lud16.isNullOrBlank()) {
            lud16
        } else if (!twitter.isNullOrBlank()) {
            twitter
        } else {
            publicKey.take(8)
        }
    }

    fun staticIdentifier(): String? {
        return  if (!nip05.isNullOrBlank()) {
            nip05
        } else if (!lud16.isNullOrBlank()) {
            lud16
        } else {
            null
        }
    }

    suspend fun profilePublicRelays(
        nostrRepository: com.machankura.dedun.compose.repository.NostrRepository
    ): List<NormalizedRelayUrl>? {
        val relays = nostrRepository.getNostrEvent(
            publicKey,
            kind = RelayFeedsListEvent.KIND
        )?.let { relayFeedNostrEvent ->
            val relayFeedEvent = RelayFeedsListEvent(
                id = relayFeedNostrEvent.id,
                pubKey = relayFeedNostrEvent.pubKey,
                content = relayFeedNostrEvent.content,
                tags = relayFeedNostrEvent.tags,
                sig = relayFeedNostrEvent.sig,
                createdAt = relayFeedNostrEvent.createdAt.epochSeconds
            )

            relayFeedEvent.publicRelays()
        }

        if (relays?.isNotEmpty() == true) {
            return relays
        }

        return null
    }

    @Composable
    fun RenderAsListItem(
        activeUserPublicKey: String,
        onNavigateToEvent: (com.machankura.dedun.compose.ui.composable.navigation.routes.Route) -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = 6.dp,
                vertical = 3.dp
            ),
            onClick = {
                onNavigateToEvent.invoke(
                    NostrEventDetailRoute(
                        activeUserPublicKey = activeUserPublicKey,
                        nostrEventId
                    )
                )
            }
        ) {
            ListItem(
                leadingContent = {
                    ProfileAvatar(
                        publicKey = publicKey,
                        profile = this@Profile
                    )
                },
                headlineContent = {
                    Text(
                        text = humanReadableNameOrPubkey(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                supportingContent = {
                    staticIdentifier()?.let { staticIdentifier ->
                        Text(
                            text = staticIdentifier,
                            style = MaterialTheme.typography.labelMedium,
                            color = ProfileColor.fromPublicKey(publicKey)
                        )
                    }
                }
            )
        }
    }
}

@ConformancePreviews
@Composable
fun ProfileRenderAsListItemPreview() {
    val profile = Profile(
        publicKey = "pubKey",
        displayName = "John Doe",
        nip05 = "john@doe.com",
        about = "I am the stone that the builder refused.",
        nostrEventId = "nostrEventId"
    )
    BennuTheme {
        Surface(
            modifier = Modifier.padding(20.dp)
        ) {
            profile.RenderAsListItem(
                activeUserPublicKey = "",
                onNavigateToEvent = {}
            )
        }
    }
}