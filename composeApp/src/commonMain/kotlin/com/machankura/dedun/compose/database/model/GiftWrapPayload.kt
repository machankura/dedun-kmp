package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Ignore
import androidx.room3.PrimaryKey
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.SoftDeletableEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.machankura.dedun.compose.database.model.traits.UserViewableEntity
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUsers
import com.vitorpamplona.quartz.nip14Subject.subject
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = GiftWrapSeal::class,
            parentColumns = ["id"],
            childColumns = ["giftWrapSealId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class GiftWrapPayload(
    @PrimaryKey
    val id: HexKey,

    /**
     * Sender Public Key
     */
    val publicKey: String,

    val chatRoomId: String,

    /**`
     * Payload Event Kind
     */
    val kind: Kind,

    /**
     * Tags for the unsigned giftwrap
     *
     * [
     *     ["p", "<receiver-1-pubkey>", "<relay-url>"],
     *     ["p", "<receiver-2-pubkey>", "<relay-url>"],
     *     ["e", "<kind-14-id>", "<relay-url>"] // if this is a reply
     *     ["subject", "<conversation-title>"],
     *     // rest of tags...
     *   ]
     *
     *   q tags MAY be used when citing events in the .content with NIP-21.
     */
    val tags: Array<Array<String>>,

    /**
     * content MUST be plain text. Fields id and created_at are required.
     */
    val content: String,
    val quotedEventId: String? = null,

    /**
     * Associated GiftWrapSeal
     */
    val giftWrapSealId: String? = null,

    /**
     * Current time
     */
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = Clock.System.now(),
    override val viewedAt: Instant? = null,
    override val deletedAt: Instant? = null, // Who this message should be seen by (used to derive conversationId)
): TimestampedEntity, LocalStoreEntity, UserViewableEntity, SoftDeletableEntity {
    @Ignore
    private val logger = Logger.withTag(TAG)

    fun aggregatedParticipantsPublicKey(): String? = try {
        logger.d("aggregateParticipantsPublicKeys")

        val hexKeySet = participantPTags()

        return ChatRoom.deriveChatRoomId(
            hexKeySet.map { it.pubKey }.toSet()
        )
    } catch (e: Throwable) {
        logger.e("Failed to aggregate participants publicKeys", e)
        return null
    }

    fun participantPTags(relayUrl: NormalizedRelayUrl? = null): Set<PTag> = try {
        val pTags = mutableSetOf(
            PTag(
                pubKey = publicKey,
                relayHint = relayUrl
            )
        )

        pTags.addAll(
            tags.taggedUsers().map {
                it
            }
        )

        return pTags
    } catch (e: Throwable) {
        logger.e("Failed to get pTags", e)
        return emptySet()
    }

    fun parseSubject(): String? {
        return tags.subject()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as GiftWrapPayload

        if (kind != other.kind) return false
        if (id != other.id) return false
        if (publicKey != other.publicKey) return false
        if (!tags.contentDeepEquals(other.tags)) return false
        if (content != other.content) return false
        if (quotedEventId != other.quotedEventId) return false
        if (giftWrapSealId != other.giftWrapSealId) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (savedAt != other.savedAt) return false
        if (viewedAt != other.viewedAt) return false
        if (deletedAt != other.deletedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = kind
        result = 31 * result + id.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + tags.contentDeepHashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (quotedEventId?.hashCode() ?: 0)
        result = 31 * result + (giftWrapSealId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + savedAt.hashCode()
        result = 31 * result + (viewedAt?.hashCode() ?: 0)
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val TAG = "GiftWrapPayload"
    }
}