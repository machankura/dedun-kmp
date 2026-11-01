package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface MarmotInnerEventDao {

    /**
     * Everything this key has queued for the group and not yet sent, oldest first.
     *
     * A backlog, not a head. The only exit from this queue is a successful send: the
     * row is stamped with the group event it became, in the same transaction that
     * writes that event. So a row that cannot be sent does not leave, and while the
     * notary was handed one row at a time that row *was* the queue -- a message that
     * will never encrypt (a room whose MLS state is gone, a direct message to a key
     * that is no longer a member) sat at the head and every message queued behind it
     * waited on it forever. Not just in its own room, either: the filter is the
     * sender's key, so one wedged room silenced every group this device belongs to.
     * Nothing reports it; the messages simply stay unsealed, which is what they are.
     *
     * Handing over the whole backlog lets the caller fail one row without losing the
     * rest. It must still work through them in order and one at a time: each send
     * ratchets the room's MLS state forward and saves it, and the next send in that
     * room is encrypted against what the last one left behind.
     *
     * `id` breaks the tie because `createdAt` is stored to the second and a burst of
     * sends shares one. Ordering that is only ever "some row with this timestamp"
     * would let two passes disagree about what comes next.
     */
    @Query("SELECT * FROM MarmotInnerEvent WHERE publicKey = :publicKey AND marmotGroupEventId IS NULL ORDER BY createdAt ASC, id ASC")
    fun observeUnprocessedMarmotInnerEvents(publicKey: String): Flow<List<MarmotInnerEvent>>

    @Upsert
    suspend fun upsert(marmotInnerEvent: MarmotInnerEvent)

    /**
     * One row by its id, for a caller that has to know whether an id is taken
     * before it writes one.
     *
     * An outbound row's id is a hash over the fields the rumor will be assembled
     * from, `created_at` among them and stored to the second -- so re-publishing
     * the same message inside the same second as the first copy computes the
     * same id. Upserting it would put an already-sent row back on the queue with
     * `marmotGroupEventId` cleared, which double-sends it and loses its mapping
     * to the group event it became. `FrostSigningManager.broadcast` checks here
     * first.
     */
    @Query("SELECT * FROM MarmotInnerEvent WHERE id = :id")
    suspend fun getById(id: String): MarmotInnerEvent?

    /**
     * A room's inner events of the given kinds, oldest first.
     *
     * Used to replay a protocol backlog: a session's messages are stored as they
     * decrypt, but one that arrives before the proposal opening its session has
     * nowhere to be filed at the time.
     */
    @Query("SELECT * FROM MarmotInnerEvent WHERE chatRoomId = :chatRoomId AND kind IN (:kinds) ORDER BY createdAt ASC")
    suspend fun getByChatRoomAndKinds(chatRoomId: String, kinds: List<Int>): List<MarmotInnerEvent>

    /**
     * How many of a room's inner events are of the given kinds, as it changes: what the group's Lightning
     * node watches to answer an invoice request the moment one arrives, rather than at its next poll.
     */
    @Query("SELECT COUNT(*) FROM MarmotInnerEvent WHERE chatRoomId = :chatRoomId AND kind IN (:kinds)")
    fun observeCountByChatRoomAndKinds(chatRoomId: String, kinds: List<Int>): Flow<Int>

    @Query("DELETE FROM MarmotInnerEvent WHERE id = :id")
    suspend fun deleteById(id: String)

}