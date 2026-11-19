package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.nostr.Relays

/**
 * A device checking the room it holds against the ceremony it took part in.
 * docs/robust-group-admins.md, Phase 3.
 *
 * The rooms here are made with [MarmotGroupCreation.create] and a list of admins
 * on purpose: that is the one way to stand in for a room some other device made
 * with fewer admins than the ceremony had, which is what the check is for.
 */
class FoundingAdminsJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(Dispatchers.Default)
    private val chatRepository = DatabaseChatRepository(db, scope)
    private val nostrRepository = DatabaseNostrRepository(db, scope)

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val user = keyPair.pubKey.toHexKey()
    private val alice = KeyPair().pubKey.toHexKey()
    private val bob = KeyPair().pubKey.toHexKey()
    private val founders = setOf(user, alice, bob)

    private val thresholdPublicKey = "02" + KeyPair().pubKey.toHexKey()
    private val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    private val ceremonyRoomId = "ce1e".repeat(16)
    private val sessionId = "the-ceremony"

    /**
     * A room at [roomId] whose epoch-0 admins are [admins] -- whatever the ceremony
     * said -- holding only its creator. Built from the steps `MarmotGroupCreation`
     * takes, minus the invites: the check is about the policy, and listing an admin
     * needs no key package of theirs.
     */
    private suspend fun roomWithAdmins(admins: Set<HexKey>, profile: MarmotProfile = MarmotProfile.LEGACY) {
        (founders + admins).forEach { MarmotStandIn.seedProfile(db, it) }
        val created = MarmotGroupFactory.create(
            creator = keyPair,
            roomId = roomId,
            name = "#Ekklesia",
            description = SharedKeyDerivation.describe("Admins of Ekklesia."),
            adminPublicKeys = admins,
            relays = Relays.DefaultDMRelayList.map { it.url },
            profile = profile,
        )
        assertNotNull(
            chatRepository.getOrCreateChatRoom(
                chatRoomId = roomId,
                activeUserPublicKey = user,
                relayHint = null,
                defaultSubject = "#Ekklesia",
                description = null,
                mlsGroupState = created.group.saveState().encodeTls().toHex(),
            )
        )
        chatRepository.recordMarmotRouting(chatRoomId = roomId, routingId = created.routingId)
    }

    private suspend fun ceremony(participants: Set<HexKey>? = founders) {
        db.chatRoomDao().upsert(ChatRoom(id = ceremonyRoomId, userPublicKey = user, subject = "Ekklesia", description = null, mlsGroupState = null))
        db.dkgSessionDao().upsert(
            DkgSession(
                id = sessionId,
                chatRoomId = ceremonyRoomId,
                coordinatorPublicKey = user,
                userPublicKey = user,
                threshold = 2,
                participantCount = 3,
                participantPublicKeys = participants?.let { DkgSession.formatParticipants(it) },
                subject = "Ekklesia",
                stage = DkgRitualStage.COMPLETE,
                hostPublicKey = user,
                round1Random = "aa".repeat(32),
                round2AuxRandom = "bb".repeat(32),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = "cc".repeat(32),
            )
        )
    }

    private suspend fun keyState() {
        db.groupKeyStateDao().replace(
            GroupKeyState(
                chatRoomId = roomId,
                dkgSessionId = sessionId,
                thresholdPublicKey = thresholdPublicKey,
                derivationPath = SharedKeyDerivation.formatPath(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH),
                announcedBy = roomId,
                announcedAt = Clock.System.now(),
            )
        )
    }

    @Test
    fun `a room with no key behind it has no founders to check`(): Unit = runBlocking {
        roomWithAdmins(setOf(user))
        assertEquals(FoundingAdmins.Check.NotKeyBacked, FoundingAdmins.check(db, roomId))
    }

    @Test
    fun `a device that holds no ceremony for the room cannot check it`(): Unit = runBlocking {
        roomWithAdmins(setOf(user))
        keyState()
        assertEquals(FoundingAdmins.Check.CannotCheck, FoundingAdmins.check(db, roomId))
    }

    @Test
    fun `a room made with every founder as an admin matches`(): Unit = runBlocking {
        roomWithAdmins(founders)
        ceremony()
        keyState()
        assertEquals(FoundingAdmins.Check.Matches, FoundingAdmins.check(db, roomId))
    }

    @Test
    fun `a room made by a device that listed only itself is missing the others`(): Unit = runBlocking {
        roomWithAdmins(setOf(user))
        ceremony()
        keyState()
        assertEquals(FoundingAdmins.Check.Missing(setOf(alice, bob)), FoundingAdmins.check(db, roomId))
    }

    @Test
    fun `a ceremony from before its participant list falls back to its room's members`(): Unit = runBlocking {
        roomWithAdmins(setOf(user))
        ceremony(participants = null)
        db.participantDao().upsert(founders.map { Participant(participantPublicKey = it, chatRoomId = ceremonyRoomId, relayHint = null) })
        keyState()
        assertEquals(FoundingAdmins.Check.Missing(setOf(alice, bob)), FoundingAdmins.check(db, roomId))
    }

    /** What the Welcome path writes: one line naming who is missing, and none when nobody is. */
    @Test
    fun `the room says who it is missing, once, and only when someone is`(): Unit = runBlocking {
        roomWithAdmins(setOf(user))
        ceremony()
        keyState()

        FoundingAdmins.announceIfMissing(db, roomId, user)
        val lines = db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }
            .filter { it.messageType == ChatMessage.TYPE_FOUNDING_ADMINS_MISSING }
        val line = lines.single()
        assertTrue(alice.take(8) in line.content && bob.take(8) in line.content, line.content)
        assertTrue(ChatMessage.TYPE_FOUNDING_ADMINS_MISSING in ChatMessage.MEMBERSHIP_TYPES, "it would render as a chat bubble")
    }

    @Test
    fun `a room that names every founder says nothing`(): Unit = runBlocking {
        roomWithAdmins(founders)
        ceremony()
        keyState()

        assertEquals(FoundingAdmins.Check.Matches, FoundingAdmins.announceIfMissing(db, roomId, user))
        assertTrue(db.chatMessageDao().getChatMessagesByChatRoomId(roomId).none {
            it.chatMessage.messageType == ChatMessage.TYPE_FOUNDING_ADMINS_MISSING
        })
    }

    /**
     * The repair: one commit that adds the missing founders to the policy and
     * leaves every admin already there in place. On the current profile, where
     * the app governs a room; the relay's OK is played by hand.
     */
    @Test
    fun `an admin puts the missing founders back into the policy`(): Unit = runBlocking {
        roomWithAdmins(setOf(user), MarmotProfile.CURRENT)
        ceremony()
        keyState()
        val missing = assertIs<FoundingAdmins.Check.Missing>(FoundingAdmins.check(db, roomId)).publicKeys

        chatRepository.addAdmins(db.chatRoomDao().findChatRoomById(roomId)!!, missing)
        okTheLatestCommit()

        val room = db.chatRoomDao().findChatRoomById(roomId)!!
        val group = assertNotNull(db.marmotEngine.groupFor(room.routingId))
        assertEquals(founders, group.currentAdminIdentities())
        assertEquals(FoundingAdmins.Check.Matches, FoundingAdmins.check(db, roomId))
    }

    private suspend fun okTheLatestCommit() {
        val event = db.nostrEventDao().getMLSGroupEvents(since = Instant.fromEpochSeconds(0), limit = 20)
            .filter { it.kind == GroupEvent.KIND }
            .last { db.marmotCommitResultDao().getMarmotCommitRequestById(it.id) != null }
        val request = assertNotNull(db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(event.id))
        nostrRepository.broadcastProcessed(request, "published")
    }
}
