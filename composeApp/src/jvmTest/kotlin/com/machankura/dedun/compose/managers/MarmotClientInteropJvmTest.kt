package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.marmot.GroupEventResult
import com.vitorpamplona.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.marmot.groups.currentGroupState
import com.vitorpamplona.marmot.groups.currentNostrGroupId
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository

/**
 * The app's orchestration against the library's own: a current-profile group made,
 * invited and sent to by `MarmotManager` -- what Amethyst drives -- and read by
 * `MarmotEngine` over a `DedunDatabase`, and back.
 *
 * [CurrentProfileInteropJvmTest] is the same shape against the bare processors, and the
 * two are not redundant. There, this test's own code decides how the Welcome is sealed
 * and what an application payload looks like; here the library decides both, and the only
 * thing under test is whether the app agrees with those decisions. That is the question
 * the bare stand-in cannot ask, because the bare stand-in is this test's own wiring of
 * the same pieces the app wires.
 *
 * See docs/marmot-kmp.md, Phase 3.
 */
class MarmotClientInteropJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )
    private val marmotRepository = DatabaseMarmotRepository(db, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair()
    private val userHex = user.pubKey.toHexKey()
    private val relay = "wss://relay.example"

    private val amethyst = MarmotClientStandIn()

    /** The client makes its group, invites us, and we join from the Welcome it wrapped. */
    private suspend fun joinedRoom(): String {
        MarmotStandIn.seedProfile(db, userHex, "us")
        MarmotStandIn.seedProfile(db, amethyst.hex, "amethyst")
        val routingId = amethyst.createGroup("interop")
        return amethyst.inviteApp(db, marmotRepository, user, routingId)
    }

    private suspend fun transcript(roomId: String) =
        db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }

    /**
     * The invite path end to end, with every choice in it the library's: which commit
     * carries the Add, how the Welcome is sealed and wrapped, and which relays it names.
     * The app joining at all is the assertion; that it joined under the routing id the
     * client minted is the one that would catch a room keyed on the wrong value.
     */
    @Test
    fun `the app joins a group the library's own client made and invited it to`() = runBlocking {
        val roomId = joinedRoom()

        val room = assertNotNull(db.chatRoomDao().findChatRoomById(roomId), "no room")
        assertEquals(roomId, room.chatRoom.id, "the room is keyed on something other than the routing id")
        val group = assertNotNull(room.chatRoom.toMlsGroup(), "the room joined without state to show for it")
        assertEquals(roomId, group.currentNostrGroupId(), "the group routes somewhere other than its room")
        assertTrue(
            group.currentGroupState().isCurrentProfile,
            "a group the current-profile factory made did not come back as one",
        )
    }

    /** A text message the library's client sends, filed and attributed by the app. */
    @Test
    fun `a message from the library's own client is read and attributed to it`() = runBlocking {
        val roomId = joinedRoom()

        val bundle = amethyst.client.buildTextMessage(roomId, "hello from the client", persistOwn = false)
        MarmotStandIn.storeInApp(db, user, bundle.outbound.signedEvent, relay)

        val line = assertNotNull(
            transcript(roomId).firstOrNull { it.content == "hello from the client" },
            "the message was not filed",
        )
        assertEquals(amethyst.hex, line.senderPublicKey)
        assertFalse(line.isUserMessage)
    }

    /**
     * And the other direction: a message the app's outbound path builds, decrypted by the
     * library's client and decoded as a canonical payload. The `"sig":""` the app's rumor
     * still carries is stripped first -- the same carve-out
     * [CurrentProfileInteropJvmTest] makes, and the same one docs/marmot-v2.md Phase 2
     * records as unfinished.
     */
    @Test
    fun `a message the app sends is read by the library's own client`() = runBlocking {
        val roomId = joinedRoom()
        val room = assertNotNull(db.chatRoomDao().findChatRoomById(roomId))
        val inner = MarmotInnerEvent(
            id = "9".repeat(64),
            publicKey = userHex,
            chatRoomId = roomId,
            kind = ChatEvent.KIND,
            tags = emptyArray(),
            content = "hi from the app",
        )

        db.marmotOutboundDao().encryptAndSendMarmotInnerEvent(
            localChatRoom = room,
            mlsGroup = assertNotNull(room.chatRoom.toMlsGroup()),
            marmotInnerEvent = inner,
            nostrSignerSync = NostrSignerSync(user),
        )

        val sent = db.nostrEventDao()
            .getMLSGroupEvents(since = Instant.fromEpochSeconds(0), limit = 10)
            .mapNotNull { it.toGroupEvent(userHex) }
            .single()

        val result = assertIs<GroupEventResult.ApplicationMessage>(amethyst.client.processGroupEvent(sent))
        val payload = assertNotNull(
            MarmotAppEvent.decodeOrNull(result.innerEventJson.replace(",\"sig\":\"\"}", "}")),
            "the library's client did not read it as a canonical payload",
        )
        assertEquals("hi from the app", payload.content)
        assertEquals(userHex, payload.pubKey)
    }
}
