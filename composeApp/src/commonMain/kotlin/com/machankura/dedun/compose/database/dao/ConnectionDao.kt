package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM Connection LIMIT :limit")
    suspend fun  getAllConnections(limit: Int = 21): List<com.machankura.dedun.compose.database.model.Connection>


    @Query("SELECT * FROM Connection WHERE (sourcePublicKey = :alicePublicKey AND destinationPublicKey = :bobPublicKey) OR (sourcePublicKey = :bobPublicKey AND destinationPublicKey = :alicePublicKey)")
    fun  observeRelation(alicePublicKey: HexKey, bobPublicKey: HexKey): Flow<List<com.machankura.dedun.compose.database.model.Connection>>

    @Insert
    suspend fun insertPlaceholderProfile(connection: com.machankura.dedun.compose.database.model.Connection)

    @Upsert
    suspend fun upsert(connection: com.machankura.dedun.compose.database.model.Connection)

    @Delete
    suspend fun delete(connection: com.machankura.dedun.compose.database.model.Connection)

}