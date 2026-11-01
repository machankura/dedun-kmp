package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface InReplyToRelationDao {
    @Query("SELECT * FROM InReplyToRelation WHERE replyingNostrEventId = :replyingNostrEventId")
    suspend fun getRepostByReplyingNostrEventId(replyingNostrEventId: String): com.machankura.dedun.compose.database.model.InReplyToRelation?

    @Query("SELECT * FROM InReplyToRelation WHERE inReplyToNostrEventId = :inReplyToNostrEventId")
    suspend fun getRepostByInReplyToNostrEventId(inReplyToNostrEventId: String): com.machankura.dedun.compose.database.model.InReplyToRelation?

    @Insert
    suspend fun insert(inReplyToRelation: com.machankura.dedun.compose.database.model.InReplyToRelation)

    @Upsert
    suspend fun upsert(inReplyToRelation: com.machankura.dedun.compose.database.model.InReplyToRelation)
}