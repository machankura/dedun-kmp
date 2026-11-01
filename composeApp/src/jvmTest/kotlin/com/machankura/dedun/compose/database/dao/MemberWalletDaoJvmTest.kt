package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MemberWallet
import com.machankura.dedun.compose.database.model.MemberWalletSpend
import com.machankura.dedun.compose.managers.GroupFixtures
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Schema 30's two tables round-trip, are scoped by profile, and -- the property
 * that matters most -- outlive the room of the group that guards them.
 */
class MemberWalletDaoJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val group = "6a".repeat(32)
    private val alice = "a1".repeat(32)
    private val bob = "b0".repeat(32)

    private fun wallet(id: String, owner: String = alice) = MemberWallet(
        walletId = id,
        ownerPublicKey = owner,
        guardingChatRoomId = group,
        chain = "regtest",
        walletPrivateKey = "11".repeat(32),
        label = "Savings",
        requestJson = "{}",
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
    )

    @Test
    fun `a wallet round-trips and is listed for its owner only`(): Unit = runBlocking {
        val dao = db.memberWalletDao()
        dao.upsert(wallet("01".repeat(32)))
        dao.upsert(wallet("02".repeat(32), owner = bob))

        assertEquals(wallet("01".repeat(32)), dao.getById("01".repeat(32)))
        assertEquals(listOf("01".repeat(32)), dao.getByOwner(alice).map { it.walletId })
        assertEquals(2, dao.getByGuardingChatRoomId(group).size)
        assertFalse(wallet("01".repeat(32)).toString().contains("11".repeat(32)), "the key is never in a log line")
    }

    @Test
    fun `deleting the group's room leaves the key`(): Unit = runBlocking {
        GroupFixtures.seedRoom(db, group)
        db.memberWalletDao().upsert(wallet("01".repeat(32)))

        db.chatRoomDao().delete(assertNotNull(db.chatRoomDao().findChatRoomById(group)).chatRoom)

        assertNull(db.chatRoomDao().findChatRoomById(group))
        assertNotNull(db.memberWalletDao().getById("01".repeat(32)))
    }

    @Test
    fun `a spend round-trips and is filled in`(): Unit = runBlocking {
        val dao = db.memberWalletDao()
        val spend = MemberWalletSpend(txid = "aa".repeat(32), walletId = "01".repeat(32), psbt = "cHNidP8=", signedTransaction = "00")
        dao.upsertSpend(spend)
        dao.upsertSpend(spend.copy(seenAt = Instant.fromEpochSeconds(1_700_000_100)))

        assertEquals(Instant.fromEpochSeconds(1_700_000_100), dao.getSpend("aa".repeat(32))?.seenAt)
        assertEquals(1, dao.getSpends("01".repeat(32)).size)
    }
}
