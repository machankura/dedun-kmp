package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.KeyShareGeneration
import com.machankura.dedun.compose.database.model.KeyShareMember
import com.machankura.dedun.compose.database.model.ShareRefreshConfirmation
import com.machankura.dedun.compose.database.model.ShareRefreshMessage
import com.machankura.dedun.compose.database.model.ShareRefreshSession

/** Share refreshes (docs/frost-share-refresh.md, Phase 7). Read through `ShareRefreshManager`. */
@Dao
abstract class ShareRefreshDao {
    @Query("SELECT * FROM ShareRefreshSession WHERE id = :id")
    abstract suspend fun getSessionById(id: String): ShareRefreshSession?

    @Query("SELECT * FROM ShareRefreshSession WHERE id = :id")
    abstract fun observeSessionById(id: String): Flow<ShareRefreshSession?>

    /** The refreshes of one key, newest first. */
    @Query("SELECT * FROM ShareRefreshSession WHERE dkgSessionId = :dkgSessionId ORDER BY createdAt DESC")
    abstract suspend fun getSessionsForKey(dkgSessionId: String): List<ShareRefreshSession>

    /**
     * Every refresh [userPublicKey] is in that has not finished, oldest first:
     * what `ShareRefreshManager.resumeRunning` moves on.
     */
    @Query("SELECT * FROM ShareRefreshSession WHERE userPublicKey = :userPublicKey AND stage NOT IN ('COMPLETE', 'FAILED') ORDER BY createdAt ASC")
    abstract suspend fun getRunningSessionsFor(userPublicKey: HexKey): List<ShareRefreshSession>

    @Query("SELECT * FROM ShareRefreshSession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    abstract fun observeSessionsForRoom(chatRoomId: String): Flow<List<ShareRefreshSession>>

    @Upsert
    abstract suspend fun upsert(session: ShareRefreshSession)

    @Upsert
    abstract suspend fun upsert(message: ShareRefreshMessage)

    @Query("SELECT * FROM ShareRefreshMessage WHERE sessionId = :sessionId")
    abstract suspend fun getMessagesForSession(sessionId: String): List<ShareRefreshMessage>

    @Query("SELECT * FROM ShareRefreshMessage WHERE sessionId = :sessionId AND kind = :kind")
    abstract suspend fun getMessagesByKind(sessionId: String, kind: Kind): List<ShareRefreshMessage>

    @Query("SELECT * FROM ShareRefreshMessage WHERE sessionId = :sessionId AND kind = :kind AND participantPublicKey = :participantPublicKey")
    abstract suspend fun getMessage(sessionId: String, kind: Kind, participantPublicKey: HexKey): ShareRefreshMessage?

    /** Never replaces: the first confirmation from a generation is the only one. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertConfirmation(confirmation: ShareRefreshConfirmation)

    @Query("SELECT * FROM ShareRefreshConfirmation WHERE dkgSessionId = :dkgSessionId AND baseGeneration = :baseGeneration")
    abstract suspend fun getConfirmation(dkgSessionId: String, baseGeneration: Int): ShareRefreshConfirmation?

    @Upsert
    protected abstract suspend fun upsertGeneration(generation: KeyShareGeneration)

    @Upsert
    protected abstract suspend fun upsertMembers(members: List<KeyShareMember>)

    /**
     * Claims this device's one confirmation from [confirmation]'s base generation
     * for [confirmation]'s refresh, and stores the new generation with it -- both
     * or neither, so a confirmation never goes out for a share that is not on disk
     * and a share is never stored for a refresh this device did not confirm.
     *
     * Returns the refresh that holds the claim. When it is not this one, nothing
     * was written and the caller must not confirm.
     */
    @Transaction
    open suspend fun confirmAndStore(
        confirmation: ShareRefreshConfirmation,
        generation: KeyShareGeneration,
        members: List<KeyShareMember>,
    ): String {
        insertConfirmation(confirmation)
        val holder = getConfirmation(confirmation.dkgSessionId, confirmation.baseGeneration)?.refreshSessionId
        if (holder != confirmation.refreshSessionId) return holder.orEmpty()
        upsertGeneration(generation)
        upsertMembers(members)
        return confirmation.refreshSessionId
    }
}
