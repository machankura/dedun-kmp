package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Every gift wrap addressed to a group this device keeps an inbox for, opened or not
 * (docs/group-inbox.md, D8).
 *
 * The ledger is what makes redelivery a no-op, and what lets a key that arrives later -- a
 * restore, a ceremony that finished after the mail did -- reopen what is already here. A wrap
 * no held key opens stays with [openedGeneration] null, and is counted on the inbox screen
 * rather than hidden.
 *
 * Never a `GiftWrapMessage`: those belong to the personal inbox, which would file the group's
 * correspondents into the admin's own direct messages.
 */
@Entity(
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId")],
)
data class GroupInboxWrap(
    @PrimaryKey
    val id: HexKey,
    val chatRoomId: String,
    /** The wrap as it arrived, so it can be tried again with a key this device gets later. */
    val wrapJson: String,
    /** The wrap's own `created_at`: back-dated by up to two days, as NIP-59 has it. */
    val wrapCreatedAt: Long,
    /** The inbox key generation that opened it, or null while none has. */
    val openedGeneration: Int? = null,
    val receivedAt: Instant = Clock.System.now(),
)

/**
 * A message in a group's inbox, incoming or outgoing (docs/group-inbox.md, D8 and D10): one row
 * per rumor, however many wraps carried it.
 */
@Entity(
    primaryKeys = ["chatRoomId", "rumorId"],
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId"), Index("conversationId")],
)
data class GroupInboxMessage(
    val chatRoomId: String,
    /** Computed from the rumor, never taken from it. */
    val rumorId: HexKey,
    /** The first wrap it arrived in; null for an outgoing message filed before its copy came back. */
    val wrapId: HexKey?,
    /** [com.machankura.dedun.compose.nostr.inbox.InboxConversation.idOf] over [participants]. */
    val conversationId: String,
    /** Everyone in the conversation but the group, sorted, comma-separated. */
    val participants: String,
    val senderPublicKey: HexKey,
    val kind: Kind,
    /** The rumor as NIP-59 carries it: its id and no signature. */
    val rumorJson: String,
    /** The rumor's own `created_at`, unfuzzed: when the sender says they wrote it. */
    val createdAt: Long,
    /** The inbox key generation that opened it, or sealed it if outgoing. */
    val generation: Int,
    /** Written by the group: a reply. */
    val outgoing: Boolean,
    /** The signing session a reply came from, when this device knows it. */
    val signingSessionId: String? = null,
    val readAt: Instant? = null,
    val receivedAt: Instant = Clock.System.now(),
) {
    fun participantList(): List<HexKey> = participants.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * An event this device owes relays on the group's behalf (docs/group-inbox.md, D7 and D10): a
 * signed 10044 or 10050 the group agreed to, or a gift wrap carrying a reply it signed. Sent with
 * backoff until a relay takes it, as `ConfederationPublisher` sends confederation events.
 *
 * Never a `NostrEvent` row, for the reasons group-nostr-profile-and-posts.md D7 gives: the group's
 * events are not the feed's, and a kind 0 or 10050 there would make a profile of the group.
 */
@Entity(
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId"), Index("signingSessionId")],
)
data class GroupInboxPublication(
    @PrimaryKey
    val eventId: HexKey,
    val chatRoomId: String,
    val kind: Kind,
    val eventJson: String,
    /** Where it goes, comma-separated; the app's publish set is added to these. */
    val relays: String,
    /** The signing session it came from. */
    val signingSessionId: String?,
    /** For a wrap: who it is to. Null for the group's own announcements. */
    val recipientPublicKey: HexKey?,
    val attempts: Int = 0,
    val nextAttemptAt: Instant? = null,
    val publishedAt: Instant? = null,
    val createdAt: Instant = Clock.System.now(),
) {
    fun relayList(): List<String> = relays.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
