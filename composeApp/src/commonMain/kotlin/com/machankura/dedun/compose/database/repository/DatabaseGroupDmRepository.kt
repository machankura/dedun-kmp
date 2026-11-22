package com.machankura.dedun.compose.database.repository

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupDmOutbound
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.managers.GroupDmRelays
import com.machankura.dedun.compose.managers.RoomAdmins
import com.machankura.dedun.compose.managers.ShareGenerations
import com.machankura.dedun.compose.repository.GroupDmRepository

class DatabaseGroupDmRepository(private val database: DedunDatabase) : GroupDmRepository {
    override fun observeOwed(): Flow<List<GroupDmOutbound>> = database.groupDmDao().observeOwed()

    override suspend fun markPublished(outbound: GroupDmOutbound, at: Instant) =
        database.groupDmDao().markPublished(outbound.signingSessionId, outbound.sealId, at)

    override suspend fun markAttempt(outbound: GroupDmOutbound, next: Instant) =
        database.groupDmDao().markAttempt(outbound.signingSessionId, outbound.sealId, next)

    override fun observeRelays(chatRoomId: HexKey): Flow<List<String>?> =
        database.groupSignedEventDao().observeByKind(GroupDmRelays.KIND)
            .map { rows ->
                rows.filter { it.chatRoomId == chatRoomId && it.publicKey.equals(chatRoomId, ignoreCase = true) }
                    .maxByOrNull { it.createdAt }
                    ?.let { GroupDmRelays.relaysIn(it.tags) }
            }
            .distinctUntilChanged()

    override fun observeReadableRooms(userPublicKey: HexKey): Flow<List<com.machankura.dedun.compose.managers.GroupDmInbox>> =
        kotlinx.coroutines.flow.combine(
            database.chatRoomDao().observeChatRoomListByUserPublicKey(userPublicKey),
            database.groupSignedEventDao().observeByKind(GroupDmRelays.KIND),
            database.groupDmDao().observeNewestWrapTimes(),
        ) { rooms, _, _ -> rooms }
            .map { rooms ->
                rooms.filter { it.chatRoom.mlsGroupState != null && it.chatRoom.leftGroupAt == null && it.chatRoom.deletedAt == null }
                    .mapNotNull { room ->
                        val id = room.chatRoom.id
                        if (!com.machankura.dedun.compose.managers.GroupDmReceiver.readsFor(database, id, userPublicKey)) return@mapNotNull null
                        com.machankura.dedun.compose.managers.GroupDmInbox(
                            roomId = id,
                            relays = GroupDmRelays.relaysOf(database, id).orEmpty(),
                            newestWrapAt = database.groupDmDao().newestWrapCreatedAt(id)?.epochSeconds,
                        )
                    }
                    .sortedBy { it.roomId }
            }
            .distinctUntilChanged()

    override suspend fun receiveWrap(wrap: com.vitorpamplona.quartz.nip01Core.core.Event, userPublicKey: HexKey, nostrPrivateKey: ByteArray): Boolean =
        com.machankura.dedun.compose.managers.GroupDmReceiver.receive(database, wrap, userPublicKey, nostrPrivateKey)

    override suspend fun isCurrentAdmin(chatRoomId: HexKey, userPublicKey: HexKey): Boolean {
        val admins = RoomAdmins.of(database, chatRoomId)?.admins ?: return false
        if (userPublicKey !in admins) return false
        val key = FrostSigningManager.completedKey(database, chatRoomId) ?: return false
        return ShareGenerations.active(database, key).signerIds.containsKey(userPublicKey)
    }

    override suspend fun proposeRelays(localChatRoom: LocalChatRoom, userPublicKey: HexKey, relays: List<String>): FrostSigningSession =
        GroupDmRelays.propose(database, localChatRoom, userPublicKey, relays)
}
