package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.MarmotRouting

@Dao
interface MarmotRoutingDao {
    @Upsert
    suspend fun upsert(routing: MarmotRouting)

    @Query("SELECT * FROM MarmotRouting WHERE routingId = :routingId")
    suspend fun findByRoutingId(routingId: HexKey): MarmotRouting?

    @Query("SELECT * FROM MarmotRouting WHERE chatRoomId = :chatRoomId ORDER BY activeFromEpoch DESC")
    suspend fun findByChatRoomId(chatRoomId: HexKey): List<MarmotRouting>

    /**
     * The room addressed by [routingId], or [routingId] itself when no row says
     * otherwise -- the fallback every room from before the table relies on, and the
     * truth for every room whose routing id is its own id.
     */
    suspend fun chatRoomIdFor(routingId: HexKey): HexKey = findByRoutingId(routingId)?.chatRoomId ?: routingId

    /** The address [chatRoomId]'s outbound traffic is stamped with right now. */
    suspend fun activeRoutingIdFor(chatRoomId: HexKey): HexKey =
        findByChatRoomId(chatRoomId).firstOrNull { it.isActive }?.routingId ?: chatRoomId
}
