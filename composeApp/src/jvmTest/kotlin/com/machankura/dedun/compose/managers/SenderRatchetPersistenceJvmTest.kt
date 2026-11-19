package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.marmot.GroupEventResult
import com.vitorpamplona.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.mls.group.MlsGroupState
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotSenderRatchet
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository

/**
 * Where this device's own sender ratchet is kept, and what it is for.
 *
 * Sending an application message advances this leaf's entry in the secret tree and
 * nothing else, so the library carries the position as its own small record --
 * `MlsGroupStateStore.saveSenderRatchet` -- rather than rewriting the group to hold
 * it. The store defaulted that to `false` until now, which sent the manager back to
 * a full `save()` per message: correct, and the cost of a whole group re-encoded on
 * every send, growing with the member count.
 *
 * Implementing the pair moves the position out of the group state, and that is the
 * thing to be careful about. It stops being carried by a write that happens anyway
 * and becomes a record that must be written, and read back, on its own. A position
 * that is lost re-emits a generation this leaf has already published, which reuses
 * an AEAD key and nonce pair -- a confidentiality break, and a message strict
 * receivers refuse outright.
 *
 * So the third test here deletes the record and shows the peer refusing what the
 * app then sends. That is the hazard, demonstrated rather than asserted: it says
 * what the row is holding up.
 *
 * See docs/marmot-kmp.md, Phase 8.
 */
class SenderRatchetPersistenceJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )
    private val marmotRepository = DatabaseMarmotRepository(db, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair()
    private val userHex = user.pubKey.toHexKey()

    private val amethyst = MarmotStandIn()

    private suspend fun joinedRoom(): String {
        MarmotStandIn.seedProfile(db, userHex, "us")
        val roomId = amethyst.inviteApp(db, marmotRepository, user, amethyst.createGroup("ratchet"))
        // Every app path asks the engine for the room before it reads or writes; the
        // manager owns the group from then on, and the row is what it was.
        assertNotNull(db.marmotEngine.groupFor(roomId), "the engine cannot restore the room")
        return roomId
    }

    /** A message the app sends, built the way `encryptAndSendMarmotInnerEvent` builds it. */
    private suspend fun appSends(engine: MarmotEngine, routingHex: String, text: String): GroupEvent =
        engine.outbound.buildGroupEventFromBytes(routingHex, text.encodeToByteArray()).signedEvent

    private suspend fun peerReads(event: GroupEvent): GroupEventResult =
        amethyst.inbound.processGroupEvent(event)

    private suspend fun ratchetRow(roomId: String): MarmotSenderRatchet? =
        db.marmotSenderRatchetDao().forChatRoomId(roomId)

    private suspend fun storedStateHex(roomId: String): String =
        assertNotNull(assertNotNull(db.chatRoomDao().findChatRoomById(roomId)).chatRoom.mlsGroupState)

    /** Where the stored state says this leaf's own ratchet is, which the record may be ahead of. */
    private suspend fun storedSenderGeneration(roomId: String): Int {
        val state = MlsGroupState.decodeTls(storedStateHex(roomId).hexToByteArray())
        return state.senderRatchetStates[state.myLeafIndex]?.applicationGeneration ?: 0
    }

    /** A message from the stand-in, delivered through the app's own ingest. */
    private suspend fun theirMessage(routingHex: String, text: String): GroupEvent =
        amethyst.outbound.buildAppEvent(
            routingHex,
            MarmotAppEvent.build(pubKey = amethyst.hex, kind = ChatEvent.KIND, content = text, createdAt = TimeUtils.now()),
        ).signedEvent

    private suspend fun store(event: Event) = MarmotStandIn.storeInApp(db, user, event, "wss://relay.example")

    /**
     * What the split is for: the position is written, the group is not.
     *
     * The room's state hex is byte for byte what it was before the send, which is the
     * whole saving -- and the reason the record below has to be read back, because it
     * is now the only place the position exists on disk.
     */
    @Test
    fun `a send writes the ratchet record and leaves the group state alone`() = runBlocking {
        val roomId = joinedRoom()
        val before = storedStateHex(roomId)
        assertNull(ratchetRow(roomId), "a room that has sent nothing already has a ratchet record")

        peerReads(appSends(db.marmotEngine, roomId, "one"))

        val row = assertNotNull(ratchetRow(roomId), "the send wrote no ratchet record")
        val group = assertNotNull(db.marmotEngine.groupManager.getGroup(roomId))
        assertEquals(group.epoch, row.epoch, "the record is not at the group's epoch")
        assertTrue(row.applicationGeneration > 0, "the record is at the position the send started from")
        assertEquals(before, storedStateHex(roomId), "the whole group state was rewritten to record a send")
    }

    /**
     * And it is read back: an engine rebuilt over the same database -- which is what a
     * restart is -- sends the next generation rather than the one it already used.
     *
     * The peer is the assertion. Its ratchet for this leaf has consumed generation 0,
     * so a second message at generation 0 is refused by RFC 9420 §9's own rule, and
     * the only way through is for the app to carry on from where the record says.
     */
    @Test
    fun `a restarted engine carries on from the record`() = runBlocking {
        val roomId = joinedRoom()
        peerReads(appSends(db.marmotEngine, roomId, "before the restart"))
        val sent = assertNotNull(ratchetRow(roomId)).applicationGeneration

        val rebuilt = MarmotEngine(db)
        assertNotNull(rebuilt.groupFor(roomId), "the rebuilt engine does not restore the room")

        val result = assertIs<GroupEventResult.ApplicationMessage>(
            peerReads(appSends(rebuilt, roomId, "after the restart")),
            "the peer refused the message sent after the restart",
        )
        assertEquals("after the restart", result.innerEventJson)
        assertTrue(
            assertNotNull(ratchetRow(roomId)).applicationGeneration > sent,
            "the record did not move past the generation already used",
        )
    }

    /**
     * The hazard the record exists for, with the record taken away.
     *
     * Deleting the row leaves exactly the state of affairs the split introduces if
     * the position is written and never read: the group state on disk is the one
     * saved before any send, so a restarted engine starts this leaf's ratchet where
     * that state left it and re-emits a generation the peer has already consumed.
     * The peer refuses it, and this is what that refusal looks like.
     *
     * It is also what the ordering in `MarmotEngine.groupFor` protects: adopting a
     * group persists it, so a record read after the adopt rather than before it is a
     * record already overwritten by the older position -- the same picture as this
     * test, reached by writing rather than by deleting.
     */
    @Test
    fun `without the record the restarted engine reuses a generation the peer has read`() = runBlocking {
        val roomId = joinedRoom()
        peerReads(appSends(db.marmotEngine, roomId, "before the restart"))
        assertNotNull(ratchetRow(roomId), "there was no record to delete")

        db.marmotSenderRatchetDao().deleteForChatRoomId(roomId)

        val rebuilt = MarmotEngine(db)
        assertNotNull(rebuilt.groupFor(roomId), "the rebuilt engine does not restore the room")

        assertIsNot<GroupEventResult.ApplicationMessage>(
            peerReads(appSends(rebuilt, roomId, "after the restart")),
            "the peer read a message at a generation it had already consumed, which means " +
                "the ratchet did not rewind and this test no longer shows what it claims",
        )
    }

    /**
     * The ordering in `MarmotEngine.groupFor`, on the path that reaches it.
     *
     * An engine restores once, and a room joined after that restore is loaded from its
     * row the first time it is asked for -- which is the common way round in a running
     * app, and the one path `MlsGroupManager.restoreAll` does not cover. Adopting a
     * group persists it, and persisting rewrites the ratchet row from the group being
     * adopted, so a record read after the adopt instead of before it is a record
     * already overwritten by the restored state's older position. The row is the
     * assertion, because the group in memory looks right either way: it is the
     * position left on disk that would have rewound, for the next restart to pick up.
     *
     * The send in the middle is what gives this teeth, and it took inverting the order
     * to find that out. A state saved before this leaf ever sent has no ratchet entry
     * at all, `exportOwnSenderRatchet` returns null for it and the adopt writes nothing
     * -- so the order cannot matter until the stored state holds a position of its own
     * that is behind the record's. One receive is what puts one there: it is a full
     * save, so it captures wherever the sender ratchet had got to, and the sends after
     * it move the record past that.
     *
     * Two engines over one database, which the app never does -- `DedunDatabase` owns
     * one. It is how the sequence is reached in a test: the second engine restores
     * while there is no room, and the first is finished writing before it reads.
     */
    @Test
    fun `a room joined after the engine restored keeps the position its record holds`() = runBlocking {
        val lateEngine = MarmotEngine(db)
        lateEngine.ensureRestored()

        val roomId = joinedRoom()
        peerReads(appSends(db.marmotEngine, roomId, "before their message"))
        // Their message is a full save, which puts the sender position of this moment
        // into the stored state; the send after it leaves the record ahead of that.
        store(theirMessage(roomId, "theirs"))
        peerReads(appSends(db.marmotEngine, roomId, "after their message"))

        val sent = assertNotNull(ratchetRow(roomId)).applicationGeneration
        assertTrue(
            storedSenderGeneration(roomId) < sent,
            "the stored state is not behind the record, so this test cannot show the order matters",
        )

        // The lazy path: this engine restored before the room existed, so it holds no
        // group for it and loads one from the row.
        assertNull(lateEngine.groupManager.getGroup(roomId), "the room was already in the manager; this is not the lazy path")
        assertNotNull(lateEngine.groupFor(roomId), "the engine cannot load the room from its row")

        assertEquals(
            sent,
            assertNotNull(ratchetRow(roomId)).applicationGeneration,
            "loading the room rewound the recorded position, so the next restart re-emits a used generation",
        )
    }

    /**
     * A position means nothing outside the secret tree that derived it, and that tree
     * is what goes when a room's state goes.
     */
    @Test
    fun `dropping a room's state drops its ratchet record`() = runBlocking {
        val roomId = joinedRoom()
        peerReads(appSends(db.marmotEngine, roomId, "one"))
        assertNotNull(ratchetRow(roomId))

        db.marmotEngine.store.delete(roomId)

        assertNull(ratchetRow(roomId), "the record outlived the group state it describes")
    }
}
