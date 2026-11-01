package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface NegentropySynchronizeResultDao {
    @Upsert
    suspend fun upsert(negentropySynchronizeResult: com.machankura.dedun.compose.database.model.NegentropySynchronizeResult)
}