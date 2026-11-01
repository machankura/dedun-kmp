package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.nostr.SignInSync
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * `markCompleteIfSent` is conditional in SQL for a reason worth pinning: the handler
 * that moves a request to `processed` when an event arrives runs in its own coroutine
 * and can land after the subscription has closed. A completion that overwrote it would
 * turn "found" back into "found nothing".
 */
class SynchronizeNostrEventRequestCompletionJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val at = Instant.fromEpochSeconds(1_700_000_000)
    private val later = Instant.fromEpochSeconds(1_700_000_100)

    private suspend fun placeholder(): Long = db.unsignedNostrEventDao().upsert(
        UnsignedNostrEvent(pubKey = "a".repeat(64), kind = 0, tags = emptyArray(), content = "{}", createdAt = at)
    )

    private fun request(id: String, status: String, unsignedNostrEventId: Long) = SynchronizeNostrEventRequest(
        id = id,
        purpose = SignInSync.PURPOSE,
        status = status,
        relayURL = "wss://$id.example",
        level = 0,
        synchronizationFilters = arrayOf(SynchronizationFilter(authors = arrayOf("a".repeat(64)), kinds = SignInSync.KINDS)),
        unsignedNostrEventId = unsignedNostrEventId,
        createdAt = at,
        updatedAt = at,
    )

    private suspend fun statusOf(unsignedNostrEventId: Long, id: String): String? =
        db.unsignedNostrEventDao().observeProfile("a".repeat(64)).first()
            ?.synchronizeNostrEventRequests?.firstOrNull { it.id == id }?.status

    @Test
    fun `a sent request becomes complete, a processed or pending one is left alone`() = runBlocking {
        val accountId = placeholder()
        val dao = db.synchronizeNostrEventRequestDao()
        dao.insert(
            listOf(
                request("sent", "sent", accountId),
                request("processed", "processed", accountId),
                request("pending", "pending", accountId),
            )
        )

        val changed = listOf("sent", "processed", "pending").sumOf { dao.markCompleteIfSent(it, SignInSync.STATUS_COMPLETE, later) }

        assertEquals(1, changed)
        assertEquals(SignInSync.STATUS_COMPLETE, statusOf(accountId, "sent"))
        assertEquals("processed", statusOf(accountId, "processed"))
        assertEquals("pending", statusOf(accountId, "pending"))
    }
}
