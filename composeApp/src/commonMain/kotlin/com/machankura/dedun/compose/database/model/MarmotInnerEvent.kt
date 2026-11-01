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
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.machankura.dedun.compose.managers.retentionSeconds
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = MarmotGroupEvent::class,
            parentColumns = ["id"],
            childColumns = ["marmotGroupEventId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class MarmotInnerEvent( // TODO: Rename this to GiftWrapPayload...
    @PrimaryKey
    val id: HexKey,

    /**
     * Sender Public Key
     */
    val publicKey: HexKey,

    val chatRoomId: String,

    /**
     * Payload Event Kind
     */
    val kind: Kind = ChatEvent.KIND,

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
     * Associated MarmotGroupEvent
     */
    val marmotGroupEventId: String? = null,

    /**
     * Who this is a direct message to, or null for an ordinary group message.
     *
     * Set on the way out only. It is what tells the notary to gift wrap this row for one
     * member instead of sending it to the whole group, and afterwards what tells the
     * transcript the row was private. Inbound rows leave it null -- the recipient is on
     * the wrap's `p` tag, which is where every member reads it from.
     *
     * See docs/marmot-direct-messages.md.
     */
    val directMessageRecipientPublicKey: HexKey? = null,

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


    companion object {
        const val TAG = "MarmotInnerEvent"

        /**
         * Compute the NIP-40 expiration timestamp for outbound application messages,
         * or `null` when the group does not have disappearing messages configured.
         *
         * When the group carries a retention -- MIP-01's `disappearing_message_secs`
         * in the group data extension, or the current profile's message-retention
         * component -- clients MUST auto-apply an `expiration` tag on kind:445
         * events at `created_at + disappearing_message_secs`. Read through
         * [retentionSeconds] so both profiles count: reading only the first is how
         * the library's own outbound processor once dropped the tag on every
         * current-profile group, and disappearing messages simply did not.
         */
        fun disappearingExpiration(
            group: MlsGroup,
            createdAt: Long,
        ): Long? {
            val secs = group.retentionSeconds() ?: return null
            return createdAt + secs
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MarmotInnerEvent

        if (id != other.id) return false
        if (kind != other.kind) return false
        if (publicKey != other.publicKey) return false
        if (chatRoomId != other.chatRoomId) return false
        if (!tags.contentDeepEquals(other.tags)) return false
        if (content != other.content) return false
        if (quotedEventId != other.quotedEventId) return false
        if (marmotGroupEventId != other.marmotGroupEventId) return false
        if (directMessageRecipientPublicKey != other.directMessageRecipientPublicKey) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (savedAt != other.savedAt) return false
        if (viewedAt != other.viewedAt) return false
        if (deletedAt != other.deletedAt) return false
        if (logger != other.logger) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kind
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + chatRoomId.hashCode()
        result = 31 * result + tags.contentDeepHashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (quotedEventId?.hashCode() ?: 0)
        result = 31 * result + (marmotGroupEventId?.hashCode() ?: 0)
        result = 31 * result + (directMessageRecipientPublicKey?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + savedAt.hashCode()
        result = 31 * result + (viewedAt?.hashCode() ?: 0)
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + logger.hashCode()
        return result
    }
}