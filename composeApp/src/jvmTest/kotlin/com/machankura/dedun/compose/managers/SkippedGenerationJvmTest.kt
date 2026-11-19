package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.marmot.groups.MarmotGroupPolicy
import com.vitorpamplona.marmot.mip03GroupMessages.GroupEventEncryption
import com.vitorpamplona.mls.group.MlsGroup
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.extensions.exporterSecret

/**
 * The bug docs/mls-skipped-keys.md is about, closed in two places.
 *
 * RFC 9420 §9.1 says a receiver handed generation N+1 before N derives the intermediate
 * keys and keeps them, so the older message still opens when it turns up. quartz's engine
 * derived and cached them in a `SecretTree` field that `saveState` never wrote -- while
 * `exportSenderStates` did write the position the ratchet had reached. That pair is what
 * made it a bug rather than a cost: the saved state said the ratchet had passed generation
 * N and carried nothing for it, so two messages published in the same second reliably lost
 * one across a restart, silently -- the kind:445 row was stored and no inner event came out
 * of it.
 *
 * **The format closed it.** `MlsGroupState` has `skippedApplicationSecrets` and
 * `skippedHandshakeSecrets` from its STATE_VERSION 5, `saveState` exports them from the
 * secret tree beside the positions, and `restore` imports them back. Saved together, they
 * can no longer disagree, and the first two tests here are that.
 *
 * **And the row keeps up.** Phase 5 left the other half pinned: `MlsGroupManager.decrypt`
 * persists only when the message was a COMMIT that advanced the epoch, so an application
 * message reached the live group's state and never the blob on disk.
 * `MarmotEngine.processGroupEvent` writes it now, and the third test is that, the other way
 * round from how Phase 5 left it.
 *
 * One correction while inverting it. Phase 5's note said a restart between two messages
 * still lost the late one, and that is not what an unwritten row costs here: an older
 * *coherent* snapshot -- positions and skipped keys saved together, or neither -- restores
 * a ratchet that has not passed the late generation yet, and derives it forward. What it
 * costs is the deriving: every generation since the last full save, re-derived after each
 * restart, against `SecretTree`'s thousand-step forward cap. The reason to close it in this
 * change rather than the next is that until this change every send wrote the whole state,
 * so the row could not fall far behind. Now sends write a ratchet record instead
 * (`SenderRatchetPersistenceJvmTest`), and this is what keeps the row current inside an
 * epoch.
 *
 * See docs/marmot-kmp.md, Phases 5 and 8.
 */
class SkippedGenerationJvmTest {

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

    private suspend fun joinedRoom(): String {
        MarmotStandIn.seedProfile(db, userHex, "us")
        return amethyst.inviteApp(db, marmotRepository, user, amethyst.createGroup("skipped"))
    }

    private suspend fun store(event: Event) = MarmotStandIn.storeInApp(db, user, event, relay)

    /** One message from the stand-in, which advances its sender ratchet by one generation. */
    private suspend fun theirMessage(routingHex: String, text: String): GroupEvent =
        amethyst.outbound.buildAppEvent(
            routingHex,
            MarmotAppEvent.build(pubKey = amethyst.hex, kind = ChatEvent.KIND, content = text, createdAt = TimeUtils.now()),
        ).signedEvent

    /** The state of the group the engine holds right now, as the manager would save it. */
    private suspend fun liveState(roomId: String): MlsGroupState =
        assertNotNull(db.marmotEngine.groupManager.snapshot(roomId), "the engine holds no group")

    /** The state actually written to the room's row. */
    private suspend fun storedState(roomId: String): MlsGroupState {
        val room = assertNotNull(db.chatRoomDao().findChatRoomById(roomId))
        return MlsGroupState.decodeTls(assertNotNull(room.chatRoom.mlsGroupState).hexToByteArray())
    }

