package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.MarmotOutboundGate
import com.machankura.dedun.compose.database.model.MarmotPublishObligation

@Dao
interface MarmotPublishObligationDao {
    @Upsert
    suspend fun upsert(obligation: MarmotPublishObligation)

    @Query("DELETE FROM MarmotPublishObligation WHERE obligationId = :obligationId")
    suspend fun delete(obligationId: HexKey)

    @Query("SELECT * FROM MarmotPublishObligation")
    suspend fun getAll(): List<MarmotPublishObligation>

    @Upsert
    suspend fun upsertGate(gate: MarmotOutboundGate)

    @Query("DELETE FROM MarmotOutboundGate WHERE groupId = :groupId")
    suspend fun deleteGate(groupId: HexKey)

    @Query("SELECT * FROM MarmotOutboundGate")
    suspend fun getAllGates(): List<MarmotOutboundGate>
}
