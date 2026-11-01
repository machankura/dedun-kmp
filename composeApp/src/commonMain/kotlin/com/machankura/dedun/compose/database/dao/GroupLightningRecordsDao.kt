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

    /** Every payment any wallet's snapshots reported: what a tally of payouts reads. */
    @Query("SELECT * FROM GroupLightningPaymentRow")
    suspend fun allPayments(): List<GroupLightningPaymentRow>

    @Upsert
    suspend fun upsertInvoice(row: GroupLightningInvoiceRow)

    @Query("SELECT * FROM GroupLightningInvoiceRow WHERE wallet = :wallet AND requestId = :requestId")
    suspend fun invoice(wallet: String, requestId: String): GroupLightningInvoiceRow?

    /** The invoices for [wallet] paid by [paymentHash]: one, unless an operator answered two requests with one invoice. */
    @Query("SELECT * FROM GroupLightningInvoiceRow WHERE wallet = :wallet AND paymentHash = :paymentHash")
    suspend fun invoicesByHash(wallet: String, paymentHash: String): List<GroupLightningInvoiceRow>

    /** Every invoice for [wallet] whose requester is known: what attributes Lightning deposits. */
    @Query("SELECT * FROM GroupLightningInvoiceRow WHERE wallet = :wallet AND requester IS NOT NULL AND paymentHash IS NOT NULL")
    suspend fun attributedInvoices(wallet: String): List<GroupLightningInvoiceRow>

    @Query("SELECT * FROM GroupLightningInvoiceRow WHERE wallet = :wallet AND requestId = :requestId")
    fun observeInvoice(wallet: String, requestId: String): Flow<GroupLightningInvoiceRow?>
}
