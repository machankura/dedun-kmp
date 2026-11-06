package com.machankura.dedun.compose.database.repository

import androidx.room3.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.cancelAndWait
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Signing in twice plants one account.
 *
 * Until the read-only kind nothing called `signInToProfile` twice for one pubkey: the
 * writers refused a second sign-in before it got there. Pasting the nsec of a key held
 * read-only is the first path that signs in a pubkey whose account already exists, and
 * a second placeholder would be two kind-0 rows for one pubkey -- two accounts
 * disagreeing about which is this one. `getLocalAccounts` is every kind-0 row, so the
 * count is the assertion.
 */
class SignInToProfileTwiceJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )
    private val scope = CoroutineScope(Job())
    private val repository = DatabaseNostrRepository(db, scope)

    private val publicKey = "a".repeat(64)
    private val someoneElse = "b".repeat(64)

    @AfterTest
    fun closeDb() {
        scope.cancelAndWait()
        db.close()
    }

    @Test
    fun `a second sign-in for the same key plants nothing, and another key still gets its own account`() = runBlocking {
        repository.signInToProfile(publicKey)
        repository.signInToProfile(publicKey)
        repository.signInToProfile(someoneElse)

        val accounts = repository.getLocalAccounts()

        assertEquals(listOf(publicKey, someoneElse).sorted(), accounts.mapNotNull { it.unsignedNostrEvent?.pubKey }.sorted())
        val placeholder = accounts.first { it.unsignedNostrEvent?.pubKey == publicKey }.unsignedNostrEvent!!
        assertEquals(GENESIS_AT, placeholder.signedAt, "the placeholder is what it was: signed at genesis, so the notary skips it")
    }
}
