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
 * One device's part in a group's inbox key ceremony (docs/group-inbox.md, D2 and D3): ChillDKG
 * at threshold 1 over the room's admins.
 *
 * Kept apart from [DkgSession] on purpose. Every completed `DkgSession` is a key the signing
 * path may find, and a threshold-1 key there would be a key any one admin could sign as the
 * group with. Nothing that looks for a signing key reads this table.
 *
 * Inputs, never protocol state, as `DkgSession` keeps them: every step is a pure function of
 * the randomness here and the messages in [InboxKeyCeremonyMessage], so a device that dies
 * between rounds recomputes the step on the next message. The secret it produces is not kept
 * here but in [InboxKey], once, when the ceremony completes.
 */
@Entity(
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId")],
)
data class InboxKeyCeremony(
    @PrimaryKey
    val id: String,
    /** The group's Marmot room: the ceremony runs in it, and its admins are the participants. */
    val chatRoomId: String,
    /** Which generation of the room's inbox key this ceremony makes. */
    val generation: Int,
    val coordinatorPublicKey: HexKey,
    /** The identity on this device taking part, or watching. */
    val userPublicKey: HexKey,
    /** The admins the proposal named, sorted, comma-separated. */
    val participantPublicKeys: String,
    val stage: InboxKeyCeremonyStage = InboxKeyCeremonyStage.COLLECTING_HOST_KEYS,
    /** Null on the device of a member who is not a participant: it only watches. */
    val hostPublicKey: HexKey? = null,
    val round1Random: HexKey? = null,
    val round2AuxRandom: HexKey? = null,
    /** The ceremony's public key `E`, x-only, once it is certified. */
    val inboxPublicKey: HexKey? = null,
    val failureReason: String? = null,
    /** Whose fault a failure was, when ChillDKG could say. */
    val culprit: HexKey? = null,
    /** When the last message arrived: what "waiting since" is measured from. */
    val lastMessageAt: Instant = Clock.System.now(),
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = createdAt,
) {
    val isRunning: Boolean
        get() = stage != InboxKeyCeremonyStage.COMPLETE && stage != InboxKeyCeremonyStage.FAILED

    fun isCoordinator(): Boolean = coordinatorPublicKey == userPublicKey

    fun participants(): List<HexKey> = participantPublicKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun isParticipant(): Boolean = userPublicKey in participants()

    companion object {
        fun formatParticipants(publicKeys: Collection<HexKey>): String = publicKeys.distinct().sorted().joinToString(",")
    }
}

/** Where an inbox key ceremony has got to, as a label for screens. */
enum class InboxKeyCeremonyStage {
    COLLECTING_HOST_KEYS,
    ROUND_1,
    ROUND_2,
    COMPLETE,
    FAILED,
}

/** One message an inbox key ceremony has received or sent, written once per sender and kind. */
@Entity(
    primaryKeys = ["sessionId", "senderPublicKey", "kind"],
    foreignKeys = [
        ForeignKey(entity = InboxKeyCeremony::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)
    ],
)
data class InboxKeyCeremonyMessage(
    val sessionId: String,
    val senderPublicKey: HexKey,
    val kind: Kind,
    val payload: String,
    val receivedAt: Instant = Clock.System.now(),
)

/**
 * One generation of a group's inbox key, as this device holds it (docs/group-inbox.md, D14).
 *
 * [secretKey] is the whole secret `e`: every admin holds it, which is the point of it. It is
 * kept as a share is kept, and wiped as a share is wiped -- [state] goes to [InboxKeyState.WIPED]
 * and the secret to null, while [publicKey] stays so the device can still say which key it was.
 */
@Entity(
    primaryKeys = ["chatRoomId", "userPublicKey", "generation"],
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId")],
)
data class InboxKey(
    val chatRoomId: String,
    val userPublicKey: HexKey,
    val generation: Int,
    /** `E`, x-only hex: what the group's 10044 names. */
    val publicKey: HexKey,
    /** `e`, hex; null once wiped. */
    val secretKey: HexKey?,
    /** ChillDKG's recovery data: with the host secret from the nsec, it restores [secretKey]. */
    val recoveryData: HexKey?,
    val ceremonyId: String,
    val state: InboxKeyState = InboxKeyState.HELD,
    /** The signed 10044 that announced this generation, once there is one. */
    val announcementEventId: HexKey? = null,
    val createdAt: Instant = Clock.System.now(),
    val announcedAt: Instant? = null,
    val wipedAt: Instant? = null,
)

enum class InboxKeyState {
    /** Made, not yet announced: nobody has been told to write to it. */
    HELD,
    /** The key the group's newest signed 10044 names: replies are sealed from it. */
    ANNOUNCED,
    /** Replaced by a later generation: used to open, never to seal. */
    RETIRED,
    /** Gone from this device: the admin was removed, or the device asked to forget. */
    WIPED,
}
