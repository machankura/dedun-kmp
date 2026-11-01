package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.MarmotCommitResult
import com.vitorpamplona.quartz.nip01Core.core.HexKey

@Dao
interface MarmotCommitResultDao {
    @Query("SELECT * FROM MarmotCommitResult WHERE id = :nostrEventId")
    suspend fun getMarmotCommitRequestById(nostrEventId: HexKey): MarmotCommitResult?

    @Upsert
    suspend fun upsert(marmotCommitResult: MarmotCommitResult)
}