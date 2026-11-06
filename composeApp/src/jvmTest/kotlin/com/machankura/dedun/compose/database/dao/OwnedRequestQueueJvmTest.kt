package com.machankura.dedun.compose.database.dao

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import com.machankura.dedun.compose.cancelAndWait
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.toWalletId
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The two fetch queues, owned -- Phase 6 of docs/multiple-profiles.md.
 *
 * A request another identity queued waits for that identity, because what it brings
 * back is opened with that identity's key; a request nobody owns -- one from before the
 * column, or queued while nothing was open -- is anyone's. And the owner is the
 * repository's to stamp, from the identity that is open when the request is queued,
 * so that no call site can forget it.
 */
class OwnedRequestQueueJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val keyA = PrivateKey(ByteVector32("0a".repeat(32)))
    private val keyB = PrivateKey(ByteVector32("0b".repeat(32)))
    private val a = keyA.nostrPublicKeyHex()
    private val b = keyB.nostrPublicKeyHex()
    private val at = Instant.fromEpochSeconds(1_700_000_000)

    @AfterTest
    fun tearDown() {
        scope.cancelAndWait()
        db.close()
    }

    private fun sync(id: String, owner: String?, createdAt: Instant = at) = SynchronizeNostrEventRequest(
        id = id,
        purpose = "test",
        relayURL = "wss://relay.example",
        level = 0,
        synchronizationFilters = arrayOf(SynchronizationFilter(kinds = arrayOf(1))),
        ownerPublicKey = owner,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun negentropy(id: String, owner: String?, createdAt: Instant = at) = NegentropySynchronizeRequest(
        id = id,
        purpose = "test",
        relayURL = "wss://relay.example",
        level = 0,
        synchronizationFilter = SynchronizationFilter(kinds = arrayOf(1)),
        ownerPublicKey = owner,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun `a sync request is offered to its owner and to nobody else, and an unowned one to everyone`() = runBlocking {
        val dao = db.synchronizeNostrEventRequestDao()
        dao.insert(listOf(sync("a-1", owner = a)))

        assertEquals("a-1", dao.observeSynchronizeNostrEventRequestsByStatusFor("pending", a).first()?.id)
        assertNull(dao.observeSynchronizeNostrEventRequestsByStatusFor("pending", b).first(), "A's request is not B's to answer")

        dao.insert(listOf(sync("anyone", owner = null, createdAt = at.plus(kotlin.time.Duration.parse("1s")))))
        assertEquals("a-1", dao.observeSynchronizeNostrEventRequestsByStatusFor("pending", a).first()?.id, "oldest first, still")
        assertEquals("anyone", dao.observeSynchronizeNostrEventRequestsByStatusFor("pending", b).first()?.id, "nobody's is anyone's")
    }

    @Test
    fun `a negentropy request is offered the same way`() = runBlocking {
        val dao = db.negentropySynchronizeRequestDao()
        dao.insert(listOf(negentropy("a-1", owner = a), negentropy("anyone", owner = null, createdAt = at.plus(kotlin.time.Duration.parse("1s")))))

        assertEquals("a-1", dao.observeNegentropySynchronizeRequestsByStatusFor("pending", a).first()?.id)
        assertEquals("anyone", dao.observeNegentropySynchronizeRequestsByStatusFor("pending", b).first()?.id)
    }

    private fun prefsFile(name: String) =
        Files.createTempFile(name, ".preferences_pb").also { it.toFile().delete() }.toString().toPath()

    private fun identity(key: PrivateKey) = Identity.signing(
        id = key.publicKey().xOnly().toWalletId(),
        kind = IdentityKind.NostrSecret,
        nostrPrivateKey = key,
        userPrefs = UserPrefs(PreferenceDataStoreFactory.createWithPath { prefsFile("user") }),
        internalPrefs = InternalPrefs(PreferenceDataStoreFactory.createWithPath { prefsFile("internal") }),
        business = null,
    )

    @Test
    fun `the repository stamps what it queues with the identity that is open, and nothing when none is`() = runBlocking {
        val active = MutableStateFlow<Identity?>(null)
        val repository = DatabaseNostrRepository(db, scope, activeIdentity = active)

        repository.queueSynchronizeNostrEvent(listOf(sync("nobody", owner = null)))
        repository.queueNegentropySynchronizeRequest(listOf(negentropy("nobody-n", owner = null)))

        active.value = identity(keyA)
        repository.queueSynchronizeNostrEvent(listOf(sync("as-a", owner = null)))
        repository.queueNegentropySynchronizeRequest(listOf(negentropy("as-a-n", owner = null)))
        // A caller that named an owner keeps it.
        repository.queueSynchronizeNostrEvent(listOf(sync("named-b", owner = b)))

        val syncs = db.synchronizeNostrEventRequestDao().getAll().associate { it.id to it.ownerPublicKey }
        assertEquals(mapOf("nobody" to null, "as-a" to a, "named-b" to b), syncs)
        val negs = db.negentropySynchronizeRequestDao().getAll().associate { it.id to it.ownerPublicKey }
        assertEquals(mapOf("nobody-n" to null, "as-a-n" to a), negs)

        // A negentropy request carries its owner into the REQ it becomes.
        assertEquals(a, negentropy("x", owner = a).toSynchronizeNostrEventRequest().ownerPublicKey)
    }
}
