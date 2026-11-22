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

    override fun observeConversations(chatRoomId: HexKey): Flow<List<com.machankura.dedun.compose.ui.view.state.GroupDmConversationSummary>> =
        database.groupDmDao().observeMessages(chatRoomId).map { messages ->
            messages.groupBy { it.conversationId }.map { (conversationId, inIt) ->
                val newest = inIt.maxBy { it.createdAt }
                val participants = participantsIn(chatRoomId, inIt)
                com.machankura.dedun.compose.ui.view.state.GroupDmConversationSummary(
                    conversationId = conversationId,
                    participants = participants,
                    names = participants.map { nameOf(it) },
                    lastLine = lineOf(newest.rumorJson),
                    lastAt = newest.createdAt,
                    unread = inIt.count { !it.outgoing && it.readAt == null },
                )
            }.sortedByDescending { it.lastAt }
        }

    override fun observePending(chatRoomId: HexKey): Flow<com.machankura.dedun.compose.ui.view.state.GroupDmPending> =
        kotlinx.coroutines.flow.combine(
            database.groupDmDao().observePendingWraps(chatRoomId),
            database.groupDmDao().observeOpenPartials(chatRoomId),
        ) { wraps, _ -> wraps }.map { wraps ->
            val waiting = wraps.filter { it.state == com.machankura.dedun.compose.database.model.GroupDmWrapState.WAITING }
            val newest = waiting.maxByOrNull { it.createdAt }
            val (needed, notYet) = newest?.let { com.machankura.dedun.compose.managers.GroupDmReceiver.waitingOn(database, chatRoomId, it) }
                ?: (0 to emptyList())
            com.machankura.dedun.compose.ui.view.state.GroupDmPending(
                waiting = waiting.size,
                queued = wraps.count { it.state == com.machankura.dedun.compose.database.model.GroupDmWrapState.QUEUED },
                moreAdminsNeeded = needed,
                notYetAnsweredBy = notYet.map { nameOf(it) },
            )
        }.distinctUntilChanged()

    override fun observeConversation(chatRoomId: HexKey, conversationId: HexKey): Flow<List<com.machankura.dedun.compose.ui.view.state.GroupDmLine>> =
        kotlinx.coroutines.flow.combine(
            database.groupDmDao().observeConversation(chatRoomId, conversationId),
            database.groupDmDao().observeOutbound(chatRoomId),
        ) { messages, outbound -> messages to outbound }.map { (messages, outbound) ->
            messages.map { message ->
                val owed = message.signingSessionId?.let { id -> outbound.filter { it.signingSessionId == id } }.orEmpty()
                com.machankura.dedun.compose.ui.view.state.GroupDmLine(
                    id = message.rumorId,
                    text = lineOf(message.rumorJson),
                    outgoing = message.outgoing,
                    senderName = nameOf(message.senderPublicKey),
                    at = message.createdAt,
                    outgoingState = if (message.outgoing) outgoingStateOf(message.signingSessionId, owed) else null,
                    signingSessionId = message.signingSessionId,
                )
            }
        }

    /**
     * Where a message the group sent has got to: its signing session, then its wraps. Sent once
     * every recipient's wrap is on a relay; signed but not sent when the session completed and
     * no wrap is owed here, which is how every device but the proposer's sees it (D10).
     */
    private suspend fun outgoingStateOf(
        signingSessionId: String?,
        owed: List<com.machankura.dedun.compose.database.model.GroupDmOutbound>,
    ): com.machankura.dedun.compose.ui.view.state.GroupDmOutgoingState? {
        val session = signingSessionId?.let { database.frostSigningSessionDao().getSessionById(it) } ?: return null
        return when {
            session.stage == com.machankura.dedun.compose.database.model.types.FrostSigningStage.FAILED ->
                com.machankura.dedun.compose.ui.view.state.GroupDmOutgoingState.DECLINED
            session.stage != com.machankura.dedun.compose.database.model.types.FrostSigningStage.COMPLETE ->
                com.machankura.dedun.compose.ui.view.state.GroupDmOutgoingState.BEING_SIGNED
            owed.isNotEmpty() && owed.all { it.publishedAt != null } -> com.machankura.dedun.compose.ui.view.state.GroupDmOutgoingState.SENT
            owed.isNotEmpty() && owed.any { it.attempts > 0 } -> com.machankura.dedun.compose.ui.view.state.GroupDmOutgoingState.NOT_SENT
            owed.isNotEmpty() -> com.machankura.dedun.compose.ui.view.state.GroupDmOutgoingState.SIGNED
            else -> com.machankura.dedun.compose.ui.view.state.GroupDmOutgoingState.NOT_SENT
        }
    }

    override suspend fun participantsOf(chatRoomId: HexKey, conversationId: HexKey): List<HexKey> =
        participantsIn(chatRoomId, database.groupDmDao().getMessages(chatRoomId).filter { it.conversationId == conversationId })

    private fun participantsIn(chatRoomId: HexKey, messages: List<com.machankura.dedun.compose.database.model.GroupDmMessage>): List<HexKey> =
        messages.flatMap { message ->
            runCatching { com.vitorpamplona.quartz.nip01Core.core.Event.fromJson(message.rumorJson) }.getOrNull()
                ?.let { com.machankura.dedun.compose.nostr.groupdm.GroupDmConversation.participantsOf(it, chatRoomId) }
                .orEmpty()
        }.distinct().sorted()

    /** A kind 14's words; null for anything else, which the screen says it cannot show yet. */
    private fun lineOf(rumorJson: String): String? =
        runCatching { com.vitorpamplona.quartz.nip01Core.core.Event.fromJson(rumorJson) }.getOrNull()
            ?.takeIf { it.kind == 14 }?.content

    override suspend fun markRead(chatRoomId: HexKey, conversationId: HexKey) =
        database.groupDmDao().markRead(chatRoomId, conversationId, kotlin.time.Clock.System.now())

    override suspend fun nameOf(publicKey: HexKey): String =
        database.profileDao().getProfileByPublicKey(publicKey)
            ?.let { profile -> profile.displayName?.takeIf { it.isNotBlank() && it != "LOADING..." } ?: profile.userName?.takeIf { it.isNotBlank() } }
            ?: publicKey.take(8)

    override suspend fun isCurrentAdmin(chatRoomId: HexKey, userPublicKey: HexKey): Boolean {
        val admins = RoomAdmins.of(database, chatRoomId)?.admins ?: return false
        if (userPublicKey !in admins) return false
        val key = FrostSigningManager.completedKey(database, chatRoomId) ?: return false
        return ShareGenerations.active(database, key).signerIds.containsKey(userPublicKey)
    }

    override suspend fun proposeRelays(localChatRoom: LocalChatRoom, userPublicKey: HexKey, relays: List<String>): FrostSigningSession =
        GroupDmRelays.propose(database, localChatRoom, userPublicKey, relays)
}
