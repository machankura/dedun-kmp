package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.MarmotSenderRatchet

@Dao
interface MarmotSenderRatchetDao {
    @Query("SELECT * FROM MarmotSenderRatchet WHERE chatRoomId = :chatRoomId")
    suspend fun forChatRoomId(chatRoomId: HexKey): MarmotSenderRatchet?

    /**
     * Upsert rather than insert: a send overwrites the position its predecessor
     * wrote, and there is one row per room for the life of the room.
     */
    @Upsert
    suspend fun upsert(marmotSenderRatchet: MarmotSenderRatchet)

    @Query("DELETE FROM MarmotSenderRatchet WHERE chatRoomId = :chatRoomId")
    suspend fun deleteForChatRoomId(chatRoomId: HexKey)
}
