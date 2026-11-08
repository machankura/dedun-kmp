package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupArkArriving
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The table a boarding payment lands in before any round takes it in, and the reads the watch and the
 * screens need of it: `docs/group-wavelength-unconfirmed-deposits.md`, Phase 1.
 */
class GroupArkArrivingDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val wallet = "wavelength:room:regtest:0"
    private val other = "wavelength:room:regtest:1"
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun arriving(
        wallet: String = this.wallet,
        outpoint: String,
        amountSat: Long = 10_000,
        script: String = "5120" + "11".repeat(32),
        member: String? = alice,
        firstSeen: Long = 1_000,
        height: Long? = null,
    ) = GroupArkArriving(wallet, outpoint, amountSat, script, member, firstSeen, height)

    @Test
    fun `a row goes in and comes back whole`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertArriving(arriving(outpoint = "aa:0", height = 900))

        val row = dao.arriving(wallet).single()
        assertEquals("aa:0", row.outpoint)
        assertEquals(10_000, row.amountSat)
        assertEquals(alice, row.member)
        assertEquals(1_000, row.firstSeenAtEpochSeconds)
        assertEquals(900, row.blockHeight)
        assertNull(row.spentUnseenAtEpochSeconds)
    }

    @Test
    fun `one outpoint holds one row, and a later pass keeps the time it was first seen`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertArriving(arriving(outpoint = "aa:0"))
        // What the watch does on the pass that finds it confirmed: the height is new, the first sight is not.
        dao.upsertArriving(arriving(outpoint = "aa:0", height = 950, firstSeen = 1_000))

        val row = dao.arriving(wallet).single()
        assertEquals(950, row.blockHeight)
        assertEquals(1_000, row.firstSeenAtEpochSeconds, "a second pass must not restamp it")
    }

    @Test
    fun `a wallet reads only its own, and an outpoint can arrive at two wallets`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertArriving(arriving(outpoint = "aa:0"))
        dao.upsertArriving(arriving(outpoint = "bb:0", amountSat = 2_000))
        dao.upsertArriving(arriving(wallet = other, outpoint = "aa:0", amountSat = 3_000))

        assertEquals(listOf(2_000L, 10_000L), dao.arriving(wallet).map { it.amountSat }.sorted())
        assertEquals(listOf(3_000L), dao.arriving(other).map { it.amountSat })
    }

    @Test
    fun `the flow emits on an insert and again on a forget`() = runBlocking {
        val dao = db.groupArkDao()
        assertEquals(emptyList(), dao.observeArriving(wallet).first())

        dao.upsertArriving(arriving(outpoint = "aa:0"))
        assertEquals(listOf("aa:0"), dao.observeArriving(wallet).first().map { it.outpoint })

        dao.forgetArriving(wallet, "aa:0")
        assertEquals(emptyList(), dao.observeArriving(wallet).first())
    }

    @Test
    fun `forgetting one leaves the rest, and leaves another wallet's alone`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertArriving(arriving(outpoint = "aa:0"))
        dao.upsertArriving(arriving(outpoint = "bb:0"))
        dao.upsertArriving(arriving(wallet = other, outpoint = "aa:0"))

        dao.forgetArriving(wallet, "aa:0")

        assertEquals(listOf("bb:0"), dao.arriving(wallet).map { it.outpoint })
        assertEquals(listOf("aa:0"), dao.arriving(other).map { it.outpoint })
    }

    @Test
    fun `a coin a round this device never signed took in is marked, not dropped`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertArriving(arriving(outpoint = "aa:0", height = 900))

        dao.markArrivingSpentUnseen(wallet, "aa:0", 2_000)

        val row = dao.arriving(wallet).single()
        assertEquals(2_000, row.spentUnseenAtEpochSeconds)
        assertEquals(10_000, row.amountSat, "the coin is still what it was")
    }

    @Test
    fun `the wallets to watch are the ones with an answered boarding address`() = runBlocking {
        val dao = db.groupArkDao()
        // Asked for and answered: one script to watch.
        dao.insertDepositAddress(GroupArkDepositAddress(wallet, "r1", alice, Instant.fromEpochSeconds(1)))
        dao.answerDepositAddress(wallet, "r1", "bcrt1qaa", "0014aa")
        // Asked for and never answered: nothing to watch.
        dao.insertDepositAddress(GroupArkDepositAddress(other, "r2", bob, Instant.fromEpochSeconds(2)))
        // An invoice, which has no script at all.
        dao.insertDepositAddress(GroupArkDepositAddress(other, "r3", bob, Instant.fromEpochSeconds(3), invoiceAmountSat = 5_000))

        assertEquals(listOf(wallet), dao.walletsWithDepositAddresses())
        assertEquals(listOf(wallet), dao.observeWalletsWithDepositAddresses().first())
    }

    @Test
    fun `an earlier wallet's addresses are still watched`() = runBlocking {
        val dao = db.groupArkDao()
        dao.insertDepositAddress(GroupArkDepositAddress(wallet, "r1", alice, Instant.fromEpochSeconds(1)))
        dao.answerDepositAddress(wallet, "r1", "bcrt1qaa", "0014aa")
        dao.insertDepositAddress(GroupArkDepositAddress(other, "r2", alice, Instant.fromEpochSeconds(2)))
        dao.answerDepositAddress(other, "r2", "bcrt1qbb", "0014bb")

        // A boarding address is one member's for good and can be paid any time, so a wallet a newer one
        // replaced is still a wallet with money on its way in.
        assertEquals(setOf(wallet, other), dao.walletsWithDepositAddresses().toSet())
    }

    @Test
    fun `nothing here writes a deposit`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertArriving(arriving(outpoint = "aa:0", amountSat = 50_000))
        dao.markArrivingSpentUnseen(wallet, "aa:0", 2_000)

        // A2: a DepositRow is a payable balance the moment it exists, so this table must never become one.
        assertTrue(db.depositDao().inRoom("room", "regtest").isEmpty())
    }
}
