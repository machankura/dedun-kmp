package com.machankura.dedun.compose.database.repository

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.GroupInboxMessage
import com.machankura.dedun.compose.database.model.GroupInboxPublication
import com.machankura.dedun.compose.database.model.InboxKeyCeremony
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.GroupInboxManager
import com.machankura.dedun.compose.managers.InboxAnnouncements
import com.machankura.dedun.compose.managers.InboxKeyRitualManager
import com.machankura.dedun.compose.managers.InboxReplies
import com.machankura.dedun.compose.managers.InboxRotation
import com.machankura.dedun.compose.nostr.inbox.InboxKeyRitualEvents
import com.machankura.dedun.compose.repository.GroupInboxRepository

class DatabaseGroupInboxRepository(
    private val database: DedunDatabase,
    @Suppress("unused") private val scope: CoroutineScope,
    /** The identity the app runs as: its key takes part in the ceremonies it starts. */
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
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

    override fun observeConversations(chatRoomId: String): Flow<List<GroupInboxRepository.ConversationSummary>> =
        database.groupInboxDao().observeMessages(chatRoomId).map { messages ->
            val names = namesOf(messages.flatMap { it.participantList() }.distinct())
            messages.groupBy { it.conversationId }.map { (id, inConversation) ->
                val newest = inConversation.maxBy { it.createdAt }
                val participants = newest.participantList()
                GroupInboxRepository.ConversationSummary(
                    conversationId = id,
                    participants = participants,
                    names = participants.map { names[it] ?: it.take(8) },
                    lastText = Event.fromJsonOrNull(newest.rumorJson)?.content.orEmpty(),
                    lastAt = newest.createdAt,
                    lastOutgoing = newest.outgoing,
                    unread = inConversation.count { !it.outgoing && it.readAt == null },
                )
            }.sortedByDescending { it.lastAt }
        }

    override fun observeConversation(chatRoomId: String, conversationId: String): Flow<List<GroupInboxMessage>> =
        database.groupInboxDao().observeConversation(chatRoomId, conversationId)

    override fun observeUnopenedCount(chatRoomId: String): Flow<Int> =
        database.groupInboxDao().observeUnopenedCount(chatRoomId)

    override fun observeStatus(localChatRoom: LocalChatRoom, userPublicKey: HexKey): Flow<GroupInboxRepository.Status> {
        val roomId = localChatRoom.chatRoom.id
        return combine(
            database.inboxKeyDao().observeKeys(roomId, userPublicKey),
            database.inboxKeyDao().observeLatestCeremony(roomId, userPublicKey),
        ) { keys, ceremony ->
            GroupInboxRepository.Status(
                keys = keys,
                ceremony = ceremony,
                announcedKey = InboxAnnouncements.announcedKey(database, roomId),
                relays = InboxAnnouncements.relaysOf(database, roomId),
                setUpRefusal = InboxKeyRitualManager.refusal(database, localChatRoom, userPublicKey),
                waitingOn = ceremony?.takeIf { it.isRunning }?.let { waitingOn(it) }.orEmpty(),
                rotationOwed = InboxRotation.isOwed(database, localChatRoom, userPublicKey),
            )
        }
    }

    /** The admins a running ceremony has not heard a host key from: who to knock on the door of. */
    private suspend fun waitingOn(ceremony: InboxKeyCeremony): List<String> {
        val heard = database.inboxKeyDao().getMessages(ceremony.id)
            .filter { it.kind == InboxKeyRitualEvents.HOST_KEY }.map { it.senderPublicKey }.toSet()
        val silent = ceremony.participants().filter { it !in heard }
        val names = namesOf(silent)
        return silent.map { names[it] ?: it.take(8) }
    }

    override suspend fun markRead(chatRoomId: String, conversationId: String) =
        database.groupInboxDao().markConversationRead(chatRoomId, conversationId, Clock.System.now())

    override suspend fun namesOf(publicKeys: List<HexKey>): Map<HexKey, String> =
        database.profileDao().getProfileByPublicKeys(publicKeys).associate { it.publicKey to it.humanReadableNameOrPubkey() }

    private fun secret(): ByteArray =
        activeIdentity.value?.nostrPrivateKey?.value?.toByteArray() ?: throw IllegalStateException("This identity cannot sign")

    override suspend fun setUp(localChatRoom: LocalChatRoom, relays: List<NormalizedRelayUrl>): InboxKeyCeremony =
        InboxKeyRitualManager.propose(database, localChatRoom, localChatRoom.chatRoom.userPublicKey, secret(), relays)

    override suspend fun rotate(localChatRoom: LocalChatRoom): InboxKeyCeremony =
        InboxKeyRitualManager.propose(database, localChatRoom, localChatRoom.chatRoom.userPublicKey, secret())

    override suspend fun abandon(localChatRoom: LocalChatRoom, ceremonyId: String): Boolean =
        InboxKeyRitualManager.abandon(database, localChatRoom, ceremonyId)

    override suspend fun proposeReply(localChatRoom: LocalChatRoom, conversationId: String, text: String): String =
        InboxReplies.propose(database, localChatRoom, localChatRoom.chatRoom.userPublicKey, conversationId, text).id

    override suspend fun withoutAnnouncedKey(publicKeys: List<HexKey>): List<HexKey> =
        publicKeys.filterNot { InboxReplies.hasAnnouncedKey(database, it) }

    override suspend fun unsentReplies(chatRoomId: String): Set<String> =
        database.groupInboxDao().getMessages(chatRoomId)
            .mapNotNull { it.signingSessionId }
            .filter { session -> database.groupInboxDao().getPublicationsFor(session).any { it.publishedAt == null } }
            .toSet()

    override suspend fun sendAgain(sessionId: String): Boolean = InboxReplies.sendAgain(database, sessionId)
}
