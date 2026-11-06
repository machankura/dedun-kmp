package com.machankura.dedun.compose.database.repository

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.cancelAndWait
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.nostr.SignInSync
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Forgetting a key at the rows: the account it made goes, and so does everything that
 * hung off it -- while an unrelated account is untouched.
 */
class ForgetLocalAccountJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )
    private val scope = CoroutineScope(Job())
    private val repository = DatabaseNostrRepository(db, scope)

    private val forgotten = "a".repeat(64)
    private val kept = "b".repeat(64)

    @AfterTest
    fun closeDb() {
        scope.cancelAndWait()
        db.close()
    }

    @Test
    fun `the account and its requests go, the other account stays`() = runBlocking {
        repository.signInToProfile(forgotten)
        repository.signInToProfile(kept)
        val account = repository.getLocalAccounts().first { it.unsignedNostrEvent?.pubKey == forgotten }
        val placeholderId = assertNotNull(account.unsignedNostrEvent).id
        repository.queueSynchronizeNostrEvent(
            SignInSync.requests(placeholderId, forgotten, listOf(RelayUrlNormalizer.normalize("wss://one.example")), level = 0)
        )
        assertEquals(1, repository.observeLocalAccount(forgotten).first()?.synchronizeNostrEventRequests?.size)

        repository.forgetLocalAccount(forgotten)

        assertEquals(listOf(kept), repository.getLocalAccounts().map { it.unsignedNostrEvent?.pubKey })
        assertEquals(0, db.synchronizeNostrEventRequestDao().observeSynchronizeNostrEventRequestByPurposeAndStatusCount(SignInSync.PURPOSE, listOf("pending")).first(),
            "the requests cascaded with the row they hung off")
    }
}
