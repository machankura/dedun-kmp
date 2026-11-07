package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.GroupArkCheckpoint
import com.machankura.dedun.compose.database.model.GroupArkChecked
import com.machankura.dedun.compose.database.model.GroupArkFee
import com.machankura.dedun.compose.database.model.GroupArkFloor
import com.machankura.dedun.compose.database.model.GroupArkMark
import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.database.model.GroupArkPendingRound
import com.machankura.dedun.compose.database.model.GroupArkRound
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

    @Upsert
    suspend fun upsertVtxo(row: GroupArkVtxo)

    @Query("SELECT * FROM GroupArkVtxo WHERE wallet = :wallet")
    suspend fun vtxos(wallet: String): List<GroupArkVtxo>

    @Query("UPDATE GroupArkVtxo SET settled = 1 WHERE wallet = :wallet AND origin = :origin")
    suspend fun settle(wallet: String, origin: String)

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

    @Upsert
    suspend fun upsertFee(row: GroupArkFee)

    @Query("SELECT * FROM GroupArkFee WHERE wallet = :wallet AND term = :term AND atEpochSeconds >= :since")
    suspend fun fees(wallet: String, term: Long, since: Long): List<GroupArkFee>

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
