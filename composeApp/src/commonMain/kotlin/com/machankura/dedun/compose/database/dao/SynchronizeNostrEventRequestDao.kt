package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SynchronizeNostrEventRequestDao {
    // See NegentropySynchronizeRequestDao: the old `createdAt > :createdAt` bound a
    // once-evaluated `Clock.System.now()` for the life of the Flow and hid every
    // request queued before the observer subscribed.
    @Query("SELECT * FROM SynchronizeNostrEventRequest WHERE status = :status ORDER BY createdAt ASC, id ASC LIMIT 1")
    fun observeSynchronizeNostrEventRequestsByStatus(status: String): Flow<com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest?>

    /**
     * The same head-of-queue, for one identity: its own requests and the ones nobody
     * owns. A request another identity queued waits for that identity, because what it
     * brings back is opened with that identity's key.
     */
    @Query(
        "SELECT * FROM SynchronizeNostrEventRequest " +
            "WHERE status = :status AND (ownerPublicKey = :ownerPublicKey OR ownerPublicKey IS NULL) " +
            "ORDER BY createdAt ASC, id ASC LIMIT 1"
    )
    fun observeSynchronizeNostrEventRequestsByStatusFor(status: String, ownerPublicKey: String): Flow<com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest?>

    @Query("SELECT COUNT(*) FROM SynchronizeNostrEventRequest WHERE purpose = :purpose AND status IN (:status)")
    fun observeSynchronizeNostrEventRequestByPurposeAndStatusCount(purpose: String, status: List<String>): Flow<Int>

    @Upsert
    suspend fun upsert(synchronizeNostrEventRequest: com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest)

    /**
     * Records that the relay has said everything it has, *if* nothing arrived: a request
     * an event has already moved to `processed` stays there. Conditional in SQL rather
     * than read-then-write because the event handling that writes `processed` runs in
     * its own coroutine and can land after the subscription has closed.
     */
    @Query("UPDATE SynchronizeNostrEventRequest SET status = :status, updatedAt = :updatedAt WHERE id = :id AND status = 'sent'")
    suspend fun markCompleteIfSent(id: String, status: String, updatedAt: kotlin.time.Instant): Int

    @Insert
    suspend fun insert(synchronizeNostrEventRequests: List<com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest>)

    @Query("SELECT * FROM SynchronizeNostrEventRequest")
    suspend fun getAll(): List<com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest>
}