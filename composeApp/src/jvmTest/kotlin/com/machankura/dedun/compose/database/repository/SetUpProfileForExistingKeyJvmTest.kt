package com.machankura.dedun.compose.database.repository

import androidx.room3.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exit from a sign-in that found nothing, at the rows.
 *
 * `signInToProfile` plants one kind-0 row per signed-in key, stamped `GENESIS_AT`, and
 * `getLocalAccounts` is *every* kind-0 row. So giving the key a profile must write over
 * that row, not beside it: two kind-0 rows for one pubkey would be two accounts, and
 * `observeLocalAccount` would answer with whichever the query happened to return.
 */
class SetUpProfileForExistingKeyJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )
    private val scope = CoroutineScope(Job())
    private val repository = DatabaseNostrRepository(db, scope)

    private val publicKey = "a".repeat(64)

    @AfterTest
    fun closeDb() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `the placeholder is written over, once, and the rest of the bootstrap arrives beside it`() = runBlocking {
        repository.signInToProfile(publicKey)
        val placeholder = repository.getLocalAccounts().single().unsignedNostrEvent
        assertNotNull(placeholder)
        assertEquals(GENESIS_AT, placeholder.signedAt)

        repository.setUpProfileForExistingKey(
            publicKey = publicKey,
            placeholderUnsignedNostrEventId = placeholder.id,
            name = "Alan",
            biography = null,
        )

        val accounts = repository.getLocalAccounts()
        assertEquals(1, accounts.size, "one kind-0 row for the key, still")
        val profile = accounts.single().unsignedNostrEvent
        assertNotNull(profile)
        assertEquals(placeholder.id, profile.id, "the same row")
        assertNull(profile.signedAt, "unsigned again, so the notary signs it")
        assertTrue(profile.content.contains("Alan"), "carrying the profile the user typed")

        // The five other bootstrap events, queued for the notary alongside it.
        val queued = db.unsignedNostrEventDao().observeUnsignedNostrEvents(publicKey).first()
        assertEquals(6, queued.size)
        assertEquals(listOf(0), queued.filter { it.kind == 0 }.map { it.kind })
    }
}
