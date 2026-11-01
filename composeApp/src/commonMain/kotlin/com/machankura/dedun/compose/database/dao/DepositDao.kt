package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.DepositRow
import kotlinx.coroutines.flow.Flow

@Dao
interface DepositDao {
    @Upsert
    suspend fun upsert(row: DepositRow)

    @Query("SELECT * FROM DepositRow WHERE chatRoomId = :chatRoomId AND depositKey = :depositKey")
    suspend fun get(chatRoomId: String, depositKey: String): DepositRow?

    @Query("SELECT * FROM DepositRow WHERE chatRoomId = :chatRoomId AND chain = :chain ORDER BY seenAt DESC")
    suspend fun inRoom(chatRoomId: String, chain: String): List<DepositRow>

    @Query("SELECT * FROM DepositRow WHERE chatRoomId = :chatRoomId AND chain = :chain ORDER BY seenAt DESC")
    fun observeInRoom(chatRoomId: String, chain: String): Flow<List<DepositRow>>
}
