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
 * The bug docs/mls-skipped-keys.md is about: half closed by the move, and the other half
 * pinned here, because it is no longer the same half.
 *
 * RFC 9420 §9.1 says a receiver handed generation N+1 before N derives the intermediate
 * keys and keeps them, so the older message still opens when it turns up. quartz's engine
 * derived and cached them in a `SecretTree` field that `saveState` never wrote, so two
 * messages published in the same second reliably lost one across a restart -- silently:
 * the kind:445 row was stored and no inner event came out of it.
 *
 * **What the move fixed.** mls-kmp's state format carries them. `MlsGroupState` has
 * `skippedApplicationSecrets` and `skippedHandshakeSecrets` from its STATE_VERSION 5,
 * `saveState` exports them from the secret tree and `restore` imports them back. The
 * engine-level gap the note describes is gone, and the first two tests here are that.
 *
 * **What it did not fix, and this is the finding.** `MlsGroupManager.decrypt` persists
 * only when the message was a COMMIT that advanced the epoch; an application message is
 * decrypted and nothing is written. So the secret reaches the live group's state and never
 * reaches the blob on disk, and a restart between two messages still loses the late one --
 * for a different reason than before. Not "the cache is never written" but "the state is
 * not saved after an application message", which is a decision about write amplification
 * rather than an oversight: persisting per inbound message re-encodes the ratchet tree,
 * the group context and every epoch secret, per message, growing with the group. The same
 * trade-off `MlsGroupStateStore.saveSenderRatchet` exists to solve on the send side.
 *
 * The third test pins that, in the style of `ConvergenceForkJvmTest`'s authored fork: it
 * asserts the gap, and says in its message what to do the day it stops holding.
 *
 * See docs/marmot-kmp.md, Phase 5.
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
     * The half the move did not close, pinned.
     *
     * `MlsGroupManager.decrypt` writes nothing for an application message -- only a COMMIT
     * that advanced the epoch is persisted -- so the secret above never reaches
     * `ChatRoom.mlsGroupState`, and a process that dies between the two messages still
     * loses the late one.
     *
     * The day this assertion fails, the manager has started persisting after a receive
     * (or the app has), docs/mls-skipped-keys.md can drop its mitigation section, and this
     * test becomes the other way round: assert the stored state carries the secret, and
     * add the restart that `MarmotEngine(db)` makes -- which is NOT asserted here on
     * purpose, because a rebuilt engine reads generation 0 happily today for the wrong
     * reason. It restores a blob whose sender ratchet never moved, so generation 0 is
     * simply the next one it expects, and the test would pass while testing nothing.
     */
    @Test
    fun `the stored state does not yet carry it, so a restart still loses the message`() = runBlocking {
        val roomId = joinedRoom()
        theirMessage(roomId, "generation 0")
        val second = theirMessage(roomId, "generation 1")

        store(second)

        assertTrue(
            liveState(roomId).skippedApplicationSecrets.isNotEmpty(),
            "the live group lost the secret too, which is a worse bug than this one",
        )
        assertTrue(
            storedState(roomId).skippedApplicationSecrets.isEmpty(),
            "the stored state carries the skipped secret -- the receive path now persists; " +
                "invert this test and see its KDoc",
        )
    }
}
