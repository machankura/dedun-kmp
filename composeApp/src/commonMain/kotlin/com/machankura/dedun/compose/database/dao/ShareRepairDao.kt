package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.machankura.dedun.compose.database.model.ShareRepairMessage
import com.machankura.dedun.compose.database.model.ShareRepairSession

/** Share repairs (docs/frost-share-refresh.md, Phase 10). Read through `ShareRepairManager`. */
@Dao
interface ShareRepairDao {
    @Query("SELECT * FROM ShareRepairSession WHERE id = :id")
    suspend fun getSessionById(id: String): ShareRepairSession?

    @Query("SELECT * FROM ShareRepairSession WHERE dkgSessionId = :dkgSessionId ORDER BY createdAt DESC")
    suspend fun getSessionsForKey(dkgSessionId: String): List<ShareRepairSession>

    @Upsert
    suspend fun upsert(session: ShareRepairSession)

    @Upsert
    suspend fun upsert(message: ShareRepairMessage)

    @Query("SELECT * FROM ShareRepairMessage WHERE sessionId = :sessionId AND kind = :kind")
    suspend fun getMessagesByKind(sessionId: String, kind: Kind): List<ShareRepairMessage>
}
