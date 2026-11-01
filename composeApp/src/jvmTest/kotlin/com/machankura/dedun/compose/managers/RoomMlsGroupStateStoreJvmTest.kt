package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.mls.codec.TlsWriter
import com.vitorpamplona.quartz.marmot.mls.group.RetainedEpochSecrets
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotRouting
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.extensions.toHex
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The library's store contract over the app's tables: keyed by routing address,
 * resolved to the room, and the retained epochs as rows rather than a map.
 */
class RoomMlsGroupStateStoreJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val store = RoomMlsGroupStateStore(db)

    @AfterTest
    fun closeDb() = db.close()

    private val roomId = "a".repeat(64)
    private val address = "b".repeat(64)
    private val user = "c".repeat(64)

    private suspend fun seedRoom(routed: Boolean) {
        // A room's user is a Profile, and a Profile hangs off its kind 0.
        db.nostrEventDao().upsert(NostrEvent(id = "e".repeat(64), pubKey = user, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = user, userName = "user", nostrEventId = "e".repeat(64)))
        db.chatRoomDao().upsert(ChatRoom(id = roomId, userPublicKey = user, subject = null, description = null, mlsGroupState = "00"))
        if (routed) db.marmotRoutingDao().upsert(MarmotRouting(routingId = address, chatRoomId = roomId))
    }

    private fun retained(epoch: Long) = TlsWriter().also { writer ->
        RetainedEpochSecrets(
            epoch = epoch,
            senderDataSecret = ByteArray(32) { epoch.toByte() },
            encryptionSecret = ByteArray(32) { (epoch + 1).toByte() },
            leafCount = 3,
            exporterSecret = ByteArray(32) { (epoch + 2).toByte() },
        ).encodeTls(writer)
    }.toByteArray()

    @Test
    fun `a routed room is listed, saved and loaded by its address`() = runBlocking {
        seedRoom(routed = true)

        assertEquals(listOf(address), store.listGroups())
        store.save(address, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.load(address))
        assertEquals("010203", db.chatRoomDao().getMlsGroupState(roomId))
    }

    @Test
    fun `a room with no routing row is its own address`() = runBlocking {
        seedRoom(routed = false)

        assertEquals(listOf(roomId), store.listGroups())
        store.save(roomId, byteArrayOf(9))
        assertEquals("09", db.chatRoomDao().getMlsGroupState(roomId))
    }

    @Test
    fun `a save for a room that does not exist is dropped`() = runBlocking {
        store.save("d".repeat(64), byteArrayOf(1))

        assertNull(store.load("d".repeat(64)))
        assertEquals(emptyList(), store.listGroups())
    }

    @Test
    fun `retained epochs round-trip as rows and are replaced whole`() = runBlocking {
        seedRoom(routed = true)

        store.saveRetainedEpochs(address, listOf(retained(1), retained(2)))
        assertEquals(listOf(1L, 2L), db.marmotRetainedEpochSecretDao().getMarmotRetainedEpochSecretForChatRoomId(roomId).map { it.epoch }.sorted())
        assertEquals(listOf(retained(1).toHex(), retained(2).toHex()), store.loadRetainedEpochs(address).map { it.toHex() })

        // The manager's window trims from the front; the rows follow it.
        store.saveRetainedEpochs(address, listOf(retained(2), retained(3)))
        assertEquals(listOf(2L, 3L), db.marmotRetainedEpochSecretDao().getMarmotRetainedEpochSecretForChatRoomId(roomId).map { it.epoch }.sorted())
    }

    @Test
    fun `deleting a group clears its state and its epochs`() = runBlocking {
        seedRoom(routed = true)
        store.saveRetainedEpochs(address, listOf(retained(1)))

        store.delete(address)

        assertNull(store.load(address))
        assertEquals(emptyList(), store.loadRetainedEpochs(address))
    }
}
