package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.ChatMessageBroadcastNostrEventRequestRelation

@Dao
interface ChatMessageBroadcastNostrEventRequestRelationDao {
    @Query("SELECT * FROM ChatMessageBroadcastNostrEventRequestRelation WHERE broadcastNostrEventRequestId = :broadcastNostrEventRequestId")
    suspend fun getChatMessageBroadcastNostrEventRequestRelationByBroadcastRequest(broadcastNostrEventRequestId: Long): ChatMessageBroadcastNostrEventRequestRelation?

    @Upsert
    suspend fun upsert(chatMessageBroadcastNostrEventRequestRelation: ChatMessageBroadcastNostrEventRequestRelation)
}