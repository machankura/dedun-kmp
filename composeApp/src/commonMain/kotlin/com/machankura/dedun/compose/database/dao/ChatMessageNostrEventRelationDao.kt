package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface ChatMessageNostrEventRelationDao {

    @Upsert
    suspend fun upsert(chatMessageNostrEventRelation: com.machankura.dedun.compose.database.model.ChatMessageNostrEventRelation)
}