package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The notary's unsigned queue, and what an event it cannot publish does to the rest.
 *
 * The last of the three queues to hand back one row at a time, and the only one already
 * observed to stall: the comment on `NostrDao.commitPublishedNostrEvent` records an indexing
 * throw rolling `signedAt` back, after which nothing queued behind that row was ever signed --
 * "including the MLS key package, which is enqueued last". That was closed by moving indexing
 * into its own transaction (covered in [NostrDaoJvmTest]), which fixed the one known way in
 * and left the queue as narrow as it was.
 *
 * These cover the width instead. `signedAt` is the only exit, so a row that cannot be
 * published stays; what matters is that it is no longer the only row the notary is shown. The
 * kinds used are the ones a real account queues -- 0 metadata, 3 contacts, 10012 relay feeds
 * (the one carrying `privateTags`, and so the only one whose publish encrypts first), 10050
 * and 10051 relay lists, 30443 key package -- because their sort order is the whole reason a
 * stall in the middle of that burst leaves a user nobody can reach.
 *
 * What is asserted here is the DAO's half: that the backlog arrives whole and in a stable
 * order, and that publishing some rows neither disturbs nor depends on the others. The loop
 * that walks it lives in NotaryViewModel, which wants an Identity flow to stand up; the
 * [drain] below is shaped like it but is this test's own, so it pins the queue rather than the
 * collector.
 */
class UnsignedNostrEventQueueJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val author = keyPair.pubKey.toHexKey()
    private val signer = NostrSignerSync(keyPair)
    private val relays = listOf("wss://one.example", "wss://two.example")

    private suspend fun queue(
        kind: Int,
        content: String,
        pubKey: String = author,
        privateTags: Array<Array<String>>? = null,
    ): Long = db.unsignedNostrEventDao().upsert(
        UnsignedNostrEvent(
            pubKey = pubKey,
            kind = kind,
            tags = emptyArray(),
            privateTags = privateTags,
            content = content,
            createdAt = Instant.fromEpochSeconds(1_000),
        )
    )

    private suspend fun backlog(publicKey: String = author) = db.unsignedNostrEventDao()
        .observeUnsignedNostrEvents(publicKey)
        .first()

    /** Sign and publish one row exactly as the notary does, private tags and all. */
    private suspend fun publish(unsigned: UnsignedNostrEvent) {
        val event = signer.signNormal<Event>(
            createdAt = unsigned.createdAt.epochSeconds,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.privateTags?.let { PrivateTagsInContent.encryptNip44(it, signer) }
                ?: unsigned.content,
        )

        db.nostrDao().publishNostrEvent(
            unsignedNostrEvent = unsigned,
            nostrEvent = NostrEvent(
                id = event.id,
                pubKey = event.pubKey,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
                createdAt = Instant.fromEpochSeconds(event.createdAt),
                sig = event.sig,
                unsignedNostrEventId = unsigned.id,
            ),
            relayURLs = relays,
            activeKeyPair = keyPair,
        )
    }

    /**
     * A pass over the backlog with [failKinds] standing in for rows the notary cannot publish.
     * A real failure writes nothing -- `commitPublishedNostrEvent` is one transaction -- so
     * skipping the row leaves the database in the state a throw would have.
     */
    private suspend fun drain(failKinds: Set<Int> = emptySet()): List<Int> {
        val failed = mutableListOf<Int>()
        backlog().forEach { unsigned ->
            if (unsigned.kind in failKinds) {
                failed += unsigned.kind
            } else {
                publish(unsigned)
            }
        }
        return failed
    }

    @Test
    fun `the queue hands back the whole backlog, lowest kind first`() = runBlocking {
        queue(kind = 30_443, content = "key package")
        queue(kind = 0, content = "metadata")
        queue(kind = 10_050, content = "dm relays")
        queue(kind = 3, content = "contacts")

        assertEquals(
            listOf(0, 3, 10_050, 30_443),
            backlog().map { it.kind },
            "the notary is handed the backlog to work through, not just its lowest kind",
        )
    }

    /**
     * `id` is the autogenerated row id, so this is the order they were queued in. For a
     * replaceable kind it is the difference between the newest version standing and an older
     * one being published last and winning.
     */
    @Test
    fun `events of one kind come back in the order they were queued`() = runBlocking {
        queue(kind = 1, content = "first")
        queue(kind = 1, content = "second")
        queue(kind = 1, content = "third")

        assertEquals(listOf("first", "second", "third"), backlog().map { it.content })
        assertEquals(
            listOf("first", "second", "third"),
            backlog().map { it.content },
            "the order changed between passes",
        )
    }

    @Test
    fun `only this key's events are in its backlog`() = runBlocking {
        val stranger = KeyPair().pubKey.toHexKey()
        queue(kind = 0, content = "ours")
        queue(kind = 0, content = "theirs", pubKey = stranger)

        assertEquals(listOf("ours"), backlog().map { it.content })
        assertEquals(listOf("theirs"), backlog(stranger).map { it.content })
    }

    @Test
    fun `a published event leaves the queue`() = runBlocking {
        queue(kind = 0, content = "metadata")

        drain()

        assertEquals(emptyList(), backlog(), "a signed row is still being offered")
    }

    /**
     * The one that matters, and the shape of the stall this queue is most exposed to: 10012 is
     * the only row of the account burst carrying private tags, and it sorts ahead of both relay
     * lists a peer needs to find this user and ahead of the key package they need to invite
     * them. A queue that yields only its head would offer 10012 forever and publish none of the
     * three.
     */
    @Test
    fun `an event that cannot be published no longer hides the ones behind it`() = runBlocking {
        queue(kind = 0, content = "metadata")
        queue(kind = 10_012, content = "relay feeds", privateTags = arrayOf(arrayOf("relay", "wss://private.example")))
        queue(kind = 10_050, content = "dm relays")
        queue(kind = 10_051, content = "key package relays")
        queue(kind = 30_443, content = "key package")

        assertEquals(listOf(10_012), drain(failKinds = setOf(10_012)))

        assertEquals(
            listOf(10_012),
            backlog().map { it.kind },
            "the stopper stays queued and nothing else does",
        )
        assertEquals(
            listOf(0, 10_050, 10_051, 30_443),
            db.unsignedNostrEventDao().getAUnsignedNostrEvents()
                .filter { it.pubKey == author && it.signedAt != null }
                .map { it.kind }
                .sorted(),
            "the events either side of the stopper were not published",
        )
    }

    /**
     * Published means queued for the relays. A row that got `signedAt` but no
     * BroadcastNostrEventRequest is still an event no relay ever sees, which is the same
     * silence in a different place -- see 0211764.
     */
    @Test
    fun `an event past the stopper is queued for every relay`() = runBlocking {
        queue(kind = 10_012, content = "relay feeds", privateTags = arrayOf(arrayOf("relay", "wss://private.example")))
        queue(kind = 30_443, content = "key package")

        drain(failKinds = setOf(10_012))

        val keyPackage = assertNotNull(
            db.unsignedNostrEventDao().getAUnsignedNostrEvents().singleOrNull { it.kind == 30_443 }
        )
        assertNotNull(keyPackage.signedAt)
        val queued = db.broadcastNostrEventRequestDao().getAllBroadcastNostrEventRequests()
            .filter { it.unsignedNostrEventId == keyPackage.id }
        assertEquals(relays.toSet(), queued.map { it.relayURL }.toSet())
        assertTrue(queued.all { it.status == "pending" }, "pending is the only status the broadcaster looks at")
    }

    /** A skipped row must be left exactly as it was, or the next pass has nothing to retry. */
    @Test
    fun `the stopper is not marked signed`() = runBlocking {
        queue(kind = 10_012, content = "relay feeds")
        queue(kind = 30_443, content = "key package")

        drain(failKinds = setOf(10_012))

        assertNull(
            backlog().single().signedAt,
            "a row still on the queue cannot also be signed",
        )
    }
}
