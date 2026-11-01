package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RelayDao {
    @Query("SELECT * FROM Relay")
    suspend fun  getAllPosts(): List<com.machankura.dedun.compose.database.model.Relay>

    @Query("SELECT * FROM Relay WHERE publicKey = :publicKey")
    fun observePublicKeyRelays(publicKey: String): Flow<List<com.machankura.dedun.compose.database.model.Relay>>

    @Upsert
    suspend fun upsert(relay: com.machankura.dedun.compose.database.model.Relay)

}