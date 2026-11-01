package com.machankura.dedun.compose.database.model

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A kind:1210 or kind:1009 from another client is not a line. docs/marmot-v2.md,
 * Phase 7: the system row is derived from the state this device holds, never
 * received, and an edit is not applied; an unknown kind of any other number is
 * still the raw-JSON bubble it always was, because that bubble is the only trace
 * an unrecognised payload leaves.
 */
class MarmotSystemRowJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val member = KeyPair()

    private fun inner(kind: Int, content: String): Event =
        NostrSignerSync(member).sign<Event>(createdAt = 1_700_000_000L, kind = kind, tags = emptyArray(), content = content)

    private suspend fun apply(event: Event) = ChatMessage.applyInnerEvent(
        database = db,
        groupId = "room",
        event = event,
        marmotGroupEventId = null,
        marmotInnerEventId = event.id,
        senderPublicKey = event.pubKey,
        isUserMessage = false,
        createdAt = Instant.fromEpochSeconds(event.createdAt),
    )

    @Test
    fun `a received system row and a received edit make no line`() = runBlocking {
        assertNull(apply(inner(MarmotAppEvent.KIND_SYSTEM, """{"type":"member_added","account":"abcd"}""")))
        assertNull(apply(inner(MarmotAppEvent.KIND_EDIT, "the corrected text")))
    }

    @Test
    fun `any other unknown kind is still the bubble`() = runBlocking {
        val row = apply(inner(31337, "whatever this is"))
        assertEquals("unsupported", row?.messageType)
    }
}
