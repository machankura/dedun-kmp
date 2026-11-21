package com.machankura.dedun.compose.database.repository

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.GroupInboxPublication
import com.machankura.dedun.compose.repository.GroupInboxRepository

class DatabaseGroupInboxRepository(
    private val database: DedunDatabase,
    @Suppress("unused") private val scope: CoroutineScope,
) : GroupInboxRepository {
    override fun observeUnpublished(): Flow<List<GroupInboxPublication>> =
        database.groupInboxDao().observeUnpublished()

    override suspend fun markPublished(eventId: HexKey, at: Instant) =
        database.groupInboxDao().markPublished(eventId, at)
}
