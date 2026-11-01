package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [NostrDao] is the funnel every event passes through, inbound and outbound, so its two
 * decisions are load-bearing for everything downstream: which of two copies of an event wins,
 * and what survives when the enrichment that follows a write fails.
 *
 * Both were reasoned about in comments rather than asserted. The publish path carries a
 * description of a bug that shipped -- indexing sharing the commit's transaction, so any throw
 * in it rolled back `signedAt` as well, leaving the notary to re-select the same unsigned row
 * forever and never sign anything queued behind it, including the MLS key package that goes
 * last.
 */
class NostrDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val author = keyPair.pubKey.toHexKey()
    private val relay = "wss://relay.example"

    /** A kind nothing dispatches on, so these tests see the funnel and not a kind handler. */
    private val inertKind = 31_337

    private fun event(
        id: String,
        createdAt: Instant,
        content: String = "original",
        kind: Int = inertKind,
        pubKey: String = author,
        unsignedNostrEventId: Long? = null,
    ) = NostrEvent(
        id = id.padEnd(64, '0'),
        pubKey = pubKey,
        kind = kind,
        tags = emptyArray(),
        content = content,
        sig = "0".repeat(128),
        createdAt = createdAt,
        unsignedNostrEventId = unsignedNostrEventId,
    )

    private suspend fun store(nostrEvent: NostrEvent) = db.nostrDao().storeNostrEvent(
        nostrEvent = nostrEvent,
        relayURL = relay,
        synchronizationRelayURLs = listOf(relay),
        level = 0,
        activeKeyPair = keyPair,
    )

    @Test
    fun `a first sighting of an event is stored`() = runBlocking {
        val incoming = event("1", Instant.fromEpochSeconds(1_000))

        store(incoming)

        assertEquals("original", db.nostrEventDao().getNostrEventById(incoming.id)?.content)
    }

    /**
     * Same id, later timestamp: the newer copy wins. Relays redeliver and negentropy re-syncs,
     * so an event arrives repeatedly and the funnel has to be idempotent in the right
     * direction.
     */
    @Test
    fun `a strictly newer copy of an event replaces the stored one`() = runBlocking {
        val first = event("1", Instant.fromEpochSeconds(1_000), content = "original")
        store(first)

        store(first.copy(createdAt = Instant.fromEpochSeconds(2_000), content = "newer"))

        assertEquals("newer", db.nostrEventDao().getNostrEventById(first.id)?.content)
    }

    @Test
    fun `an older copy of an event is ignored`() = runBlocking {
        val first = event("1", Instant.fromEpochSeconds(2_000), content = "original")
        store(first)

        store(first.copy(createdAt = Instant.fromEpochSeconds(1_000), content = "older"))

        assertEquals("original", db.nostrEventDao().getNostrEventById(first.id)?.content)
    }

    /**
     * The boundary. The comparison is a strict `>`, so a redelivery of the *same* event -- same
     * id, same timestamp, which is what a second relay hands over -- is a no-op rather than a
     * rewrite.
     */
    @Test
    fun `a redelivery at the same timestamp is a no-op`() = runBlocking {
        val first = event("1", Instant.fromEpochSeconds(1_000), content = "original")
        store(first)

        store(first.copy(content = "from another relay"))

        assertEquals("original", db.nostrEventDao().getNostrEventById(first.id)?.content)
    }

    /**
     * An event from an author with no profile leaves a placeholder behind rather than nothing.
     * The placeholder is stamped GENESIS_AT, which is the marker the sync path looks for --
     * without the row there is no record that this pubkey was ever seen and needs fetching.
     */
    @Test
    fun `an unknown author gets a placeholder profile to be synced later`() = runBlocking {
        val stranger = "f".repeat(64)

        store(event("1", Instant.fromEpochSeconds(1_000), pubKey = stranger))

        val profile = db.profileDao().getProfileByPublicKey(stranger)
        assertNotNull(profile, "no placeholder profile was created for an unseen author")
        assertEquals("LOADING...", profile.displayName)
    }

    /**
     * The documented split. `commitPublishedNostrEvent` is the durable half and indexing is
     * best-effort enrichment in its own transaction, so a throw in indexing must leave the
     * commit standing.
     *
     * Indexing is made to fail here the way the code itself would fail it: publishing with no
     * target relays reaches `relayURLs.first()` inside the try, which throws. The assertion is
     * that everything the durable half wrote is still there afterwards -- above all `signedAt`,
     * because the notary drains one unsigned row at a time and a row whose `signedAt` was
     * rolled back is re-selected forever, blocking every event queued behind it.
     */
    @Test
    fun `a failure while indexing does not roll back the published event`() = runBlocking<Unit> {
        val unsignedId = db.unsignedNostrEventDao().upsert(
            UnsignedNostrEvent(
                pubKey = author,
                kind = inertKind,
                tags = emptyArray(),
                content = "to be published",
            )
        )
        val unsigned = db.unsignedNostrEventDao().getAUnsignedNostrEvents().single { it.id == unsignedId }
        val signed = event("1", Instant.fromEpochSeconds(1_000), unsignedNostrEventId = unsignedId)

        db.nostrDao().publishNostrEvent(
            unsignedNostrEvent = unsigned,
            nostrEvent = signed,
            relayURLs = emptyList(),
            activeKeyPair = keyPair,
        )

        assertNotNull(
            db.unsignedNostrEventDao().getAUnsignedNostrEvents().single { it.id == unsignedId }.signedAt,
            "signedAt was rolled back, so the notary would re-select this row forever",
        )
        assertNotNull(
            db.nostrEventDao().getNostrEventById(signed.id),
            "the signed event was rolled back with the indexing failure",
        )
    }

    /** The happy path of the same split, so the test above is not passing for the wrong reason. */
    @Test
    fun `a published event is stored and queued for every target relay`() = runBlocking {
        val unsignedId = db.unsignedNostrEventDao().upsert(
            UnsignedNostrEvent(
                pubKey = author,
                kind = inertKind,
                tags = emptyArray(),
                content = "to be published",
            )
        )
        val unsigned = db.unsignedNostrEventDao().getAUnsignedNostrEvents().single { it.id == unsignedId }
        val signed = event("1", Instant.fromEpochSeconds(1_000), unsignedNostrEventId = unsignedId)
        val relays = listOf("wss://one.example", "wss://two.example")

        db.nostrDao().publishNostrEvent(
            unsignedNostrEvent = unsigned,
            nostrEvent = signed,
            relayURLs = relays,
            activeKeyPair = keyPair,
        )

        assertNotNull(db.nostrEventDao().getNostrEventById(signed.id))
        val queued = db.broadcastNostrEventRequestDao().getAllBroadcastNostrEventRequests()
            .filter { it.nostrEventId == signed.id }
        assertEquals(
            relays.toSet(),
            queued.map { it.relayURL }.toSet(),
            "a broadcast request is queued per target relay",
        )
    }

    /**
     * Rescheduling re-queues the broadcast and, where the event is a group message that already
     * has a chat line, re-links the two. Without the relation the line has no delivery state to
     * read and stays looking unsent no matter how the retry goes.
     */
    @Test
    fun `rescheduling relinks a broadcast to the chat line it belongs to`() = runBlocking {
        val groupEventId = "e".repeat(64)
        seedRoomWithChatLine(marmotGroupEventId = groupEventId)

        db.nostrDao().rescheduleBroadcastNostrEventRequests(
            listOf(BroadcastNostrEventRequest(nostrEventId = groupEventId, relayURL = relay))
        )

        val request = assertNotNull(
            db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(groupEventId),
            "the broadcast request was not queued",
        )
        val chatMessage = assertNotNull(
            db.chatMessageDao().getChatMessagesByMarmotGroupEventId(groupEventId)
        )
        val relation = assertNotNull(
            db.chatMessageBroadcastNostrEventRequestRelationDao()
                .getChatMessageBroadcastNostrEventRequestRelationByBroadcastRequest(request.id),
            "the re-queued broadcast was not linked back to its chat line",
        )
        assertEquals(chatMessage.id, relation.chatMessageId)
    }

    /**
     * The relation is conditional; the queueing is not. An event with no chat line -- anything
     * that is not a group message -- must still be re-queued for broadcast.
     *
     * The event itself has to exist: `BroadcastNostrEventRequest.nostrEventId` is a foreign key
     * onto NostrEvent, so "no chat line" is the only thing missing here. Writing this test
     * against an id that was never stored fails with SQLite 787 instead, which is worth knowing
     * -- a caller cannot schedule a broadcast for an event it has not saved.
     */
    @Test
    fun `rescheduling an event with no chat line still queues the broadcast`() = runBlocking {
        val plainEventId = "9".repeat(64)
        db.nostrEventDao().upsert(event(plainEventId, Instant.fromEpochSeconds(1_000)))

        db.nostrDao().rescheduleBroadcastNostrEventRequests(
            listOf(BroadcastNostrEventRequest(nostrEventId = plainEventId, relayURL = relay))
        )

        val request = assertNotNull(
            db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(plainEventId),
            "the broadcast was not queued just because there was no chat line to link",
        )
        assertNull(db.chatMessageDao().getChatMessagesByMarmotGroupEventId(plainEventId))
        assertNull(
            db.chatMessageBroadcastNostrEventRequestRelationDao()
                .getChatMessageBroadcastNostrEventRequestRelationByBroadcastRequest(request.id),
            "no chat line means no relation should have been invented",
        )
    }

    private suspend fun seedRoomWithChatLine(marmotGroupEventId: String) {
        val profileEventId = "c".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = profileEventId,
                pubKey = author,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(Profile(publicKey = author, userName = "author", nostrEventId = profileEventId))
        val roomId = "b".repeat(64)
        db.chatRoomDao().upsert(
            ChatRoom(
                id = roomId,
                userPublicKey = author,
                subject = null,
                description = null,
                mlsGroupState = null,
            )
        )
        db.nostrEventDao().upsert(
            event(marmotGroupEventId, Instant.fromEpochSeconds(1_000), kind = 445)
        )
        db.marmotGroupEventDao().upsert(
            com.machankura.dedun.compose.database.model.MarmotGroupEvent(
                id = marmotGroupEventId,
                userPublicKey = author,
                publicKey = author,
                chatRoomId = roomId,
                signature = "0".repeat(128),
                encryptedContent = "ciphertext",
                expiresAt = null,
            )
        )
        db.chatMessageDao().upsert(
            com.machankura.dedun.compose.database.model.ChatMessage(
                content = "a sent message",
                chatRoomId = roomId,
                senderPublicKey = author,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = marmotGroupEventId,
                marmotInnerEventId = null,
            )
        )
    }
}
