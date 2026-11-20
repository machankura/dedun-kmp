package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.GovernanceProposal

/** Changes this device proposed to a governed room (docs/member-admin-promotion.md, Phase 5). */
@Dao
interface GovernanceProposalDao {
    @Query("SELECT * FROM GovernanceProposal WHERE id = :id")
    suspend fun getById(id: String): GovernanceProposal?

    @Query("SELECT * FROM GovernanceProposal WHERE frostSigningSessionId = :sessionId")
    suspend fun getBySession(sessionId: String): List<GovernanceProposal>

    @Query("SELECT * FROM GovernanceProposal WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    suspend fun getForRoom(chatRoomId: String): List<GovernanceProposal>

    @Upsert
    suspend fun upsert(proposal: GovernanceProposal)

    @Query("SELECT * FROM GovernanceProposal ORDER BY createdAt ASC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<GovernanceProposal>>

    @Query("SELECT * FROM GovernanceProposal ORDER BY createdAt ASC")
    suspend fun getAll(): List<GovernanceProposal>
}
