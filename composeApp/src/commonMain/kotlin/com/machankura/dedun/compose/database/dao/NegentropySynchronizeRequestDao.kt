package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import kotlinx.coroutines.flow.Flow

@Dao
interface NegentropySynchronizeRequestDao {
    // No `createdAt >` cut-off: a `Clock.System.now()` default is evaluated once, at
    // the call site, and Room binds it for the whole life of the Flow — which silently
    // stranded every request queued before (or within the same second as) the observer.
    @Query("SELECT * FROM NegentropySynchronizeRequest WHERE status = :status ORDER BY createdAt ASC, id ASC LIMIT 1")
    fun observeNegentropySynchronizeRequestsByStatus(status: String): Flow<NegentropySynchronizeRequest?>

    /** The same head-of-queue, for one identity; see `SynchronizeNostrEventRequestDao.observeSynchronizeNostrEventRequestsByStatusFor`. */
    @Query(
        "SELECT * FROM NegentropySynchronizeRequest " +
            "WHERE status = :status AND (ownerPublicKey = :ownerPublicKey OR ownerPublicKey IS NULL) " +
            "ORDER BY createdAt ASC, id ASC LIMIT 1"
    )
    fun observeNegentropySynchronizeRequestsByStatusFor(status: String, ownerPublicKey: String): Flow<NegentropySynchronizeRequest?>

    @Query("SELECT COUNT(*) FROM NegentropySynchronizeRequest WHERE purpose = :purpose AND status IN (:status)")
    fun observeNegentropySynchronizeRequestByPurposeAndStatusCount(purpose: String, status: List<String>): Flow<Int>

    @Upsert
    suspend fun upsert(negentropySynchronizeRequest: NegentropySynchronizeRequest)

    /**
     * Upsert rather than insert-or-ignore. `computeId` buckets by minute, so an identical
     * request re-queued inside the same minute collides — and with IGNORE the second one was
     * silently dropped even when the first had already run and come back empty. Re-arming the
     * row (fresh uuid, status back to "pending") is what makes a user-initiated retry actually
     * hit the network, while still collapsing duplicates into a single row.
     */
    @Upsert
    suspend fun insert(negentropySynchronizeRequests: List<NegentropySynchronizeRequest>)

    @Query("SELECT * FROM NegentropySynchronizeRequest")
    suspend fun getAll(): List<NegentropySynchronizeRequest>
}