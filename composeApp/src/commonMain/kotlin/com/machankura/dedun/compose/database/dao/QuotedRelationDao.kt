package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface QuotedRelationDao {
    @Query("SELECT * FROM QuotedRelation WHERE quotingNostrEventId = :quotingNostrEventId")
    suspend fun getRepostByQuotingNostrEventId(quotingNostrEventId: String): com.machankura.dedun.compose.database.model.QuotedRelation?

    @Query("SELECT * FROM QuotedRelation WHERE quotedNostrEventId = :quotedNostrEventId")
    suspend fun getRepostByQuotedNostrEventId(quotedNostrEventId: String): com.machankura.dedun.compose.database.model.QuotedRelation?

    @Insert
    suspend fun insert(quotedRelation: com.machankura.dedun.compose.database.model.QuotedRelation)

    @Upsert
    suspend fun upsert(quotedRelation: com.machankura.dedun.compose.database.model.QuotedRelation)
}