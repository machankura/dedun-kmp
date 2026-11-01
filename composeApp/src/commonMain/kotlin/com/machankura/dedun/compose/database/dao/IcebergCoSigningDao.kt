package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.IcebergBudgetSpend
import com.machankura.dedun.compose.database.model.IcebergChannelLedger
import com.machankura.dedun.compose.database.model.IcebergEchoRow
import com.machankura.dedun.compose.database.model.IcebergLabelAssignment
import com.machankura.dedun.compose.database.model.IcebergLabelLedger
import com.machankura.dedun.compose.database.model.IcebergNonceRow
import com.machankura.dedun.compose.database.model.IcebergShareRow

@Dao
interface IcebergCoSigningDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNonce(row: IcebergNonceRow)

    @Query("SELECT * FROM IcebergNonceRow WHERE wallet = :wallet AND counter = :counter")
    suspend fun noncesFor(wallet: String, counter: Long): List<IcebergNonceRow>

    @Query("SELECT MAX(counter) FROM IcebergNonceRow WHERE wallet = :wallet AND participant = :participant")
    suspend fun highestNonce(wallet: String, participant: String): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssignment(row: IcebergLabelAssignment)

    @Query("SELECT * FROM IcebergLabelAssignment WHERE wallet = :wallet AND purpose = :purpose LIMIT 1")
    suspend fun assignmentFor(wallet: String, purpose: String): IcebergLabelAssignment?

    @Query("SELECT MAX(counter) FROM IcebergLabelAssignment WHERE wallet = :wallet")
    suspend fun highestAssignment(wallet: String): Long?

    @Query("SELECT * FROM IcebergLabelAssignment WHERE wallet = :wallet")
    suspend fun assignments(wallet: String): List<IcebergLabelAssignment>

    /** First write wins: a label is echoed once, under one message. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedger(row: IcebergLabelLedger)

    @Query("SELECT * FROM IcebergLabelLedger WHERE wallet = :wallet AND label = :label")
    suspend fun ledgerFor(wallet: String, label: String): IcebergLabelLedger?

    @Upsert
    suspend fun upsertLedger(row: IcebergLabelLedger)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEcho(row: IcebergEchoRow)

    @Query("SELECT * FROM IcebergEchoRow WHERE wallet = :wallet AND label = :label")
    suspend fun echoesFor(wallet: String, label: String): List<IcebergEchoRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertShare(row: IcebergShareRow)

    @Query("SELECT * FROM IcebergShareRow WHERE wallet = :wallet AND requestId = :requestId")
    suspend fun sharesFor(wallet: String, requestId: String): List<IcebergShareRow>

    /** The ledger of the newest funding output of [channelId] this device has signed for. */
    @Query("SELECT * FROM IcebergChannelLedger WHERE wallet = :wallet AND channelId = :channelId ORDER BY fundingTxIndex DESC LIMIT 1")
    suspend fun channelLedger(wallet: String, channelId: String): IcebergChannelLedger?

    /** Every funding output of [channelId] this device has signed for, oldest first. */
    @Query("SELECT * FROM IcebergChannelLedger WHERE wallet = :wallet AND channelId = :channelId ORDER BY fundingTxIndex")
    suspend fun channelLedgers(wallet: String, channelId: String): List<IcebergChannelLedger>

    @Upsert
    suspend fun upsertChannelLedger(row: IcebergChannelLedger)

    @Query("SELECT * FROM IcebergBudgetSpend WHERE wallet = :wallet")
    suspend fun budgetSpends(wallet: String): List<IcebergBudgetSpend>

    @Upsert
    suspend fun upsertBudgetSpend(row: IcebergBudgetSpend)
}
