package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.GroupSignedEvent

@Dao
abstract class GroupSignedEventDao {
    @Query("SELECT * FROM GroupSignedEvent WHERE id = :id")
    abstract suspend fun getById(id: HexKey): GroupSignedEvent?

    /**
     * A room's signed work, oldest first.
     *
     * Oldest first because that is the order it can be applied in: a chunk names
     * a chapter, and the group signed the chapter first. It is not the order to
     * *show* anything in.
     */
    @Query("SELECT * FROM GroupSignedEvent WHERE chatRoomId = :chatRoomId ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getByChatRoomId(chatRoomId: String): List<GroupSignedEvent>

    @Query("SELECT * FROM GroupSignedEvent WHERE chatRoomId = :chatRoomId AND kind = :kind ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getByChatRoomIdAndKind(chatRoomId: String, kind: Kind): List<GroupSignedEvent>

    /**
     * How many events of one kind a room has signed, as it changes: what the group's Lightning node watches
     * to act on a payment or channel action the moment the group authorises it, rather than at its next poll.
     */
    @Query("SELECT COUNT(*) FROM GroupSignedEvent WHERE chatRoomId = :chatRoomId AND kind = :kind")
    abstract fun observeCountByChatRoomIdAndKind(chatRoomId: String, kind: Kind): Flow<Int>

    @Query("SELECT COUNT(*) FROM GroupSignedEvent WHERE kind IN (:kinds)")
    abstract fun observeCountOfKinds(kinds: List<Kind>): Flow<Int>

    /**
     * Every signed event of one kind, whatever room it was signed in.
     *
     * The one query here that is not about a room, and it exists for the one
     * statement that is not made in the room it is about: a group signs its
     * `GroupKeyStateEvent` in the NIP-17 room its ceremony ran in, naming the
     * Marmot room that does not exist yet -- see `GroupKeyStateManager.adopt`,
     * which walks these to find the state for a room the moment there is one.
     */
    @Query("SELECT * FROM GroupSignedEvent WHERE kind = :kind ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getByKind(kind: Kind): List<GroupSignedEvent>

    /**
     * The same, as it changes.
     *
     * Watched by the ceremony screen, which has to know the moment the group
     * finishes agreeing its key state -- there being no room yet, and so no
     * `GroupKeyState` row, to watch instead.
     */
    @Query("SELECT * FROM GroupSignedEvent WHERE kind = :kind ORDER BY createdAt ASC, id ASC")
    abstract fun observeByKind(kind: Kind): Flow<List<GroupSignedEvent>>

    /** Everything one session signed, in the order the batch was proposed in. */
    @Query("SELECT * FROM GroupSignedEvent WHERE frostSigningSessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getByFrostSigningSessionId(sessionId: String): List<GroupSignedEvent>

    /** Whether this device holds any of a room's signed work. */
    @Query("SELECT COUNT(*) FROM GroupSignedEvent WHERE chatRoomId = :chatRoomId")
    abstract suspend fun countByChatRoomId(chatRoomId: String): Int

    @Upsert
    abstract suspend fun upsert(groupSignedEvent: GroupSignedEvent)

    /**
     * Files a signed event, keeping what is already known about it.
     *
     * The same event reaches a device more than once by design: it is applied
     * when the session that made it completes, and again from any chronicle page
     * carrying it. A plain upsert would let the second arrival overwrite the
     * first, and the second arrival is the poorer one -- a chronicle knows no
     * session and, on a member who joined after the ceremony, no derivation
     * path. So the incoming row fills gaps and never empties them.
     *
     * The event's own fields are not merged, because they cannot disagree:
     * [GroupSignedEvent.id] is the hash of them, so two rows under one id either hold the same event
     * or one of them is not the event it claims to be.
     *
     * Returns the row now on file.
     */
    @Transaction
    open suspend fun record(groupSignedEvent: GroupSignedEvent): GroupSignedEvent {
        val known = getById(groupSignedEvent.id)

        val merged = groupSignedEvent.copy(
            derivationPath = groupSignedEvent.derivationPath ?: known?.derivationPath,
            frostSigningSessionId = groupSignedEvent.frostSigningSessionId
                ?: known?.frostSigningSessionId,
            // The event cannot change, so only a re-record moves this; savedAt
            // stays at the moment this device first held the group's work.
            updatedAt = if (known == null) groupSignedEvent.updatedAt else Clock.System.now(),
            savedAt = known?.savedAt ?: groupSignedEvent.savedAt,
        )

        upsert(merged)

        return merged
    }

    /**
     * Files a whole batch as one write.
     *
     * A session's events are signed together and applied together, so half a
     * batch on file is a state no reader should have to think about.
     */
    @Transaction
    open suspend fun recordAll(groupSignedEvents: List<GroupSignedEvent>): List<GroupSignedEvent> =
        groupSignedEvents.map { record(it) }
}
