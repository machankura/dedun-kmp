package com.machankura.dedun.compose.database.model

import com.machankura.dedun.compose.nostr.DisappearingMessages
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.nostr.groupdm.EcdhPurpose

/*
 * A group's direct messages, by threshold ECDH (docs/group-threshold-ecdh.md, Phase 4).
 *
 * None of this writes a GiftWrapMessage, GiftWrapPayload or NostrEvent row: the group's mail
 * is its own pipeline (D9), and the personal one is built around one identity.
 */

/** Where a round has got to. */
enum class GroupEcdhRoundState { OPEN, COMBINED, FAILED }

/**
 * One point the admins are multiplying their shares by: `(room, generation, P)`, its id a hash
 * of the three, so two devices that open a round for the same wrap open the same round (D11).
 */
@Entity(
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId"), Index("point")],
)
data class GroupEcdhRound(
    @PrimaryKey
    val id: HexKey,
    val chatRoomId: HexKey,
    val generation: Int,
    /** The x-only key multiplied. */
    val point: HexKey,
    val purpose: EcdhPurpose,
    /** A wrap or a seal is named by the wrap's id. */
    val ref: HexKey? = null,
    /** A recipient's evidence, as JSON: the only evidence that travels whole. */
    val evidenceJson: String? = null,
    val state: GroupEcdhRoundState = GroupEcdhRoundState.OPEN,
    /** When this device's own partial went out; null while it is owed or not yet answerable. */
    val partialSentAt: Instant? = null,
    val openedAt: Instant = Clock.System.now(),
    val combinedAt: Instant? = null,
)

/** One holder's partial for a round, once per participant id: redelivery is a no-op. */
@Entity(
    primaryKeys = ["roundId", "participantId"],
    foreignKeys = [
        ForeignKey(entity = GroupEcdhRound::class, parentColumns = ["id"], childColumns = ["roundId"], onDelete = ForeignKey.CASCADE)
    ],
)
data class GroupEcdhPartial(
    val roundId: HexKey,
    val participantId: Int,
    val senderPublicKey: HexKey,
    /** `Dᵢ`, compressed. */
    val partial: String,
    val proof: String,
    /** False for a partial whose proof failed: kept so its sender is named once. */
    val verified: Boolean,
    /** When this device published the round's failure naming this participant. */
    val blamedAt: Instant? = null,
    val receivedAt: Instant = Clock.System.now(),
)

/**
 * The conversation key between the group and one correspondent, kept so their next message
 * costs one round instead of two, and writing to them costs none (D6). A secret: wiped with the
 * room's shares, and on this device's member being removed as an admin (D14).
 */
@Entity(
    primaryKeys = ["chatRoomId", "peer"],
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
)
data class GroupConversationKey(
    val chatRoomId: HexKey,
    val peer: HexKey,
    val conversationKey: String,
    val roundId: HexKey,
    val computedAt: Instant = Clock.System.now(),
) {
    override fun toString(): String = "GroupConversationKey(chatRoomId=$chatRoomId, peer=$peer, conversationKey=<secret>)"
}

/** Where a wrap to the group has got to. */
enum class GroupDmWrapState {
    /** Waiting on the admins: its wrap round, or its seal's. */
    WAITING,

    /** Beyond this hour's rate cap; opened newest first when there is room (Phase 7). */
    QUEUED,
    OPENED,

    /** The combined key did not open it, or what was inside was not a seal with a rumor. */
    UNREADABLE,
}

/** The ledger of wraps addressed to the group's npub, keyed by id: redelivery is a no-op. */
@Entity(
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId"), Index("state")],
)
data class GroupDmWrap(
    @PrimaryKey
    val id: HexKey,
    val chatRoomId: HexKey,
    val wrapJson: String,
    /** The wrap's own created_at, back-dated by up to two days as NIP-59 does. */
    val createdAt: Instant,
    val state: GroupDmWrapState = GroupDmWrapState.WAITING,
    /** The seal, once the wrap round combined: it is the seal round's evidence. */
    val sealJson: String? = null,
    val receivedAt: Instant = Clock.System.now(),
)

/** A message in one of the group's conversations, in or out, keyed by rumor id. */
@Entity(
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId"), Index("conversationId"), Index("expiresAt")],
)
data class GroupDmMessage(
    @PrimaryKey
    val rumorId: HexKey,
    val chatRoomId: HexKey,
    val conversationId: HexKey,
    val senderPublicKey: HexKey,
    val rumorJson: String,
    val createdAt: Instant,
    val outgoing: Boolean,
    /** The wrap it came in, for a message the group was sent. */
    val wrapId: HexKey? = null,
    /** The signing session it went out in, for a message the group sent. */
    val signingSessionId: String? = null,
    val readAt: Instant? = null,
    val filedAt: Instant = Clock.System.now(),
    /** The rumor's NIP-40 expiry, read off [rumorJson]; the sweeper deletes the message from then on. */
    val expiresAt: Instant? = DisappearingMessages.expirationOfRumorJson(rumorJson)?.let { Instant.fromEpochSeconds(it) },
)

/** One recipient's copy of a message the group signed, owed until a relay takes it (D10). */
@Entity(
    primaryKeys = ["signingSessionId", "sealId"],
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId")],
)
data class GroupDmOutbound(
    val signingSessionId: String,
    val sealId: HexKey,
    val chatRoomId: HexKey,
    val recipient: HexKey,
    /** Comma-separated: the recipient's 10050, or the publish set when there is none. */
    val relays: String,
    /** The wrap, built once so every retry sends the same event. */
    val wrapJson: String? = null,
    val publishedAt: Instant? = null,
    val attempts: Int = 0,
    val nextAttemptAt: Instant? = null,
)
