package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.nostr.MarmotProfile

/**
 * `Participant.adminAt` made true where rooms are made and when a room is opened,
 * so that nothing reads a stale flag as news. docs/member-admin-promotion.md,
 * Phase 1.
 *
 * Before, the flag was written only when a commit arrived, so on the device that
 * created a convenient room or a direct message nobody was an admin until somebody
 * else committed. With admin captions derived from changes, that would have put
 * "You are now an admin" on the creator's screen at their first invite.
 */
class AdminFlagsJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val chatRepository = DatabaseChatRepository(db, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val user = keyPair.pubKey.toHexKey()
    private val member = MarmotStandIn()

    private suspend fun seedProfile(publicKey: String) {
        val eventId = publicKey.take(32).padEnd(64, 'f')
        db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = publicKey.take(8), nostrEventId = eventId))
    }

    private suspend fun directMessage(): String {
        seedProfile(user)
        seedProfile(member.hex)
        member.makeBundle()
        return db.marmotOutboundDao().createMlsDirectMessageChatRoom(
            creator = keyPair,
            peerPublicKey = member.hex,
            peerKeyPackage = MarmotKeyPackage(
                id = member.hex, publicKey = member.hex,
                tlsEncodedMarmotKeyPackage = member.bundle.keyPackage.toTlsBytes(),
                profile = MarmotProfile.CURRENT.wireName,
            ),
            profile = MarmotProfile.CURRENT,
        )
    }

    private suspend fun flags(roomId: String): Map<String, Instant?> =
        db.chatRoomDao().findChatRoomById(roomId)!!.localParticipants
            .associate { it.participant.participantPublicKey to it.participant.adminAt }

    @Test
    fun `the creator of a direct message is its admin from the start, and the peer is not`() = runBlocking {
        val roomId = directMessage()

        val flags = flags(roomId)
        assertNotNull(flags[user], "the creator was not flagged before any commit arrived")
        assertNull(flags[member.hex])
    }

    @Test
    fun `opening a room whose flags went stale catches them up and says nothing`() = runBlocking {
        val roomId = directMessage()
        // A room made before this build: the creator's row never flagged.
        val creatorRow = db.chatRoomDao().findChatRoomById(roomId)!!.localParticipants
            .single { it.participant.participantPublicKey == user }.participant
        db.participantDao().upsert(listOf(creatorRow.copy(adminAt = null)))
        val linesBefore = db.chatMessageDao().getChatMessagesByChatRoomId(roomId).size

        assertTrue(chatRepository.reconcileAdmins(roomId))

        assertNotNull(flags(roomId)[user])
        assertEquals(linesBefore, db.chatMessageDao().getChatMessagesByChatRoomId(roomId).size, "a reconcile wrote a line")
        assertFalse(chatRepository.reconcileAdmins(roomId), "a second reconcile found something to change")
    }

    @Test
    fun `a flag the group no longer backs is cleared, and one that agrees keeps its moment`() {
        val since = Instant.fromEpochSeconds(1_700_000_000)
        val rows = listOf(
            Participant(participantPublicKey = "a", chatRoomId = "r", relayHint = null, adminAt = since),
            Participant(participantPublicKey = "b", chatRoomId = "r", relayHint = null, adminAt = since),
            Participant(participantPublicKey = "c", chatRoomId = "r", relayHint = null),
        )

        val changes = MarmotInboundManager.adminFlagChanges(rows, adminPublicKeys = setOf("a", "c"))
            .associateBy { it.participantPublicKey }

        assertEquals(setOf("b", "c"), changes.keys, "a row that already agreed was rewritten")
        assertNull(changes.getValue("b").adminAt)
        assertNotNull(changes.getValue("c").adminAt)
    }
}
