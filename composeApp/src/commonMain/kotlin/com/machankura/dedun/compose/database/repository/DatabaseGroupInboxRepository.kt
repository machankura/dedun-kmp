package com.machankura.dedun.compose.database.repository

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.GroupInboxMessage
import com.machankura.dedun.compose.database.model.GroupInboxPublication
import com.machankura.dedun.compose.managers.GroupInboxManager
import com.machankura.dedun.compose.managers.InboxAnnouncements
import com.machankura.dedun.compose.repository.GroupInboxRepository

class DatabaseGroupInboxRepository(
    private val database: DedunDatabase,
    @Suppress("unused") private val scope: CoroutineScope,
) : GroupInboxRepository {
    override fun observeUnpublished(): Flow<List<GroupInboxPublication>> =
        database.groupInboxDao().observeUnpublished()

    override suspend fun markPublished(eventId: HexKey, at: Instant) =
        database.groupInboxDao().markPublished(eventId, at)

    override fun observeRoutes(userPublicKey: HexKey): Flow<List<GroupInboxRepository.Route>> =
        database.inboxKeyDao().observeHeldKeys(userPublicKey)
            .map { keys -> keys.groupBy({ it.chatRoomId }, { it.publicKey }).toSortedMap() }
            .distinctUntilChanged()
            .map { byRoom -> byRoom.map { (room, keys) -> GroupInboxRepository.Route(room, keys, InboxAnnouncements.relaysOf(database, room)) } }

    override suspend fun receive(wrap: Event, userPublicKey: HexKey): GroupInboxMessage? =
        GroupInboxManager.receive(database, wrap, userPublicKey)
}
