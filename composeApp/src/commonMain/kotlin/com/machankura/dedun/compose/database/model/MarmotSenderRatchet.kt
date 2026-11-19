package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * Where this device's own sender ratchet has got to in a current-profile room.
 *
 * Sending an application message advances exactly one thing in an MLS group: this
 * leaf's entry in the secret tree (RFC 9420 §9). The ratchet tree, the group
 * context, the epoch secrets and the path keys are all byte for byte what they
 * were. So the library carries the position as its own small record rather than
 * rewriting the group to hold it -- `MlsGroupStateStore.saveSenderRatchet`, in the
 * spirit of OpenMLS keeping `message_secrets` apart from `group_state` -- and this
 * is where Dedun puts it.
 *
 * It is a safety record, not a cache. A position that is lost re-emits a generation
 * this leaf has already published, which reuses an AEAD key and nonce pair; that is
 * the one failure the record exists to prevent, and it is why the write has to
 * happen before the ciphertext it produced is broadcast. The library makes applying
 * one safe from the other direction: a record is accepted only for the epoch, leaf
 * and secret tree it was taken from, and then only to move a chain *forward*.
 *
 * [record] is the library's own `OwnSenderRatchet.encodeTls()` and is the truth
 * here, opaque the way [MarmotPublishObligation.bytes] is: nothing in this app
 * re-encodes it, so a field the library adds cannot be dropped on the way through.
 * [epoch], [leafIndex] and [applicationGeneration] are that record's own numbers,
 * read off it as it is written and kept beside it to be read -- "where does this
 * device think its ratchet is" is the first question a lost message asks, and a blob
 * answers nothing.
 *
 * One row per room: the manager keeps one position per group, and writes it under
 * the lock it encrypts under, so the row only ever moves forward.
 *
 * See docs/marmot-kmp.md, Phase 8, and docs/mls-skipped-keys.md.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MarmotSenderRatchet(
    @PrimaryKey
    val chatRoomId: HexKey,
    val epoch: Long,
    val leafIndex: Int,
    val applicationGeneration: Int,
    val record: ByteArray, // TODO: Encrypt this...
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is MarmotSenderRatchet &&
                    chatRoomId == other.chatRoomId &&
                    epoch == other.epoch &&
                    leafIndex == other.leafIndex &&
                    applicationGeneration == other.applicationGeneration &&
                    record.contentEquals(other.record)
            )

    override fun hashCode(): Int {
        var result = chatRoomId.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + leafIndex
        result = 31 * result + applicationGeneration
        result = 31 * result + record.contentHashCode()
        return result
    }
}
