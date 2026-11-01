package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface RepostedRelationDao {
    @Query("SELECT * FROM RepostedRelation")
    suspend fun  getAllReposts(): List<com.machankura.dedun.compose.database.model.RepostedRelation>

    @Query("SELECT * FROM RepostedRelation WHERE repostingNostrEventId = :repostingNostrEventId")
    suspend fun getRepostByRepostingId(repostingNostrEventId: String): com.machankura.dedun.compose.database.model.RepostedRelation?

    @Query("SELECT * FROM RepostedRelation WHERE repostedNostrEventId = :repostedNostrEventId")
    suspend fun getRepostByRepostedId(repostedNostrEventId: String): com.machankura.dedun.compose.database.model.RepostedRelation?

    @Upsert
    suspend fun upsert(repostedRelation: com.machankura.dedun.compose.database.model.RepostedRelation)
}