    /**
     * Receiving generation 1 before generation 0 leaves generation 0's secret in the state
     * the engine would save. Under the old engine `saveState` dropped it on the floor;
     * mls-kmp's STATE_VERSION 5 is where it started being carried.
     */
    @Test
    fun `a skipped generation's secret is in the state the engine would save`() = runBlocking {
        val roomId = joinedRoom()
        theirMessage(roomId, "generation 0")
        val second = theirMessage(roomId, "generation 1")

        // The manager holds a group once it has been asked for one; the engine adopts it
        // from the row on first use, which is what every app path does before it reads.
        assertNotNull(db.marmotEngine.groupFor(roomId), "the engine cannot restore the room")
        assertTrue(
            liveState(roomId).skippedApplicationSecrets.isEmpty(),
            "a group that has received nothing already has skipped secrets",
        )

        // The later one arrives first; the receiver derives generation 0 on the way to 1.
        store(second)

        assertEquals(
            1,
            liveState(roomId).skippedApplicationSecrets.size,
            "the skipped generation's secret is not in the state, which is the bug " +
                "docs/mls-skipped-keys.md describes",
        )
    }

    /**
     * And it survives the encode: a group rebuilt from those bytes -- the whole round trip
     * through the blob, which is what a restart is -- reads the message that arrived late.
     *
     * This is the engine-level claim, made against the state the engine holds rather than
     * the state the app wrote, because those are not the same thing yet. See the test
     * below.
     */
    @Test
    fun `the late message decrypts under a group rebuilt from those bytes`() = runBlocking {
        val roomId = joinedRoom()
        val first = theirMessage(roomId, "generation 0")
        val second = theirMessage(roomId, "generation 1")

        store(second)

        val rebuilt = MlsGroup.restore(MlsGroupState.decodeTls(liveState(roomId).encodeTls()), MarmotGroupPolicy)
        val mlsBytes = GroupEventEncryption.decrypt(first.content, rebuilt.exporterSecret())
        val decrypted = rebuilt.decrypt(mlsBytes)

        assertTrue(
            decrypted.content.decodeToString().contains("generation 0"),
            "the late message did not decrypt from a rebuilt group",
        )
    }

    /**
     * And the row carries it, which is what Phase 5 left undone.
     *
     * The write is `MarmotEngine.processGroupEvent`, not the manager: the library persists
     * an epoch change as it applies it and a proposal as it stages it, and an application
     * message is the one result that moves state and writes nothing, because there is no
     * small record for the receive side the way there is for the send side -- the skipped
     * keys live in the group state and nowhere else.
     *
     * `store(...)` is the app's own ingest, so this goes through the door the app uses. A
     * test that reached for `engine.inbound.processGroupEvent` instead would read the
     * message and write nothing, which is the one way to make this fail without a bug.
     */
    @Test
    fun `the stored state carries it too, so a restart starts where this device left off`() = runBlocking {
        val roomId = joinedRoom()
        theirMessage(roomId, "generation 0")
        val second = theirMessage(roomId, "generation 1")

        store(second)

        assertEquals(
            liveState(roomId).skippedApplicationSecrets.size,
            storedState(roomId).skippedApplicationSecrets.size,
            "the row does not carry what the live group holds -- the receive path is not persisting",
        )
        assertEquals(
            1,
            storedState(roomId).skippedApplicationSecrets.size,
            "the skipped generation's secret is not on the row",
        )
    }

    /**
     * The positions move with it, which is the half that makes the pair coherent.
     *
     * A state that said the ratchet had passed generation 0 while carrying nothing for it
     * is the original bug, so the thing to assert is not that each is saved but that
     * neither is saved without the other.
     */
    @Test
    fun `the row's ratchet positions move with the secrets it keeps`() = runBlocking {
        val roomId = joinedRoom()
        theirMessage(roomId, "generation 0")
        store(theirMessage(roomId, "generation 1"))

        val live = liveState(roomId).senderRatchetStates
        val stored = storedState(roomId).senderRatchetStates
        assertEquals(live.keys, stored.keys, "the row knows a different set of senders")
        live.forEach { (leafIndex, state) ->
            assertEquals(
                state.applicationGeneration,
                assertNotNull(stored[leafIndex]).applicationGeneration,
                "leaf $leafIndex is at a different generation on the row than in the group",
            )
        }
    }
}
