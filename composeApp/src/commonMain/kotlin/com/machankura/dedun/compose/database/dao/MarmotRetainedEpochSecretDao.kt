package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.IGNORE
import androidx.room3.Query
import com.machankura.dedun.compose.database.model.MarmotRetainedEpochSecret
import com.vitorpamplona.quartz.nip01Core.core.HexKey

@Dao
interface MarmotRetainedEpochSecretDao {
    @Query("SELECT * FROM MarmotRetainedEpochSecret WHERE chatRoomId = :chatRoomId")
    suspend fun getMarmotRetainedEpochSecretForChatRoomId(chatRoomId: HexKey): List<MarmotRetainedEpochSecret>


    @Query("SELECT * FROM MarmotRetainedEpochSecret WHERE chatRoomId = :chatRoomId AND epoch < :epochCutOffPoint")
    suspend fun getDefenestratableMarmotRetainedEpochSecretForChatRoomId(chatRoomId: HexKey, epochCutOffPoint: Long): List<MarmotRetainedEpochSecret>

    @Insert(
        onConflict = IGNORE
    )
    suspend fun insert(marmotRetainedEpochSecret: MarmotRetainedEpochSecret)

    @Delete
    suspend fun defenestrate(marmotRetainedEpochSecrets: List<MarmotRetainedEpochSecret>)

    @Query("DELETE FROM MarmotRetainedEpochSecret WHERE chatRoomId = :chatRoomId")
    suspend fun deleteForChatRoomId(chatRoomId: HexKey)

}