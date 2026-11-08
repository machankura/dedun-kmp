package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.GroupArkArriving
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkCheckpoint
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.database.model.GroupArkChecked
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkFee
import com.machankura.dedun.compose.database.model.GroupArkFloor
import com.machankura.dedun.compose.database.model.GroupArkLeave
import com.machankura.dedun.compose.database.model.GroupArkMark
import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.database.model.GroupArkPendingRound
import com.machankura.dedun.compose.database.model.GroupArkRound
import com.machankura.dedun.compose.database.model.GroupArkSnapshotRow
import com.machankura.dedun.compose.database.model.GroupArkTreeBinding
import com.machankura.dedun.compose.database.model.GroupArkTreeShare
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupArkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTreeShare(row: GroupArkTreeShare)

    @Query("SELECT * FROM GroupArkTreeShare WHERE wallet = :wallet")
    suspend fun treeShares(wallet: String): List<GroupArkTreeShare>

    @Query("SELECT MAX(child) FROM GroupArkTreeShare WHERE wallet = :wallet AND participant = :participant")
    suspend fun highestTreeShare(wallet: String, participant: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDepositAddress(row: GroupArkDepositAddress)

    @Query("SELECT COUNT(*) FROM GroupArkDepositAddress WHERE address IS NULL AND invoice IS NULL")
    fun observeUnansweredDeposits(): Flow<Int>

    @Query("UPDATE GroupArkDepositAddress SET address = :address, script = :script WHERE wallet = :wallet AND requestId = :requestId AND address IS NULL")
    suspend fun answerDepositAddress(wallet: String, requestId: String, address: String, script: String)

    @Query("UPDATE GroupArkDepositAddress SET invoice = :invoice, paymentHash = :paymentHash WHERE wallet = :wallet AND requestId = :requestId AND invoice IS NULL")
    suspend fun answerDepositInvoice(wallet: String, requestId: String, invoice: String, paymentHash: String)

    @Upsert
    suspend fun upsertClaim(row: GroupArkClaim)

    @Query("SELECT * FROM GroupArkClaim WHERE wallet = :wallet AND ark = :ark")
    suspend fun claim(wallet: String, ark: String): GroupArkClaim?

    @Query("SELECT * FROM GroupArkClaim WHERE wallet = :wallet")
    fun observeClaims(wallet: String): Flow<List<GroupArkClaim>>

    @Query("DELETE FROM GroupArkClaim WHERE wallet = :wallet AND ark = :ark")
    suspend fun forgetClaim(wallet: String, ark: String)

    /** Every claim of this wallet not yet seen settle, for the history. */
    @Query("SELECT * FROM GroupArkClaim WHERE wallet = :wallet")
    suspend fun claims(wallet: String): List<GroupArkClaim>

    @Query("SELECT * FROM GroupArkDepositAddress WHERE wallet = :wallet")
    suspend fun depositAddresses(wallet: String): List<GroupArkDepositAddress>

    @Query("SELECT * FROM GroupArkDepositAddress WHERE wallet = :wallet")
    fun observeDepositAddresses(wallet: String): Flow<List<GroupArkDepositAddress>>

    @Upsert
    suspend fun upsertBoarded(row: GroupArkBoarded)

    @Query("SELECT * FROM GroupArkBoarded WHERE wallet = :wallet AND commitment = :commitment")
    suspend fun boardedIn(wallet: String, commitment: String): List<GroupArkBoarded>

    /** Every boarding coin of this wallet still waiting on its round, for the history. */
    @Query("SELECT * FROM GroupArkBoarded WHERE wallet = :wallet")
    suspend fun boarded(wallet: String): List<GroupArkBoarded>

    @Query("SELECT * FROM GroupArkBoarded WHERE wallet = :wallet")
    fun observeBoarded(wallet: String): Flow<List<GroupArkBoarded>>

    @Query("DELETE FROM GroupArkBoarded WHERE wallet = :wallet AND commitment = :commitment")
    suspend fun forgetBoarded(wallet: String, commitment: String)

    @Query("DELETE FROM GroupArkBoarded WHERE wallet = :wallet AND commitment = :commitment AND outpoint = :outpoint")
    suspend fun forgetBoardedCoin(wallet: String, commitment: String, outpoint: String)

    @Upsert
    suspend fun upsertArriving(row: GroupArkArriving)

    /** Every boarding payment this device has seen on chain and no round has taken in yet, for the history. */
    @Query("SELECT * FROM GroupArkArriving WHERE wallet = :wallet")
    suspend fun arriving(wallet: String): List<GroupArkArriving>

    @Query("SELECT * FROM GroupArkArriving WHERE wallet = :wallet")
    fun observeArriving(wallet: String): Flow<List<GroupArkArriving>>

    @Query("DELETE FROM GroupArkArriving WHERE wallet = :wallet AND outpoint = :outpoint")
    suspend fun forgetArriving(wallet: String, outpoint: String)

    /** A coin a round this device didn't sign has taken in: kept, so that missing a round is something a member can see (A12). */
    @Query("UPDATE GroupArkArriving SET spentUnseenAtEpochSeconds = :atEpochSeconds WHERE wallet = :wallet AND outpoint = :outpoint")
    suspend fun markArrivingSpentUnseen(wallet: String, outpoint: String, atEpochSeconds: Long)

    /** Every wallet the group has been given a boarding address for, including earlier ones, whose addresses can still be paid. */
    @Query("SELECT DISTINCT wallet FROM GroupArkDepositAddress WHERE script IS NOT NULL")
    suspend fun walletsWithDepositAddresses(): List<String>

    /** Every wallet with an answered boarding address, so the watch follows one answered while the app is open. */
    @Query("SELECT DISTINCT wallet FROM GroupArkDepositAddress WHERE script IS NOT NULL")
    fun observeWalletsWithDepositAddresses(): Flow<List<String>>

    @Upsert
    suspend fun upsertSnapshot(row: GroupArkSnapshotRow)

    @Query("SELECT * FROM GroupArkSnapshotRow WHERE wallet = :wallet")
    suspend fun snapshot(wallet: String): GroupArkSnapshotRow?

    @Query("SELECT * FROM GroupArkSnapshotRow WHERE wallet = :wallet")
    fun observeSnapshot(wallet: String): Flow<GroupArkSnapshotRow?>

    @Query("SELECT DISTINCT wallet FROM GroupArkVtxo WHERE spent = 0")
    suspend fun walletsWithCoins(): List<String>

    @Upsert
    suspend fun upsertVtxo(row: GroupArkVtxo)

    @Query("SELECT * FROM GroupArkVtxo WHERE wallet = :wallet")
    suspend fun vtxos(wallet: String): List<GroupArkVtxo>

    @Query("SELECT * FROM GroupArkVtxo WHERE wallet = :wallet")
    fun observeVtxos(wallet: String): Flow<List<GroupArkVtxo>>

    @Query("UPDATE GroupArkVtxo SET settled = 1 WHERE wallet = :wallet AND origin = :origin")
    suspend fun settle(wallet: String, origin: String)

    /** What a commitment's height says of the coins it made: when their batch can be swept. */
    @Query("UPDATE GroupArkVtxo SET expiresAtHeight = :height + sweepDelay WHERE wallet = :wallet AND origin = :origin AND sweepDelay IS NOT NULL")
    suspend fun expireFrom(wallet: String, origin: String, height: Long)

    @Query("UPDATE GroupArkVtxo SET spent = 1 WHERE wallet = :wallet AND reservedBy = :by")
    suspend fun spendReservedBy(wallet: String, by: String)

    @Query("UPDATE GroupArkVtxo SET reservedBy = NULL, term = NULL WHERE wallet = :wallet AND reservedBy = :by AND spent = 0")
    suspend fun releaseReservedBy(wallet: String, by: String)

    @Query("DELETE FROM GroupArkVtxo WHERE wallet = :wallet AND origin = :origin AND settled = 0")
    suspend fun forgetPendingFrom(wallet: String, origin: String)

    @Query("DELETE FROM GroupArkFee WHERE wallet = :wallet AND tx = :tx")
    suspend fun forgetFee(wallet: String, tx: String)

    @Upsert
    suspend fun upsertPendingRound(row: GroupArkPendingRound)

    @Query("SELECT * FROM GroupArkPendingRound")
    suspend fun pendingRounds(): List<GroupArkPendingRound>

    @Query("SELECT COUNT(*) FROM GroupArkPendingRound")
    fun observePendingRounds(): Flow<Int>

    @Query("DELETE FROM GroupArkPendingRound WHERE wallet = :wallet AND commitment = :commitment")
    suspend fun deletePendingRound(wallet: String, commitment: String)

    /** Keeps a label already filed: an Ark transaction's signature comes after a checkpoint's, on a retry. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCheckpoint(row: GroupArkCheckpoint)

    @Query("UPDATE GroupArkCheckpoint SET label = :label WHERE wallet = :wallet AND ark = :ark AND checkpoint = :checkpoint")
    suspend fun signedCheckpoint(wallet: String, ark: String, checkpoint: String, label: String)

    @Query("SELECT * FROM GroupArkCheckpoint WHERE wallet = :wallet")
    suspend fun checkpoints(wallet: String): List<GroupArkCheckpoint>

    @Query("DELETE FROM GroupArkCheckpoint WHERE wallet = :wallet AND ark = :ark")
    suspend fun deleteCheckpoints(wallet: String, ark: String)

    @Upsert
    suspend fun upsertPayment(row: GroupArkPayment)

    @Query("SELECT * FROM GroupArkPayment WHERE wallet = :wallet")
    suspend fun payments(wallet: String): List<GroupArkPayment>

    /** The first record of a payment stands: a payment goes once, and its refund is never written over. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPayment(row: GroupArkPayment)

    /**
     * The refund [ark] of the payment whose vHTLC is [script], unless one already recorded has settled. One that was signed and never
     * happened gives way to the next, or a member would stay charged for money that came home by another transfer.
     */
    @Query(
        "UPDATE GroupArkPayment SET refundedIn = :ark WHERE wallet = :wallet AND vhtlcScript = :script AND (refundedIn IS NULL OR " +
            "refundedIn NOT IN (SELECT origin FROM GroupArkVtxo WHERE wallet = :wallet AND settled = 1 AND origin IS NOT NULL))"
    )
    suspend fun refundPayment(wallet: String, script: String, ark: String)

    @Upsert
    suspend fun upsertLeave(row: GroupArkLeave)

    @Query("SELECT * FROM GroupArkLeave WHERE wallet = :wallet")
    suspend fun leaves(wallet: String): List<GroupArkLeave>

    @Query("SELECT * FROM GroupArkLeave WHERE wallet = :wallet")
    fun observeLeaves(wallet: String): Flow<List<GroupArkLeave>>

    @Query("UPDATE GroupArkLeave SET confirmed = 1, confirmedAtEpochSeconds = :atEpochSeconds WHERE wallet = :wallet AND commitment = :commitment")
    suspend fun confirmLeaves(wallet: String, commitment: String, atEpochSeconds: Long)

    @Query("DELETE FROM GroupArkLeave WHERE wallet = :wallet AND commitment = :commitment")
    suspend fun forgetLeaves(wallet: String, commitment: String)

    @Upsert
    suspend fun upsertFee(row: GroupArkFee)

    @Query("SELECT * FROM GroupArkFee WHERE wallet = :wallet AND term = :term AND atEpochSeconds >= :since")
    suspend fun fees(wallet: String, term: Long, since: Long): List<GroupArkFee>

    /** Every fee this device has agreed to for this wallet, in any term, for the history. */
    @Query("SELECT * FROM GroupArkFee WHERE wallet = :wallet")
    suspend fun allFees(wallet: String): List<GroupArkFee>

    @Upsert
    suspend fun upsertRound(row: GroupArkRound)

    @Query("SELECT * FROM GroupArkRound WHERE wallet = :wallet")
    suspend fun rounds(wallet: String): List<GroupArkRound>

    @Upsert
    suspend fun upsertTreeBinding(row: GroupArkTreeBinding)

    @Query("SELECT * FROM GroupArkTreeBinding WHERE wallet = :wallet")
    suspend fun treeBindings(wallet: String): List<GroupArkTreeBinding>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChecked(row: GroupArkChecked)

    @Query("SELECT * FROM GroupArkChecked WHERE wallet = :wallet AND label = :label")
    suspend fun checked(wallet: String, label: String): GroupArkChecked?

    @Query("SELECT MAX(counter) FROM GroupArkChecked WHERE wallet = :wallet AND child IS NULL")
    suspend fun highestPlainChecked(wallet: String): Long?

    @Query("SELECT MAX(child) FROM GroupArkChecked WHERE wallet = :wallet AND child IS NOT NULL")
    suspend fun highestChildChecked(wallet: String): Long?

    @Query("SELECT * FROM GroupArkFloor WHERE wallet = :wallet")
    suspend fun floor(wallet: String): GroupArkFloor?

    @Upsert
    suspend fun upsertFloor(row: GroupArkFloor)

    @Upsert
    suspend fun upsertMark(row: GroupArkMark)

    @Query("SELECT * FROM GroupArkMark WHERE wallet = :wallet")
    suspend fun marks(wallet: String): List<GroupArkMark>
}
