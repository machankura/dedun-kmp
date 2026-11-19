package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.marmot.appComponents.AdminPolicyV1
import com.vitorpamplona.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository

/**
 * The lines a room gets when who governs it changes. docs/member-admin-promotion.md,
 * Phase 2.
 *
 * Another member administers the room and changes its admin list over the wire;
 * the app applies each commit and is checked for what it says. The lines come from
 * the group's admin set before and after the commit -- not from the participants'
 * `adminAt` flags -- so they are the same on every device that applies it.
 */
class AdminCaptionsJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val marmotRepository = DatabaseMarmotRepository(db, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair()
    private val userHex = user.pubKey.toHexKey()
    private val dana = MarmotStandIn()

    /** Dana's room with the app in it; Dana is its only admin. Returns the room id. */
    private suspend fun danasRoom(): String {
        MarmotStandIn.seedProfile(db, userHex, "us")
        MarmotStandIn.seedProfile(db, dana.hex, "Dana")
        val routingId = dana.createGroup("Dana's")
        return dana.inviteApp(db, marmotRepository, user, routingId)
    }

    /** Dana commits [admins] as the room's admin list, and the app receives it. */
    private suspend fun danaSetsAdmins(routingId: String, admins: List<String>) {
        val commit = dana.commitLocally(routingId) {
            val staged = dana.manager.stageAppDataUpdate(routingId, AdminPolicyV1.COMPONENT_ID, AdminPolicyV1.ofHex(admins).encode())
            dana.manager.installState(routingId, staged.pendingState)
            staged.result
        }
        MarmotStandIn.storeInApp(db, user, dana.outbound.buildCommitEvent(routingId, commit.framedCommitBytes, commit.preCommitExporterSecret).signedEvent)
    }

    /**
     * The admin lines, in no particular order: two written in the same second
     * cannot be told apart by time, so the tests look for each by its type.
     */
    private suspend fun adminLines(roomId: String) =
        db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }
            .filter { it.messageType == ChatMessage.TYPE_ADMIN_ADDED || it.messageType == ChatMessage.TYPE_ADMIN_REMOVED }

    @Test
    fun `made an admin, and then not, the app says so in its own words`() = runBlocking {
        val roomId = danasRoom()

        danaSetsAdmins(roomId, listOf(dana.hex, userHex))
        assertEquals(listOf(ChatMessage.TYPE_ADMIN_ADDED to "You are now an admin"), adminLines(roomId).map { it.messageType to it.content })
        assertNotNull(db.chatRoomDao().findChatRoomById(roomId)!!.localParticipants.single { it.participant.participantPublicKey == userHex }.participant.adminAt)

        danaSetsAdmins(roomId, listOf(dana.hex))
        assertEquals(
            listOf("You are no longer an admin"),
            adminLines(roomId).filter { it.messageType == ChatMessage.TYPE_ADMIN_REMOVED }.map { it.content },
        )
        assertEquals(2, adminLines(roomId).size, "the demotion repeated the promotion's line")
        assertNull(db.chatRoomDao().findChatRoomById(roomId)!!.localParticipants.single { it.participant.participantPublicKey == userHex }.participant.adminAt)
    }

    @Test
    fun `another member made an admin is named`() = runBlocking {
        val roomId = danasRoom()
        val erin = KeyPair()
        MarmotStandIn.seedProfile(db, erin.pubKey.toHexKey(), "Erin")
        val add = dana.commitLocally(roomId) {
            dana.manager.addMember(roomId, CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(erin)).keyPackage.toTlsBytes())
        }
        MarmotStandIn.storeInApp(db, user, dana.outbound.buildCommitEvent(roomId, add.framedCommitBytes, add.preCommitExporterSecret).signedEvent)

        danaSetsAdmins(roomId, listOf(dana.hex, erin.pubKey.toHexKey()))

        assertEquals(listOf("Erin is now an admin"), adminLines(roomId).map { it.content })
    }

    @Test
    fun `a commit that changes nobody's rights says nothing about them`() = runBlocking {
        val roomId = danasRoom()
        val erin = KeyPair()
        MarmotStandIn.seedProfile(db, erin.pubKey.toHexKey(), "Erin")

        val add = dana.commitLocally(roomId) {
            dana.manager.addMember(roomId, CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(erin)).keyPackage.toTlsBytes())
        }
        MarmotStandIn.storeInApp(db, user, dana.outbound.buildCommitEvent(roomId, add.framedCommitBytes, add.preCommitExporterSecret).signedEvent)

        assertTrue(db.chatMessageDao().getChatMessagesByChatRoomId(roomId).any { it.chatMessage.messageType == ChatMessage.TYPE_MEMBER_ADDED })
        assertTrue(adminLines(roomId).isEmpty(), "an add wrote an admin line")
    }
}
