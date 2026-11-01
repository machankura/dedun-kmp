package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.intermdiate.LocalBroadcastNostrEventRequest
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

@Dao
interface BroadcastNostrEventRequestDao {
    @Query("SELECT * FROM BroadcastNostrEventRequest")
    suspend fun  getAllBroadcastNostrEventRequests(): List<BroadcastNostrEventRequest>

    @Query("SELECT * FROM BroadcastNostrEventRequest WHERE nostrEventId = :nostrEventId ORDER BY createdAt ASC")
    suspend fun  getFirstBroadcastNostrEventRequestByNostrEventId(nostrEventId: String): BroadcastNostrEventRequest?

    /** Every relay's request for one event: the obligation's scope, as rows. */
    @Query("SELECT * FROM BroadcastNostrEventRequest WHERE nostrEventId = :nostrEventId ORDER BY createdAt ASC, id ASC")
    suspend fun getBroadcastNostrEventRequestsByNostrEventId(nostrEventId: String): List<BroadcastNostrEventRequest>

    // See NegentropySynchronizeRequestDao: the old `createdAt > :createdAt` bound a
    // once-evaluated `Clock.System.now()` for the life of the Flow. Because Instants are
    // persisted at second resolution, that hid every broadcast enqueued in the observer's
    // own start second — i.e. the entire profile-creation burst — plus everything left
    // pending by a previous session. Nothing else drains this table, so those events were
    // never sent to any relay.
    @Query("SELECT * FROM BroadcastNostrEventRequest WHERE status = :status ORDER BY createdAt ASC, id ASC LIMIT 1")
    fun observeBroadcastNostrEventRequestsByStatus(status: String): Flow<LocalBroadcastNostrEventRequest?>

    @Query("SELECT * FROM BroadcastNostrEventRequest WHERE nostrEventId = :nostrEventId")
    fun observeBroadcastNostrEventRequestByNostrEventId(nostrEventId: String): Flow<BroadcastNostrEventRequest?>

    /**
     * A request is flipped to "processing" before the publish is attempted, and the publish can
     * end in a timeout or a socket error that leaves it there. Nothing observes "processing" or
     * "failed", so those rows are dead weight. Requeue everything a previous process left behind
     * when we start up, so an interrupted publish is retried instead of lost. Not "rejected":
     * that is a relay's answer to these exact bytes, and it does not change on a retry -- see
     * [com.machankura.dedun.compose.database.model.BroadcastStatus].
     */
    @Query("UPDATE BroadcastNostrEventRequest SET status = 'pending' WHERE status IN ('processing', 'failed') AND createdAt <= :staleBefore")
    suspend fun requeueStaleBroadcastNostrEventRequests(staleBefore: Instant): Int

    @Upsert
    suspend fun upsert(broadcastNostrEventRequest: BroadcastNostrEventRequest): Long

    @Insert
    suspend fun insert(broadcastNostrEventRequests: List<BroadcastNostrEventRequest>): LongArray
}