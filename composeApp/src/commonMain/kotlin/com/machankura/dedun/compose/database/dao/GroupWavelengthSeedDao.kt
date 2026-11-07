package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.machankura.dedun.compose.database.model.GroupWavelengthSeed

@Dao
interface GroupWavelengthSeedDao {
    /** The first copy stands: a seed is checked against the identity it produces, so a second adds nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(seed: GroupWavelengthSeed)

    @Query("SELECT * FROM GroupWavelengthSeed WHERE chatRoomId = :chatRoomId AND identity = :identity")
    suspend fun get(chatRoomId: String, identity: String): GroupWavelengthSeed?
}
