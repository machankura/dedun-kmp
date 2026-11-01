package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface ChatMessageBroadcastNostrEventReceiptRelationDao {
    @Upsert
    suspend fun upsert(chatMessageBroadcastNostrEventReceiptRelation: com.machankura.dedun.compose.database.model.ChatMessageBroadcastNostrEventReceiptRelation)
}