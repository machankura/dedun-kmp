package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.machankura.dedun.compose.database.model.GroupLightningSeed

@Dao
interface GroupLightningSeedDao {
    /** First copy wins: a seed for a node id is that node id's whatever arrives later. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(seed: GroupLightningSeed)

    @Query("SELECT * FROM GroupLightningSeed WHERE chatRoomId = :chatRoomId AND nodeId = :nodeId")
    suspend fun get(chatRoomId: String, nodeId: String): GroupLightningSeed?
}
