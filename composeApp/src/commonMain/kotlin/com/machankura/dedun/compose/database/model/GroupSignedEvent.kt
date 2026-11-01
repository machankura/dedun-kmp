package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent

/**
 * An event the group's own key put a signature to, kept as the group made it.
 *
 * A quorum signing something is the most expensive thing this app does and,
 * until this table, the least recorded: `FrostSigningManager.complete` verified
 * the signature, handed the event to `ChatMessage.applyInnerEvent`, and let it
 * go. What survived was whatever the row it became happened to keep -- an
 * artifact keeps its `signature`, a contributor list keeps nothing at all, and a
 * kind with no arm in `applyInnerEvent` keeps nothing anywhere. The signature is
 * the group's statement; the rows are one reading of it. This is where the
 * statement itself lives.
 *
 * ### Why the whole event, and not a pointer to one
 *
 * There is nothing to point at. A group-signed event never travels between
 * members as itself -- the outbound pipeline re-authors rumors as their sender
 * and would strip the signature off -- so it is not a `NostrEvent`, and it is not
 * a `MarmotInnerEvent` either, since no member sent it. Every device derives it
 * from a signing session it took part in, or is handed it in a chronicle. The
 * columns are `NostrEvent`'s so that what is stored is an event rather than a
 * summary of one, which is what makes [verifies] answerable from the row alone.
 *
 * ### The derivation path
 *
 * [publicKey] is the group's threshold key walked to [derivationPath], and for a
 * room that walk is also [chatRoomId] -- see `docs/shared-key-derivation.md`,
 * where the room's id and the key it signs as are one value. Storing the path is
 * what lets a reader get from a signature back to the ceremony behind it: the
 * threshold key alone does not say which of a group's rooms signed, and a room
 * id alone cannot be walked backwards. `GroupKeyState` records the same path for
 * the room; this records it for the event, so an event stays checkable after the
 * room's state is gone or was never known.
 *
 * Null means the untweaked threshold key itself, which is what a room not
 * derived from the key signs as -- the same meaning it carries on
 * [FrostSigningSession.derivationPath], which is where it is copied from.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("chatRoomId"),
        Index("kind"),
        Index("frostSigningSessionId"),
    ],
)
data class GroupSignedEvent(
    /** The event id, which is the 32 bytes the group actually signed. */
    @PrimaryKey
    val id: HexKey,

    /**
     * The room the signature was made in. Its id *is* [publicKey] for a derived
     * room, which is every room but one.
     *
     * The exception is a group's first signature: its `GroupKeyStateEvent` is
     * signed in the NIP-17 room the ceremony ran in, and is authored by the
     * admin room that does not exist yet -- so this names where it happened
     * and [publicKey] names who signed. [verifies] cannot pass on that row, for
     * the reason it gives.
     */
    val chatRoomId: String,

    /** The author: the group's key at [derivationPath], x-only 32-byte hex. */
    val publicKey: HexKey,

    val kind: Kind,

    val tags: Array<Array<String>>,

    val content: String,

    /** The finished 64-byte BIP-340 signature over [id], hex. */
    val signature: HexKey,

    /**
     * The path [publicKey] was derived at off the ceremony's threshold key,
     * `m/9420/0/0` style, or null for the untweaked key itself.
     */
    val derivationPath: String? = null,

    /**
     * The session that produced the signature, when this device ran it.
     *
     * Not a foreign key, and null on a device that was handed the event rather
     * than signing it -- a chronicle recipient holds the group's work without
     * ever having held a session for it, which is the whole point of
     * `docs/member-chronicle.md`. A session is also allowed to be swept away
     * without taking the group's signed work with it.
     */
    val frostSigningSessionId: String? = null,

    /** The event's own `created_at`, which is part of what was signed. */
    override val createdAt: Instant,
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = Clock.System.now(),
): TimestampedEntity, LocalStoreEntity {

    /** The signed event, rebuilt from this row exactly as the group signed it. */
    fun toEvent(): Event = Event(
        id = id,
        pubKey = publicKey,
        createdAt = createdAt.epochSeconds,
        kind = kind,
        tags = tags,
        content = content,
        sig = signature,
    )

    /** [derivationPath] as indices, and an empty list when there is none. */
    fun pathIndices(): List<Long> =
        derivationPath?.let { SharedKeyDerivation.parsePathString(it) } ?: emptyList()

    /**
     * Whether the room this row names actually signed what the row holds.
     *
     * Everything needed is on the row, which is the property worth having: the
     * author has to be the room, [id] has to be the hash of the fields sitting
     * next to it, and [signature] has to verify -- no ceremony, key state or
     * derivation path to consult first.
     *
     * **False does not always mean the row is corrupt.** The usual reading is
     * that its columns have drifted from the event they came from, and that is
     * the case worth acting on. Two kinds of row fail honestly.
     *
     * A room that was never derived from its group's key -- [derivationPath]
     * null, the legacy shape `signingPath` documents -- signs as the bare
     * threshold key rather than as its own id, so a perfectly good event in such
     * a room fails here and cannot be made to pass: the key it would have to be
     * checked against is not on the row and cannot be walked back to from one.
     * Those rooms get an empty chronicle for the same reason, which is a limit
     * of the derivation rather than of this check.
     *
     * And a group's first signature is made in the NIP-17 room its ceremony ran
     * in, about the admin room it is about to create, so [chatRoomId] is where
     * it happened and [publicKey] is who signed. Check that one with
     * `GroupKeyStateEvent.isSignedByGroup`, which asks the question this row
     * cannot: whether the *named* room signed it.
     */
    fun verifies(): Boolean = GroupKeyStateEvent.isSignedByRoom(toEvent(), chatRoomId)

    companion object {
        /**
         * The row for a signed [event], as the group made it.
         *
         * Not verified here. Both callers have already checked the signature --
         * `FrostSigningManager` before it applies a batch, `ChronicleManager`
         * before it applies a payload -- and a check that runs twice tends to
         * become a check nobody performs. [verifies] is for readers.
         */
        fun fromEvent(
            event: Event,
            chatRoomId: String,
            derivationPath: String? = null,
            frostSigningSessionId: String? = null,
        ): GroupSignedEvent = GroupSignedEvent(
            id = event.id,
            chatRoomId = chatRoomId,
            publicKey = event.pubKey,
            kind = event.kind,
            tags = event.tags,
            content = event.content,
            signature = event.sig,
            derivationPath = derivationPath,
            frostSigningSessionId = frostSigningSessionId,
            createdAt = Instant.fromEpochSeconds(event.createdAt),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as GroupSignedEvent

        if (id != other.id) return false
        if (chatRoomId != other.chatRoomId) return false
        if (publicKey != other.publicKey) return false
        if (kind != other.kind) return false
        if (!tags.contentDeepEquals(other.tags)) return false
        if (content != other.content) return false
        if (signature != other.signature) return false
        if (derivationPath != other.derivationPath) return false
        if (frostSigningSessionId != other.frostSigningSessionId) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (savedAt != other.savedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + chatRoomId.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + kind
        result = 31 * result + tags.contentDeepHashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + signature.hashCode()
        result = 31 * result + (derivationPath?.hashCode() ?: 0)
        result = 31 * result + (frostSigningSessionId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + savedAt.hashCode()
        return result
    }
}
