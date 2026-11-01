package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface SynchronizeNostrEventResultDao {
    @Upsert
    suspend fun upsert(synchronizeNostrEventResult: com.machankura.dedun.compose.database.model.SynchronizeNostrEventResult)
}