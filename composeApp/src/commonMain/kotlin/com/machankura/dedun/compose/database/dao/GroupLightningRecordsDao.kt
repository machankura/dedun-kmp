package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.GroupLightningInvoiceRow
import com.machankura.dedun.compose.database.model.GroupLightningPaymentRow
import com.machankura.dedun.compose.database.model.GroupLightningSnapshotRow
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupLightningRecordsDao {
    @Upsert
    suspend fun upsertSnapshot(row: GroupLightningSnapshotRow)

    @Query("SELECT * FROM GroupLightningSnapshotRow WHERE wallet = :wallet")
    suspend fun snapshot(wallet: String): GroupLightningSnapshotRow?

    @Query("SELECT * FROM GroupLightningSnapshotRow WHERE wallet = :wallet")
    fun observeSnapshot(wallet: String): Flow<GroupLightningSnapshotRow?>

    @Upsert
    suspend fun upsertPayment(row: GroupLightningPaymentRow)

    @Query("SELECT * FROM GroupLightningPaymentRow WHERE wallet = :wallet ORDER BY completedAt DESC")
    fun observePayments(wallet: String): Flow<List<GroupLightningPaymentRow>>

    @Upsert
    suspend fun upsertInvoice(row: GroupLightningInvoiceRow)

    @Query("SELECT * FROM GroupLightningInvoiceRow WHERE wallet = :wallet AND requestId = :requestId")
    suspend fun invoice(wallet: String, requestId: String): GroupLightningInvoiceRow?

    @Query("SELECT * FROM GroupLightningInvoiceRow WHERE wallet = :wallet AND requestId = :requestId")
    fun observeInvoice(wallet: String, requestId: String): Flow<GroupLightningInvoiceRow?>
}
