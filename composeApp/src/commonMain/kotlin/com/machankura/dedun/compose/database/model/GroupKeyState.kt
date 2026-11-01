package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.machankura.dedun.compose.managers.SharedKeyDerivation

/**
 * Which shared key a room signs with, as the group itself has said.
 *
 * Written from a `GroupKeyStateEvent` -- the room's own statement of the
 * ceremony behind it, signed by the group's threshold key -- and read when a
 * signing request arrives, to pick the secret share out of the right
 * [DkgSession]. A device that took part in more than one ceremony holds more
 * than one share, and they are not interchangeable: a partial signature made
 * with the wrong one cannot aggregate.
 *
 * The row appears as the room does. The group agrees the state before the room
 * exists -- in the NIP-17 room its ceremony ran in -- and every device that took
 * part files it the moment it has a room to file it against, whether it created
 * the room or was handed it by a Welcome. See `GroupKeyStateManager.adopt`.
 *
 * A device that was not in the ceremony holds no state and signing resolves its
 * key by rederiving, which is what every room did before this table existed.
 *
 * ### Why this is a row and not a rederivation
 *
 * `FrostSigningManager.completedKey` found the key by walking every ceremony
 * this device holds a share for and rederiving each one's room id until one
 * matched. That works, and it stays as the fallback for rooms made before this
 * table existed, but it can only find rooms derived at the *default* path --
 * the one path the constant names. A room derived anywhere else was invisible
 * to it. [derivationPath] is what fixes that, which is also the reason the path
 * is stored rather than assumed.
 *
 * ### Nothing secret lives here
 *
 * [thresholdPublicKey] is the key signatures verify against, not the secret
 * behind it, and [dkgSessionId] is a pointer. The share itself never leaves
 * `DkgSession.secretShare` on the device that generated it.
 *
 * One row per room: a room is derived from one key, and a group that re-runs
 * its ceremony derives a different room rather than re-keying this one.
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
        Index("dkgSessionId"),
    ],
)
data class GroupKeyState(
    /** The Marmot room this is the key state for. Its id is the derived key. */
    @PrimaryKey
    val chatRoomId: String,

    /**
     * The ceremony that made the key, and so the row holding this device's
     * share of it.
     *
     * Not a foreign key on purpose. A member can be in the room without
     * holding a share -- they were added after the ceremony, or reinstalled --
     * and the state is still worth keeping: it says what the room signs with,
     * which is what tells them they cannot.
     */
    val dkgSessionId: String,

    /** The group's ChillDKG threshold public key, 33-byte compressed hex. */
    val thresholdPublicKey: HexKey,

    /** The path [chatRoomId] was derived at, `m/9420/0/0` style. */
    val derivationPath: String,

    /**
     * Who said it: the group's own nostr identity, since only a quorum can put
     * a state on the record. Kept for the transcript -- the derivation below and
     * the signature on the event are what vouch for it.
     */
    val announcedBy: HexKey,

    /** The signed event's own timestamp, so the newest state per room wins. */
    val announcedAt: Instant,

    /**
     * The group this one is a subgroup of, or null for a group that is nobody's
     * child -- which is every group made before subgroups existed.
     *
     * **Not a foreign key, and that is deliberate.** The three tables around this
     * one cascade off `ChatRoom`, and a self-referential cascade here would mean
     * deleting a parent room deletes every subgroup's state beneath it. It could
     * not be `RESTRICT` either: this routinely names a room the device does not
     * have at all -- a member of a subgroup who was never in its parent holds the
     * id and nothing else -- so a dangling value is the normal state and readers
     * resolve it with a lookup allowed to return null.
     *
     * Written only from a state whose [birthCertificateJson] passed
     * `SubgroupBirthCertificateEvent.certifies`, so a row carrying this is a row
     * where the parent's quorum really did sign for this room. See
     * `GroupKeyStateManager.stateFrom`, which drops the whole state rather than
     * keeping a parentage it could not check.
     */
    val parentChatRoomId: HexKey? = null,

    /**
     * The parent's signed certificate, whole, as JSON -- or null when this group
     * has no parent.
     *
     * The claim itself, where [parentChatRoomId] is only an index into it. Kept
     * as the event rather than as its signature so that a reader can check it
     * again from the row alone: a signature plus a rule for rebuilding the event
     * it covers is a rule that breaks silently the first time the certificate's
     * shape changes, and a rebuild differing by one byte hashes to an id whose
     * signature fails and is indistinguishable from a forgery.
     *
     * Always set together with [parentChatRoomId]. One without the other is a
     * state that never reached this table.
     */
    val birthCertificateJson: String? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
): TimestampedEntity, LocalStoreEntity {
    /** [derivationPath] as indices, or null if it is not a walkable path. */
    fun pathIndices(): List<Long>? = SharedKeyDerivation.parsePathString(derivationPath)

    /**
     * Whether this state actually describes the room it claims to.
     *
     * The room's id is the threshold key derived at the path, so a state that
     * does not rederive its own room points the room at a key it was not made
     * from. Checked before the row is written and cheap enough to check again.
     *
     * Half the trust model, and the half that does not care who is speaking. The
     * other half is `GroupKeyStateEvent.isSignedByGroup`, which asks whether the
     * group said it rather than whether it is true.
     */
    fun verifies(): Boolean {
        val path = pathIndices() ?: return false

        return runCatching {
            SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path) == chatRoomId
        }.getOrDefault(false)
    }
}
