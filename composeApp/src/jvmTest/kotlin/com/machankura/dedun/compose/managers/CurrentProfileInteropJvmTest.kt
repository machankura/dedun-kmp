package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Two implementations in one current-profile group: the app's engine over a
 * `DedunDatabase`, and a bare library stand-in -- `MlsGroupManager` and the two
 * processors over an in-memory store -- playing Amethyst. docs/marmot-v2.md,
 * Phases 5 and 8.
 *
 * The stand-in makes the group and invites the app; the Welcome arrives as a NIP-59
 * gift wrap through the sync funnel; then messages cross in both directions, a
 * commit from the other side moves the app's room, and a second engine over the
 * same database -- a restart -- reads a message from the epoch before it.
 */
class CurrentProfileInteropJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )
    private val marmotRepository = DatabaseMarmotRepository(db, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair()
    private val userHex = user.pubKey.toHexKey()
    private val relay = "wss://relay.example"

    private val amethyst = MarmotStandIn()

    /** The stand-in makes its group, adds us, and we join from the gift-wrapped Welcome. */
    private suspend fun joinedRoom(): String {
        MarmotStandIn.seedProfile(db, userHex, "us")
        val routingId = amethyst.createGroup("interop")
        return amethyst.inviteApp(db, marmotRepository, user, routingId)
    }

    private suspend fun store(event: Event) = MarmotStandIn.storeInApp(db, user, event, relay)

    private suspend fun theirMessage(routingHex: String, text: String): GroupEvent =
        amethyst.outbound.buildAppEvent(
            routingHex,
            MarmotAppEvent.build(pubKey = amethyst.hex, kind = ChatEvent.KIND, content = text, createdAt = TimeUtils.now()),
        ).signedEvent

    private suspend fun transcript(roomId: String) =
        db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }

    @Test
    fun `a message from the other implementation is read and attributed to its sender`() = runBlocking {
        val roomId = joinedRoom()

        store(theirMessage(roomId, "hello from the other side"))

        val line = assertNotNull(transcript(roomId).firstOrNull { it.content == "hello from the other side" }, "the message was not filed")
        assertEquals(amethyst.hex, line.senderPublicKey)
        assertFalse(line.isUserMessage)
    }

    @Test
    fun `a message the app sends is read by the other implementation, as a canonical payload`() = runBlocking {
        val roomId = joinedRoom()
        val room = assertNotNull(db.chatRoomDao().findChatRoomById(roomId))
        val inner = MarmotInnerEvent(
            id = "7".repeat(64), publicKey = userHex, chatRoomId = roomId, kind = ChatEvent.KIND,
            tags = emptyArray(), content = "hi back",
        )

        db.marmotOutboundDao().encryptAndSendMarmotInnerEvent(
            localChatRoom = room,
            mlsGroup = assertNotNull(room.chatRoom.toMlsGroup()),
            marmotInnerEvent = inner,
            nostrSignerSync = NostrSignerSync(user),
        )

        val sent = db.nostrEventDao().getMLSGroupEvents(since = Instant.fromEpochSeconds(0), limit = 10)
            .mapNotNull { it.toGroupEvent(userHex) }
            .single()
        val result = assertIs<GroupEventResult.ApplicationMessage>(amethyst.inbound.processGroupEvent(sent))
        val payload = assertNotNull(MarmotAppEvent.decodeOrNull(result.innerEventJson.replace(",\"sig\":\"\"}", "}")), "not a canonical payload")
        assertEquals("hi back", payload.content)
        assertEquals(userHex, payload.pubKey)
    }

    /**
     * A commit from the other side moves the app's room to the same epoch, adds the
     * member it added, and is persisted by the engine's manager -- the state on the
     * row is the manager's, not a stale instance's.
     */
    @Test
    fun `a commit from the other implementation moves the room and its membership`() = runBlocking {
        val roomId = joinedRoom()
        val third = KeyPair()
        MarmotStandIn.seedProfile(db, third.pubKey.toHexKey(), "third")
        val thirdPackage = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(third)).keyPackage

        val commit = amethyst.manager.addMember(roomId, thirdPackage.toTlsBytes())
        val commitEvent = amethyst.outbound.buildCommitEvent(roomId, commit.framedCommitBytes, commit.preCommitExporterSecret).signedEvent
        store(commitEvent)

        val room = assertNotNull(db.chatRoomDao().findChatRoomById(roomId))
        val restored = assertNotNull(room.chatRoom.toMlsGroup())
        assertEquals(amethyst.manager.getGroup(roomId)!!.epoch, restored.epoch, "the app's persisted epoch lags the group's")
        assertTrue(restored.currentMemberIdentities().contains(third.pubKey.toHexKey()))
        assertTrue(room.localParticipants.any { it.participant.participantPublicKey == third.pubKey.toHexKey() }, "the new member has no row")
        // And the epoch left behind is on disk, not in a map.
        assertTrue(db.marmotRetainedEpochSecretDao().getMarmotRetainedEpochSecretForChatRoomId(roomId).isNotEmpty())
    }

    /**
     * The case the old pipeline could not pass: a message from the epoch before a
     * commit, read by an engine rebuilt over the same database -- the retained epochs
     * come back from the store rather than from a map that died with the process.
     */
    @Test
    fun `a message from the prior epoch decrypts after the engine is rebuilt`() = runBlocking {
        val roomId = joinedRoom()
        val late = theirMessage(roomId, "sent before the commit")

        val third = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(KeyPair())).keyPackage
        val commit = amethyst.manager.addMember(roomId, third.toTlsBytes())
        store(amethyst.outbound.buildCommitEvent(roomId, commit.framedCommitBytes, commit.preCommitExporterSecret).signedEvent)

        val rebuilt = MarmotEngine(db)
        assertNotNull(rebuilt.groupFor(roomId), "the rebuilt engine does not restore the room")
        val result = assertIs<GroupEventResult.ApplicationMessage>(rebuilt.inbound.processGroupEvent(late))
        assertTrue(result.innerEventJson.contains("sent before the commit"))
    }
}
