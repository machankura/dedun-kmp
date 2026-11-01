package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.GroupKeyState

@Dao
abstract class GroupKeyStateDao {
    @Query("SELECT * FROM GroupKeyState WHERE chatRoomId = :chatRoomId")
    abstract suspend fun getByChatRoomId(chatRoomId: String): GroupKeyState?

    @Query("SELECT * FROM GroupKeyState WHERE chatRoomId = :chatRoomId")
    abstract fun observeByChatRoomId(chatRoomId: String): Flow<GroupKeyState?>

    /** Every room that signs with one ceremony's key. One, today. */
    @Query("SELECT * FROM GroupKeyState WHERE dkgSessionId = :dkgSessionId")
    abstract suspend fun getByDkgSessionId(dkgSessionId: String): List<GroupKeyState>

    /**
     * Every subgroup of [parentChatRoomId] this device holds a verified state
     * for.
     *
     * Verified is the whole of what these rows are: a state only reaches the
     * table with a parent on it once its birth certificate has been checked
     * against that parent's signature, so this is a list of children the parent
     * really did certify rather than a list of rooms claiming a parent.
     *
     * It is not the whole list. A subgroup whose room this device does not have
     * leaves no state here at all, and the certificates in `GroupSignedEvent` are
     * the fuller answer -- see `SubgroupManager.subgroupsOf`.
     */
    @Query("SELECT * FROM GroupKeyState WHERE parentChatRoomId = :parentChatRoomId ORDER BY announcedAt DESC")
    abstract suspend fun getByParentChatRoomId(parentChatRoomId: String): List<GroupKeyState>

    @Query("SELECT * FROM GroupKeyState WHERE parentChatRoomId = :parentChatRoomId ORDER BY announcedAt DESC")
    abstract fun observeByParentChatRoomId(parentChatRoomId: String): Flow<List<GroupKeyState>>

    @Upsert
    abstract suspend fun upsert(groupKeyState: GroupKeyState)

    /**
     * Files a state, keeping the newest one per room.
     *
     * This is where the event's replaceable semantics actually happen. Relays
     * never see a `GroupKeyStateEvent` -- it lives inside a Marmot group and is
     * applied from a completed signing session -- so nothing upstream applies
     * the 3xxxx replacement rule, and a state that lands twice would otherwise
     * be two rows racing for one primary key.
     *
     * Older states are dropped rather than applied, so a session replayed after
     * a crash cannot walk the room back to a state it has already moved past. A
     * state at the same instant is kept as a no-op: two runs at the same true
     * state agree by construction, since both derived it from the room they are
     * standing in.
     *
     * Returns the state now on file.
     */
    @Transaction
    open suspend fun replace(groupKeyState: GroupKeyState): GroupKeyState {
        val known = getByChatRoomId(groupKeyState.chatRoomId)

        if (known != null && known.announcedAt >= groupKeyState.announcedAt) return known

        val stamped = groupKeyState.copy(
            createdAt = known?.createdAt ?: groupKeyState.createdAt,
            updatedAt = groupKeyState.createdAt
        )
        upsert(stamped)

        return stamped
    }
}
