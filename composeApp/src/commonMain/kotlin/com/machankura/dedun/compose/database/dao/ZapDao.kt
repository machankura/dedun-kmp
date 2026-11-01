package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.Zap
import kotlinx.coroutines.flow.Flow

@Dao
interface ZapDao {
    @Query("SELECT * FROM Zap WHERE id = :id")
    fun getZapById(id: String): Flow<Zap?>

    @Upsert
    suspend fun upsert(zap: Zap)
}