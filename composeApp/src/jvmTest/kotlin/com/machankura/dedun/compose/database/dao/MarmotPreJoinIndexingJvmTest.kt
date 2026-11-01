package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * What a room does with the kind:445 events the group published before this
 * device was in it.
 *
 * They arrive whatever anyone wants: MLS gives a joiner the key schedule from
 * their own epoch forward, the relay gives them the whole room, and negentropy
 * syncs the lot down on first open. Every one of those events is permanently
 * unreadable, and reading them anyway is what put a run of "Undecryptable
 * Message" above the conversation a member had just been invited to.
 *
 * Asserted at the DAO because the decision is only worth anything where the
 * backlog is: `indexMarmotGroupEvent` for the events as they land, and
 * `reindexMarmotGroupEvents` for the replay that would otherwise spend a refused
 * decrypt on each of them every time a member asks a room to try again.
 *
 * The room here has no MLS state, so nothing this replays can be read. That is
 * the point: it separates events left alone because they predate the join from
 * events tried and failed, which is the whole distinction under test.
 */
class MarmotPreJoinIndexingJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val user = keyPair.pubKey.toHexKey()
    private val roomId = "a".repeat(64)
    private val joinedAt = Instant.fromEpochSeconds(5_000)

    private suspend fun seedRoom(joinedGroupAt: Instant? = joinedAt) {
        val profileEventId = "f".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = profileEventId,
                pubKey = user,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(
            Profile(publicKey = user, userName = "member", nostrEventId = profileEventId)
        )
        db.chatRoomDao().upsert(
            ChatRoom(
                id = roomId,
                userPublicKey = user,
                subject = null,
                description = null,
                mlsGroupState = null,
                joinedGroupAt = joinedGroupAt,
                createdAt = joinedGroupAt ?: joinedAt,
            )
        )
    }

    /** One of the group's kind:445 events, stored the way a sync stores it. */
    private suspend fun seedGroupEvent(id: String, createdAt: Instant): String {
        val eventId = id.padEnd(64, '0')
        db.nostrEventDao().upsert(
            NostrEvent(
                id = eventId,
                pubKey = "b".repeat(64),
                kind = 445,
                tags = arrayOf(arrayOf("h", roomId)),
                content = "ciphertext",
                sig = "0".repeat(128),
                createdAt = createdAt,
            )
        )
        return eventId
    }

    private suspend fun lineFor(groupEventId: String): ChatMessage? =
        db.chatMessageDao().getChatMessagesByMarmotGroupEventId(groupEventId)

    private suspend fun reindex() = db.nostrDao().reindexMarmotGroupEvents(
        chatRoomId = roomId,
        activeKeyPair = keyPair,
    )

    @Test
    fun `a message from before the join leaves no line in the transcript`() = runBlocking {
        seedRoom()
        val before = seedGroupEvent("1", Instant.fromEpochSeconds(4_000))

        reindex()

        assertNull(
            lineFor(before),
            "a message this device never held the epoch key for is not a message it can show",
        )
    }

    /**
     * The accounting. `stored` counts what the room holds, because it holds it,
     * and `predatingMembership` says how much of that a replay was never going to
     * touch; the rest counts only what it did touch. A sweep that reported the
     * pre-join backlog as failures said a room was broken when it was working
     * exactly as designed.
     */
    @Test
    fun `the pre-join backlog is counted apart from what was replayed`() = runBlocking {
        seedRoom()
        seedGroupEvent("1", Instant.fromEpochSeconds(3_000))
        seedGroupEvent("2", Instant.fromEpochSeconds(4_000))

        val report = reindex()

        assertEquals(2, report.stored)
        assertEquals(2, report.predatingMembership)
        assertEquals(0, report.unresolved)
        assertEquals(0, report.failed)
        assertEquals(0, report.recovered)
    }

    /**
     * The other half, and the reason this is a cutoff rather than a blanket
     * refusal to replay. An event from after the join that could not be read is a
     * message waiting on a commit that has not landed, which is exactly what a
     * replay exists to pick up.
     */
    @Test
    fun `an event from after the join is still replayed`() = runBlocking {
        seedRoom()
        seedGroupEvent("1", Instant.fromEpochSeconds(4_000))
        seedGroupEvent("2", Instant.fromEpochSeconds(6_000))

        val report = reindex()

        assertEquals(2, report.stored)
        assertEquals(1, report.predatingMembership)
        assertEquals(1, report.unresolved, "only the event from after the join was replayed")
    }

    /**
     * A room joined before `joinedGroupAt` existed. `createdAt` is the same
     * answer -- a joiner's row is written from the Welcome -- so the fix reaches
     * rooms that were already on the device rather than only ones joined since.
     */
    @Test
    fun `a room with no recorded join still holds back its pre-join backlog`() = runBlocking {
        seedRoom(joinedGroupAt = null)
        val before = seedGroupEvent("1", Instant.fromEpochSeconds(4_000))

        val report = reindex()

        assertNull(lineFor(before))
        assertEquals(0, report.unresolved)
    }
}
