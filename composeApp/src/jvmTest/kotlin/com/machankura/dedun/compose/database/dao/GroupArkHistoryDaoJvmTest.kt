package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkFee
import com.machankura.dedun.compose.database.model.GroupArkLeave
import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.database.model.GroupArkRound
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reads a Wavelength history needs, and the times schema 44 keeps:
 * `docs/group-wavelength-transaction-details.md`, Phase 2.
 *
 * The existing reads were each shaped for the co-signer's own question -- one commitment's boarding coins,
 * one term's fees -- and a history asks a different one: everything this wallet has ever done.
 */
class GroupArkHistoryDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val wallet = "regtest:room:0"
    private val other = "regtest:room:1"

    @Test
    fun `every boarding coin of a wallet, not just one commitment's`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertBoarded(GroupArkBoarded(wallet, "a:0", "first", 1_000, "0014aa", 100))
        dao.upsertBoarded(GroupArkBoarded(wallet, "b:0", "second", 2_000, "0014bb", 200))
        dao.upsertBoarded(GroupArkBoarded(other, "c:0", "third", 3_000, "0014cc", 300))

        assertEquals(listOf(1_000L, 2_000L), dao.boarded(wallet).map { it.amountSat }.sorted())
        assertEquals(listOf(1_000L), dao.boardedIn(wallet, "first").map { it.amountSat })
        assertEquals(100, dao.boarded(wallet).first { it.outpoint == "a:0" }.atEpochSeconds)
    }

    @Test
    fun `every claim of a wallet, and the time it was signed`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertClaim(GroupArkClaim(wallet, "ark1", "a".repeat(64), 5_000, 400))
        dao.upsertClaim(GroupArkClaim(wallet, "ark2", "b".repeat(64), 6_000))
        dao.upsertClaim(GroupArkClaim(other, "ark3", "c".repeat(64), 7_000, 500))

        val claims = dao.claims(wallet).associateBy { it.ark }
        assertEquals(2, claims.size)
        assertEquals(400, claims.getValue("ark1").atEpochSeconds)
        assertNull(claims.getValue("ark2").atEpochSeconds, "a row written without one keeps none")
    }

    @Test
    fun `every fee of a wallet, in any term, beside the one term the budget asks for`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertFee(GroupArkFee(wallet, "tx1", 30, term = 1, atEpochSeconds = 1_000))
        dao.upsertFee(GroupArkFee(wallet, "tx2", 40, term = 2, atEpochSeconds = 2_000))
        dao.upsertFee(GroupArkFee(other, "tx3", 50, term = 2, atEpochSeconds = 2_000))

        assertEquals(70, dao.allFees(wallet).sumOf { it.feeSat })
        assertEquals(40, dao.fees(wallet, term = 2, since = 0).sumOf { it.feeSat })
    }

    @Test
    fun `a payout keeps when this device signed it`() = runBlocking {
        val dao = db.groupArkDao()
        dao.insertPayment(GroupArkPayment(wallet, "a".repeat(64), "ark", amountSat = 1_200, atEpochSeconds = 900))
        assertEquals(900, assertNotNull(dao.payments(wallet).singleOrNull()).atEpochSeconds)
    }

    @Test
    fun `a round keeps when this device signed it, so one the free window paid for still has a place`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertRound(GroupArkRound(wallet, "commitment", "note", 800))
        assertEquals(800, assertNotNull(dao.rounds(wallet).singleOrNull()).atEpochSeconds)
    }

    @Test
    fun `a leave keeps when it was signed, and confirming one records when`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertLeave(GroupArkLeave(wallet, "approval", "commitment", "0014bb", 3_000, 180, atEpochSeconds = 600))
        assertNull(assertNotNull(dao.leaves(wallet).singleOrNull()).confirmedAtEpochSeconds)

        dao.confirmLeaves(wallet, "commitment", 700)
        val confirmed = assertNotNull(dao.leaves(wallet).singleOrNull())
        assertTrue(confirmed.confirmed)
        assertEquals(600, confirmed.atEpochSeconds)
        assertEquals(700, confirmed.confirmedAtEpochSeconds, "the one moment a payout finished that nobody has to be believed about")
    }

    @Test
    fun `confirming leaves one commitment's does not touch another's`() = runBlocking {
        val dao = db.groupArkDao()
        dao.upsertLeave(GroupArkLeave(wallet, "one", "first", "0014aa", 1_000, 10, atEpochSeconds = 600))
        dao.upsertLeave(GroupArkLeave(wallet, "two", "second", "0014bb", 2_000, 20, atEpochSeconds = 601))

        dao.confirmLeaves(wallet, "first", 700)
        val byApproval = dao.leaves(wallet).associateBy { it.authorisation }
        assertEquals(700, byApproval.getValue("one").confirmedAtEpochSeconds)
        assertNull(byApproval.getValue("two").confirmedAtEpochSeconds)
    }
}
