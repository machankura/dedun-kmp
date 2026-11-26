package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlin.time.Clock
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents

/**
 * One keyed group of three on one device at a time: a proposal made on one member's device is
 * handed to another's through the inbound path, where the event ids are rebuilt and the
 * refusals run. Giza's, lifted out of its GroupStatementDeclineJvmTest so that the group inbox's and
 * direct messages' tests can share it (docs/giza-second-batch.md, Phase 6).
 *
 * The room is a NIP-17 one whose id is still the derived key, so what is queued is a
 * [GiftWrapPayload] and can be read back without an MLS group.
 */
class SigningProposalHarness(
    secret: String = "1c0ffee0000000000000000000000000000000000000000000000000000000a1",
    names: List<String> = listOf("a", "b", "c"),
    val subject: String = "Ekklesia",
) {
    val participants = names.size
    val threshold = 2

    val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32(secret)),
        nParticipants = participants,
        threshold = threshold
    )

    val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()
    val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    val ceremonyId = ("ceremony" + secret.takeLast(8)).padEnd(64, '0')
    val members = names.map { it.repeat(64).take(64) }
    private val hostKeys = names.indices.map { "2${('a' + it)}".padEnd(66, '0') }

    lateinit var db: DedunDatabase
        private set
    lateinit var room: LocalChatRoom
        private set

    fun close() {
        if (::db.isInitialized) db.close()
    }

    /**
     * Opens [owner]'s device: a fresh database holding the room and a completed ceremony.
     * [prepare] runs on it before it is returned, for whatever else the device should hold.
     */
    suspend fun openDevice(owner: HexKey, prepare: suspend (DedunDatabase) -> Unit = {}): DedunDatabase {
        close()
        db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

        val index = members.indexOf(owner)
        val nostrEventId = "e$owner".take(64)
        db.nostrEventDao().upsert(
            NostrEvent(id = nostrEventId, pubKey = owner, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128))
        )
        db.profileDao().upsert(Profile(publicKey = owner, nostrEventId = nostrEventId))

        val chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = owner,
            subject = subject,
            description = SharedKeyDerivation.describe("Admins of the group."),
            mlsGroupState = null
        )
        db.chatRoomDao().upsert(chatRoom)

        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId,
                chatRoomId = roomId,
                coordinatorPublicKey = members[0],
                userPublicKey = owner,
                threshold = threshold,
                participantCount = participants,
                stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[index],
                round1Random = "1".repeat(64),
                round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[index].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() }
            )
        )
        members.forEachIndexed { i, member ->
            db.dkgSessionDao().upsert(
                DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = hostKeys[i])
            )
        }

        room = LocalChatRoom(chatRoom = chatRoom)
        prepare(db)
        return db
    }

    /** What this device has queued for the group, of one kind. */
    suspend fun queued(kind: Kind): List<GiftWrapPayload> = db.giftWrapPayloadDao()
        .getByChatRoomAndKinds(roomId, listOf(kind))
        .filter { it.publicKey == room.chatRoom.userPublicKey }

    /** A proposal as it left the proposer's device, to be handed to another. */
    data class Sent(val session: FrostSigningSession, val proposer: HexKey, val tags: Array<Array<String>>, val content: String)

    /**
     * Opens [proposer]'s device and runs [propose] on it, returning the proposal it queued.
     */
    suspend fun propose(
        proposer: HexKey,
        prepare: suspend (DedunDatabase) -> Unit = {},
        propose: suspend (DedunDatabase, LocalChatRoom) -> FrostSigningSession,
    ): Sent {
        openDevice(proposer, prepare)
        val session = propose(db, room)
        val payload = queued(FrostSigningEvents.PROPOSAL).single()
        // A NIP-17 payload carries its recipients' p-tags too; the inbound path never sees those.
        val tags = payload.tags.filterNot { it.isNotEmpty() && it[0] == "p" }.toTypedArray()
        return Sent(session, proposer, tags, payload.content)
    }

    /** Opens [recipient]'s device and hands it [sent], as the inbound path would. */
    suspend fun deliver(sent: Sent, recipient: HexKey, prepare: suspend (DedunDatabase) -> Unit = {}) {
        openDevice(recipient, prepare)
        FrostSigningManager.processSigningPayload(
            database = db,
            localChatRoom = room,
            innerEvent = Event(
                id = "proposal".padEnd(64, '0'),
                pubKey = sent.proposer,
                createdAt = Clock.System.now().epochSeconds,
                kind = FrostSigningEvents.PROPOSAL,
                tags = sent.tags,
                content = sent.content,
                sig = ""
            ),
            userPublicKey = recipient
        )
    }
}
