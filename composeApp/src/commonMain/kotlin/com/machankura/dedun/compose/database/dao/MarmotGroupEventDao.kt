package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.MarmotGroupEvent

@Dao
interface MarmotGroupEventDao {

    @Upsert
    suspend fun upsert(marmotGroupEvent: MarmotGroupEvent)

    @Query("SELECT * FROM MarmotGroupEvent WHERE id = :id")
    suspend fun getMarmotGroupEventById(id: HexKey): MarmotGroupEvent?

    /**
     * When this device last saved a group event for [chatRoomId]: the nearest thing it has to when it
     * last heard from the room, which the Lightning wallet's start gate reads.
     */
    @Query("SELECT MAX(savedAt) FROM MarmotGroupEvent WHERE chatRoomId = :chatRoomId")
    suspend fun newestSavedAt(chatRoomId: String): kotlin.time.Instant?
}
