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
import com.vitorpamplona.quartz.marmot.appComponents.MarmotGroupState
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroupState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.musig2.Musig2
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["publicKey"],
            childColumns = ["userPublicKey"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GiftWrapPayload::class,
            parentColumns = ["id"],
            childColumns = ["initialGiftWrapPayloadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChatRoom(
    /**
     * Derived from the participants...
     *
     * can aggregate them into one id like musig(participants...)
     */
    @PrimaryKey
    val id: String,

    /**
     * Logged in user PublicKey... should help us have multiple user support...
     */
    val userPublicKey: HexKey,

    /**
     * Optional retrieved from tags...
     *
     *  Any member can change the topic by simply submitting a new subject to an existing pubkey + p tags room.
     *
     *  There is no need to send subject in every message. The newest subject in the chat room is the subject of the conversation.
     */
    val subject: String?,

    val description: String?,

    val mlsGroupState: String?,

    /**
     * Derived from kind14/15 tags
     * [
     *     ["p", "<receiver-1-pubkey>", "<relay-url>"],
     *     ["p", "<receiver-2-pubkey>", "<relay-url>"],
     *     // rest of tags...
     *   ]
     *
     *   TODO: Might want to add a relation for this...
     */
    // TODO: Add a LastChatMessageRelation
//    val lastChatMessageId: String? = null,
    // Might want to focus on the other last messages...
    val initialGiftWrapPayloadId: HexKey? = null,

    val leftGroupAt: Instant? = null,

    /**
     * When this device became a member of the group, or null for a room that
     * predates the column.
     *
     * The pair to [leftGroupAt], and the thing [memberSince] is really asking
     * for. Written from the moment the group's own clock says our epoch began:
     * the Welcome's `created_at`, which the inviter stamps as it mints the
     * Welcome out of the Add commit that made us a member, or the room's own
     * creation for a group this device started at epoch 0.
     *
     * Stored rather than read off [createdAt] because the two are only
     * incidentally equal. [createdAt] is row bookkeeping -- when this device
     * first wrote the row down -- and the question asked here decides which of
     * the group's messages are ours to read at all. That is not a fact to leave
     * hanging off a timestamp somebody could reasonably repurpose.
     */
    val joinedGroupAt: Instant? = null,

    /**
     * When this device last asked the group for its signed history, or null if
     * it never has or the answer has since arrived.
     *
     * A device with no signed work in a room asks for a chronicle -- see
     * `ChronicleManager.requestIfEmpty` and docs/member-chronicle.md. This is the
     * only thing stopping it asking again on every launch while an answer is in
     * flight, and it is cleared as soon as a chronicle applies anything, so a
     * partial answer is followed by another request rather than by silence.
     *
     * Not a claim that anybody replied. Nothing acknowledges a request, and the
     * room having work in it is the only evidence that ever arrives.
     */
    val chronicleRequestedAt: Instant? = null,

    /**
     * The group this room is a subgroup of, or null for a room that is nobody's
     * child.
     *
     * A copy of the verified `GroupKeyState.parentChatRoomId`, kept here so the
     * room list and the room's own screen can answer "whose child is this"
     * without a second read. It is written as the room is created or adopts its
     * key state, and only from a state whose certificate passed
     * `SubgroupBirthCertificateEvent.certifies`.
     *
     * **Never a foreign key onto `ChatRoom`.** Every other reference to a room in
     * this schema cascades, and a self-referential cascade would mean deleting a
     * parent silently deletes its subgroups -- and then, by their own cascades,
     * those rooms' messages, participants, key states, signing sessions and
     * signed events. A user tidying up a group they left would destroy a group
     * they are still in. `RESTRICT` would be no better: it would make a parent
     * undeletable while a child row exists, which is a foreign key deciding a
     * product question. This routinely names a room this device does not have --
     * see `GroupKeyState.parentChatRoomId` -- so a dangling value is the normal,
     * expected state.
     */
    val parentChatRoomId: HexKey? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
    override val viewedAt: Instant? = null,
    override val deletedAt: Instant? = null,

    ): TimestampedEntity, LocalStoreEntity, UserViewableEntity, SoftDeletableEntity
{
    companion object {
        const val TAG = "ChatRoom"

        @Ignore
        val logger = Logger.withTag(TAG)

        fun deriveChatRoomId(hexKeySet: Set<String>): String {
            val hexKeys = hexKeySet.sorted()

            logger.d("hexKeys: $hexKeys")

            val publicKeys = hexKeys.map {

                val publicKey = PublicKey(
                    PublicKey.compress(it.hexToByteArray())
                )
                if (publicKey.isValid().not()) {
                    logger.w("$it is an invalid pubkey (not representing a point on the secp256k1 curve): $publicKey")
                    // Using it to derive a publicKey
                    val derivedPublicKey = PrivateKey(
                        publicKey.xOnly().value
                    ).publicKey()

                    logger.w("Derived $derivedPublicKey: ${derivedPublicKey.isValid()}")

                    derivedPublicKey
                } else {
                    publicKey
                }
            }
            logger.d("PublicKeys: $publicKeys")

            val chatRoomXOnlyPublicKey = Musig2.aggregateKeys(
                publicKeys
            )
            logger.d("ChatRoomXOnly: $chatRoomXOnlyPublicKey")

            return chatRoomXOnlyPublicKey.publicKey.toHex()
        }

    }

    /**
     * The moment this device joined, falling back to when it wrote the room down.
     *
     * A room joined before [joinedGroupAt] existed has no recorded answer, and
     * [createdAt] is both the best one available and the one every path that
     * writes [joinedGroupAt] would have written anyway: a joiner's row is created
     * from the Welcome, and a creator's when it creates the group.
     */
    val memberSince: Instant get() = joinedGroupAt ?: createdAt

    /**
     * Whether something the group published at [publishedAt] belongs to an epoch
     * this device was never in.
     *
     * MLS gives a joiner the key schedule from their own epoch forward and
     * nothing before it, so a kind:445 older than [memberSince] cannot be read
     * now and cannot be read later -- not by waiting, and not by replaying it.
     * The point of asking is to leave those alone rather than file a line saying
     * a message arrived that nobody can show.
     *
     * Time is the only thing to ask it of. The epoch a kind:445 was encrypted
     * under is inside the outer layer, so an event this device cannot decrypt
     * cannot be asked what epoch it is from, and "before we joined", "from an
     * epoch we have not caught up to" and "from an epoch that fell out of the
     * retention window" all look identical from the outside. What separates the
     * first from the other two is that it was published before the group made
     * the epoch we joined at.
     *
     * Strictly before, so an event stamped in the same second as our Welcome is
     * still read. The error worth avoiding runs one way: an unreadable event
     * costs a wasted decrypt, and a discarded readable one is a message the
     * member never sees. The commit that added us sits exactly on that boundary
     * and is unreadable by construction -- it is the last act of the epoch
     * before ours -- so a room may still show one placeholder for it.
     */
    fun predatesMembership(publishedAt: Instant): Boolean = publishedAt < memberSince

    fun toMlsGroup(): MlsGroup? {
        return mlsGroupState?.let {
            return MlsGroup.restore(
                MlsGroupState.decodeTls(
                    mlsGroupState.hexToByteArray()
                )
            )
        }
    }

    /**
     * Which Marmot profile this room's group is on, read off the stored state's group
     * context without rebuilding the group -- no key schedule, no secret tree, just
     * the extensions. A room with no state, or state this build cannot read, answers
     * legacy: that is the profile every room was on before there were two, and it is
     * the pipeline that already copes with a room it cannot process.
     *
     * The profile picks the room's pipeline -- see `MarmotEngine`.
     */
    fun marmotProfile(): MarmotProfile {
        val state = mlsGroupState ?: return MarmotProfile.LEGACY
        return runCatching {
            val extensions = MlsGroupState.decodeTls(state.hexToByteArray()).groupContext.extensions
            if (MarmotGroupState.fromExtensions(extensions).isCurrentProfile) MarmotProfile.CURRENT else MarmotProfile.LEGACY
        }.getOrDefault(MarmotProfile.LEGACY)
    }
}
