package com.machankura.dedun.compose.repository

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.GroupInboxPublication

/** The group's inbox, as the screens and the sync see it (docs/group-inbox.md). */
interface GroupInboxRepository {
    /** What this device owes relays on its groups' behalf and has not yet got onto one. */
    fun observeUnpublished(): Flow<List<GroupInboxPublication>>

    suspend fun markPublished(eventId: HexKey, at: Instant)
}
