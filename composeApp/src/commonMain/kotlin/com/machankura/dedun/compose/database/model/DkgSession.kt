package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One ChillDKG ritual, as this device sees it.
 *
 * Deliberately stores inputs rather than protocol state. `ChillDKG`'s
 * participant and coordinator steps are pure functions of their inputs — the
 * only randomness enters through the `random`/`auxRand` arguments — so keeping
 * [round1Random], [round2AuxRandom] and the received messages is enough to
 * recompute every intermediate state on demand. That is what lets a ritual
 * survive the app being killed halfway through, which matters because a DKG
 * needs every member to finish and members close apps.
 *
 * [round1Random] and [round2AuxRandom] are secret: they reconstruct this
 * device's share. They live here under the same "encrypted local storage"
 * assumption `MlsGroupState` already relies on.
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
    ],
)
data class DkgSession(
    /** Minted by the coordinator, carried on every message as `dkg_session`. */
    @PrimaryKey
    val id: String,

    val chatRoomId: String,

    /** The member running the ritual — by construction, the room's creator. */
    val coordinatorPublicKey: HexKey,

    /** Whose device this row belongs to, for multi-account support. */
    val userPublicKey: HexKey,

    /** The `t` of t-of-n, taken from the quorum chosen when the group was made. */
    val threshold: Int,

    /** The `n`: how many members have to show up before params can be built. */
    val participantCount: Int,

    /**
     * Everyone the ceremony is being run with, comma-separated hex, sorted --
     * or null on a row written before this column existed.
     *
     * The room used to answer this. It cannot any more: a subgroup's ceremony
     * runs in the **parent's** room, over a subset of the parent's members, so
     * the room's roster is the wrong set and reading it would put every parent
     * member in the child's admin list. The set is the proposal's p-tags plus
     * its sender, which is the same set every device derives `n` from, so what
     * is stored here cannot disagree with the ceremony it describes.
     *
     * Sorted so two devices that assembled the set in different orders store the
     * same string, which is what lets [DkgSessionDao.getLatestSubgroupSessionFor]
     * match on it.
     *
     * Null falls back to the room, which is right for every row that predates
     * this: a ceremony before subgroups moved ran in a room whose members were
     * exactly its participants.
     */
    val participantPublicKeys: String? = null,

    /**
     * What the room this ceremony's key derives is to be called, as the proposal
     * claimed -- or null for a ceremony that names nothing.
     *
     * Off the same `SubjectTag` the proposal has always carried. It used to land
     * on the ceremony's own NIP-17 room, which was the subgroup's name written
     * somewhere it could be read back; a ceremony held in the parent's room has
     * no room of its own to be named, and the parent's name is somebody else's.
     *
     * Unverified, like [parentChatRoomId] and for the same reason -- it is a
     * claim on a proposal. What checks it is the birth certificate, which the
     * parent's quorum signs over this very name, and which every admin sees
     * before agreeing to it.
     */
    val subject: String? = null,

    /**
     * The wallet policy the coordinator chose for the subgroup this ceremony
     * makes, as `SubgroupWalletPolicy.tagValue` -- or null for a ceremony that
     * makes no subgroup, or one proposed by a build from before policies.
     * `docs/subgroup-wallet-policy.md`.
     *
     * A claim on a proposal, like [subject], and read for two things only: the
     * join screen, which tells an admin what they are agreeing to, and the
     * certificate rung, which proposes the certificate under it. What makes it
     * binding is the parent's quorum signing it into the certificate, and every
     * child signer then refusing a key state whose certificate says otherwise.
     */
    val walletPolicy: String? = null,

    /**
     * The delay, in blocks, the coordinator chose for the parent's own leaf in
     * the guarded subgroup this ceremony makes -- or null for an unguarded one,
     * one that makes no subgroup, or one from a build before parents chose.
     * `docs/guardian-delay.md`.
     *
     * A claim on a proposal like [walletPolicy], read for the join screen and for
     * the certificate rung. A guarded ceremony with none is certified at
     * `GuardianDerivation.DEFAULT_DELAY`, which is what its build would have
     * written.
     */
    val guardianDelay: Long? = null,

    val stage: DkgRitualStage = DkgRitualStage.COLLECTING_HOST_KEYS,

    /** This device's ChillDKG host public key (33-byte compressed, hex). */
    val hostPublicKey: HexKey,

    /** Secret. 32 bytes of fresh randomness for `participantStep1`. */
    val round1Random: HexKey,

    /** Secret. 32 bytes of fresh randomness for `participantStep2`. */
    val round2AuxRandom: HexKey,

    /** `CoordinatorMsg1` once it arrives, hex. */
    val coordinatorRound1: HexKey? = null,

    /** `CoordinatorMsg2` — the success certificate — once it arrives, hex. */
    val certificate: HexKey? = null,

    /** Result: the group's FROST threshold public key, hex. */
    val thresholdPublicKey: HexKey? = null,

    /** Result, secret: this device's FROST secret share, hex. */
    val secretShare: HexKey? = null,

    /**
     * Result: every participant's public share, comma-separated hex, in
     * participant order.
     *
     * Not secret, and not needed to finish the ceremony -- kept because signing
     * is what the key is for and FROST validates each signer's secret share
     * against its public one. Null on a ceremony that completed before this
     * column existed; signing still works there, without that check.
     */
    val publicShares: String? = null,

    /** Result: recovery data, to be backed up alongside the host key. */
    val recoveryData: HexKey? = null,

    val failureReason: String? = null,

    /**
     * When this device's owner approved publishing their host key, and with it
     * joining the ceremony at all. Null until they do, and nothing of theirs goes
     * out before it is set.
     *
     * The ritual is otherwise driven entirely by arriving messages, which would
     * mean a member is enrolled in the group's permanent signing quorum by a relay
     * delivering an event to a phone in their pocket. Each of these three is a
     * separate decision because each publishes something different and commits the
     * member to something different -- see `ChillDkgRitualManager.pendingApproval`.
     *
     * Set at creation for the member who opened the ceremony: starting one is
     * already the act of agreeing to be in it.
     */
    val hostKeyApprovedAt: Instant? = null,

    /** When they approved publishing their contribution to the key. */
    val round1ApprovedAt: Instant? = null,

    /** When they approved signing to confirm the combined result. */
    val round2ApprovedAt: Instant? = null,

    /**
     * The furthest step this device has already asked its owner about, so the chat
     * line asking for it is written once.
     *
     * One column rather than a flag per step because the steps are strictly
     * ordered: reaching [DkgApprovalStep.ROUND_1] means the host key was asked for
     * and granted. ChatMessage has no session to key a row against and no unique
     * constraint to lean on, so the guard has to live here.
     */
    val approvalRequestedThrough: DkgApprovalStep? = null,

    /**
     * The group this ceremony is being run to make a subgroup of, as the
     * proposal claimed -- or null for an ordinary ceremony.
     *
     * **This authenticates nothing.** It is read off a tag on the proposal, and
     * anybody can claim any parent. It exists for two things and neither of them
     * decides anything: the ritual screen saying "a subgroup of Ekklesia" rather
     * than "a shared key ceremony", and a member finding their way back into a
     * flow they closed the app halfway through.
     *
     * The load-bearing claim is the birth certificate, two steps later, which the
     * parent's own quorum signs and which any device can check against the
     * parent's room id alone. Nothing may be granted on the strength of this
     * column that would not be granted without it.
     *
     * Not a foreign key, for the reason `ChatRoom.parentChatRoomId` gives, and
     * with one more of its own: the room named here need never exist on this
     * device at all, since the claim is a stranger's until a certificate backs
     * it.
     */
    val parentChatRoomId: HexKey? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
): TimestampedEntity, LocalStoreEntity {
    fun isCoordinator(): Boolean = coordinatorPublicKey == userPublicKey

    /** The participants' public shares in participant order, or null if unrecorded. */
    fun publicShareList(): List<PublicKey>? = publicShares
        ?.split(",")
        ?.mapNotNull { hex -> hex.trim().takeIf { it.isNotEmpty() } }
        ?.map { PublicKey(ByteVector(it.hexToByteArray())) }
        ?.takeIf { it.isNotEmpty() }

    /**
     * Everyone this ceremony is being run with, or null on a row that predates
     * the column and so still means "the room's members".
     *
     * Callers that need an answer either way go through
     * `ChillDkgRitualManager.participantsOf`, which falls back to the room.
     */
    fun participantPublicKeySet(): Set<HexKey>? = participantPublicKeys
        ?.split(",")
        ?.mapNotNull { key -> key.trim().takeIf { it.isNotEmpty() } }
        ?.toSet()
        ?.takeIf { it.isNotEmpty() }

    companion object {
        /**
         * The stored form of a participant set: sorted, comma-separated hex.
         *
         * Sorted because the set is the same set whichever order it was
         * assembled in -- the coordinator builds it from a picker and every
         * other device from p-tags -- and two devices storing two strings for
         * one ceremony would make the column useless for matching on.
         */
        fun formatParticipants(publicKeys: Set<HexKey>): String? =
            publicKeys.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")
    }
}

/** [DkgSession.walletPolicy], read; null for none or one this build does not know. */
val DkgSession.subgroupWalletPolicy: SubgroupWalletPolicy?
    get() = walletPolicy?.let(SubgroupWalletPolicy::fromTagValue)
