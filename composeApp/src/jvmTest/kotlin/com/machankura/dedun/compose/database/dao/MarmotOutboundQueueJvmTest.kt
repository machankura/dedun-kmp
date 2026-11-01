package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.exceptions.MarmotMissingChatGroupException
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.Relays
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The outbound queue the notary drains, and what a message it cannot send does to the rest.
 *
 * Nothing takes a row off this queue but a successful send: `encryptAndSendMarmotInnerEvent`
 * stamps the row with the group event it became, in the transaction that writes that event.
 * So a row that can never be sent never leaves, and while the queue handed back one row at a
 * time, that row was the queue. Every message behind it -- in every room the key belongs to,
 * because the filter is the sender rather than the room -- waited on a send that was not
 * coming, and showed in the chat as unsealed for as long as the user cared to look.
 *
 * These stand the two rooms side by side: one holding real MLS state, one holding none, which
 * is the shape a room restored from an inbound gift wrap has. The unsendable row is queued
 * *first* on purpose. Under the old queue it was the only row the notary ever saw.
 */
class MarmotOutboundQueueJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val user = keyPair.pubKey.toHexKey()
    private val signer = NostrSignerSync(keyPair)

    private val repository = DatabaseMarmotRepository(
        database = db,
        scope = CoroutineScope(Dispatchers.Default),
    )

    private val statelessRoomId = "a".repeat(64)
    private val mlsRoomId = "b".repeat(64)

    /** Participant/ChatRoom rows point at a Profile, which points at the event it came from. */
    private suspend fun seedProfile() {
        val nostrEventId = "c".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = user,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(Profile(publicKey = user, userName = "user", nostrEventId = nostrEventId))
    }

    /** A room with `mlsGroupState = null`: there is no group here to encrypt anything to. */
    private suspend fun seedStatelessRoom() {
        db.chatRoomDao().upsert(
            ChatRoom(
                id = statelessRoomId,
                userPublicKey = user,
                subject = "a room restored without its group",
                description = null,
                mlsGroupState = null,
            )
        )
    }

    /** A room holding real MLS state, as a room this device created does. */
    private suspend fun seedMlsRoom() {
        val mlsGroup = MlsGroup.create(
            identity = user.hexToByteArray(),
            initialExtensions = listOf(
                MarmotGroupData.bootstrap(
                    nostrGroupId = mlsRoomId,
                    creatorPubKey = user,
                    outboxRelays = Relays.DefaultDMRelayList.map { it.url },
                ).toExtension()
            ),
        )
        db.chatRoomDao().upsert(
            ChatRoom(
                id = mlsRoomId,
                userPublicKey = user,
                subject = "a room this device created",
                description = null,
                mlsGroupState = mlsGroup.saveState().encodeTls().toHex(),
            )
        )
    }

    private suspend fun queue(id: String, chatRoomId: String, text: String, atEpochSeconds: Long): MarmotInnerEvent {
        val row = MarmotInnerEvent(
            id = id,
            publicKey = user,
            chatRoomId = chatRoomId,
            kind = ChatEvent.KIND,
            tags = emptyArray(),
            content = text,
            createdAt = Instant.fromEpochSeconds(atEpochSeconds),
        )
        db.marmotInnerEventDao().upsert(row)
        return row
    }

    private suspend fun backlog() = db.marmotInnerEventDao()
        .observeUnprocessedMarmotInnerEvents(user)
        .first()

    /**
     * What the notary does with an emission, reduced to the part under test: every row, in
     * order, each one's failure its own. Returns what failed, so a test can assert that a row
     * was refused rather than quietly skipped.
     */
    private suspend fun drain(): List<Throwable> = backlog().mapNotNull { row ->
        runCatching { repository.encryptAndSendMarmotInnerEvent(row, signer) }.exceptionOrNull()
    }

    @Test
    fun `the queue hands back the whole backlog, oldest first`() = runBlocking {
        seedProfile()
        seedMlsRoom()
        queue(id = "2".repeat(64), chatRoomId = mlsRoomId, text = "second", atEpochSeconds = 200)
        queue(id = "1".repeat(64), chatRoomId = mlsRoomId, text = "first", atEpochSeconds = 100)

        assertEquals(
            listOf("first", "second"),
            backlog().map { it.content },
            "the notary is handed the backlog to work through, not just its head",
        )
    }

    /**
     * `createdAt` is stored to the second, so a burst of sends shares one. Without the `id`
     * tiebreak the order within that second is SQLite's business, and two passes over the same
     * backlog need not agree on it.
     */
    @Test
    fun `rows sharing a second come back in a stable order`() = runBlocking {
        seedProfile()
        seedMlsRoom()
        queue(id = "f".repeat(64), chatRoomId = mlsRoomId, text = "f", atEpochSeconds = 100)
        queue(id = "0".repeat(64), chatRoomId = mlsRoomId, text = "0", atEpochSeconds = 100)
        queue(id = "7".repeat(64), chatRoomId = mlsRoomId, text = "7", atEpochSeconds = 100)

        assertEquals(listOf("0", "7", "f"), backlog().map { it.content })
        assertEquals(listOf("0", "7", "f"), backlog().map { it.content }, "the order changed between passes")
    }

    /**
     * Both lookups were `?.let`, so this returned having done nothing at all: no send, no
     * error, and no mark on the row -- which is what made it a permanent head of the queue.
     */
    @Test
    fun `a message for a room with no mls state is refused rather than ignored`() = runBlocking<Unit> {
        seedProfile()
        seedStatelessRoom()
        val row = queue(id = "1".repeat(64), chatRoomId = statelessRoomId, text = "nowhere to send this", atEpochSeconds = 100)

        assertFailsWith<MarmotMissingChatGroupException> {
            repository.encryptAndSendMarmotInnerEvent(row, signer)
        }
    }

    @Test
    fun `a message for a room this device does not have is refused rather than ignored`() = runBlocking<Unit> {
        seedProfile()
        val row = queue(id = "1".repeat(64), chatRoomId = "d".repeat(64), text = "no such room", atEpochSeconds = 100)

        assertFailsWith<MarmotMissingChatGroupException> {
            repository.encryptAndSendMarmotInnerEvent(row, signer)
        }
    }

    /**
     * The one that matters. The unsendable row is the oldest, so it is what a queue that
     * yields only its head would offer forever; the message behind it is in a different room
     * and has everything it needs to go out.
     */
    @Test
    fun `a message that cannot be sent no longer holds up the ones behind it`() = runBlocking {
        seedProfile()
        seedStatelessRoom()
        seedMlsRoom()
        queue(id = "1".repeat(64), chatRoomId = statelessRoomId, text = "the stopper", atEpochSeconds = 100)
        queue(id = "2".repeat(64), chatRoomId = mlsRoomId, text = "sent anyway", atEpochSeconds = 200)

        val failures = drain()

        assertEquals(1, failures.size, "exactly the stopper should have failed")
        assertTrue(failures.single() is MarmotMissingChatGroupException)

        val sent = assertNotNull(db.marmotInnerEventDao().getByChatRoomAndKinds(mlsRoomId, listOf(ChatEvent.KIND)).singleOrNull())
        assertNotNull(sent.marmotGroupEventId, "the message behind the stopper was never sent")
        assertEquals(
            listOf("the stopper"),
            backlog().map { it.content },
            "the stopper stays queued and nothing else does",
        )
    }

    /**
     * Sent means handed to the broadcaster. `BroadcastNostrEventRequest` rows in "pending" are
     * the only thing that puts a kind:445 on a relay, so a row that got a group event but no
     * request is still a message nobody receives -- see 0211764.
     */
    @Test
    fun `a message past the stopper is queued for the relays`() = runBlocking {
        seedProfile()
        seedStatelessRoom()
        seedMlsRoom()
        queue(id = "1".repeat(64), chatRoomId = statelessRoomId, text = "the stopper", atEpochSeconds = 100)
        queue(id = "2".repeat(64), chatRoomId = mlsRoomId, text = "sent anyway", atEpochSeconds = 200)

        drain()

        val groupEventId = assertNotNull(
            db.marmotInnerEventDao().getByChatRoomAndKinds(mlsRoomId, listOf(ChatEvent.KIND)).single().marmotGroupEventId
        )
        val requests = db.broadcastNostrEventRequestDao().getAllBroadcastNostrEventRequests()
            .filter { it.nostrEventId == groupEventId }

        assertEquals(
            Relays.DefaultDMRelayList.size,
            requests.size,
            "one broadcast request per relay is what actually sends it",
        )
        assertTrue(requests.all { it.status == "pending" }, "pending is the only status the broadcaster looks at")
    }

    /**
     * Two messages queued for the same room go out in order and each under its own generation:
     * the send ratchets the room's MLS state forward and writes it back, and the next one is
     * encrypted against what it left behind. A drain that read the room once and reused it
     * would send both from the same generation, and the group could only read one of them.
     */
    @Test
    fun `two messages for one room each ratchet the group forward`() = runBlocking {
        seedProfile()
        seedMlsRoom()
        val stateBefore = assertNotNull(db.chatRoomDao().findChatRoomById(mlsRoomId)).chatRoom.mlsGroupState
        queue(id = "1".repeat(64), chatRoomId = mlsRoomId, text = "first", atEpochSeconds = 100)
        queue(id = "2".repeat(64), chatRoomId = mlsRoomId, text = "second", atEpochSeconds = 200)

        assertEquals(emptyList(), drain())

        assertEquals(emptyList(), backlog(), "both messages should have left the queue")
        val sent = db.marmotInnerEventDao().getByChatRoomAndKinds(mlsRoomId, listOf(ChatEvent.KIND))
        assertEquals(2, sent.mapNotNull { it.marmotGroupEventId }.distinct().size, "both were sent as the same event")
        assertTrue(
            assertNotNull(db.chatRoomDao().findChatRoomById(mlsRoomId)).chatRoom.mlsGroupState != stateBefore,
            "the ratchet never reached the database",
        )
    }

    /**
     * A refused send must leave nothing behind. `encryptAndSendMarmotInnerEvent` is one
     * transaction, so a failure that had written a group event or a broadcast request would
     * have the room believing it sent something the members will never see.
     */
    @Test
    fun `a refused send writes nothing`() = runBlocking {
        seedProfile()
        seedStatelessRoom()
        val row = queue(id = "1".repeat(64), chatRoomId = statelessRoomId, text = "the stopper", atEpochSeconds = 100)

        runCatching { repository.encryptAndSendMarmotInnerEvent(row, signer) }

        assertNull(backlog().single().marmotGroupEventId, "the refused row was marked as sent")
        assertEquals(emptyList(), db.broadcastNostrEventRequestDao().getAllBroadcastNostrEventRequests())
    }
}
