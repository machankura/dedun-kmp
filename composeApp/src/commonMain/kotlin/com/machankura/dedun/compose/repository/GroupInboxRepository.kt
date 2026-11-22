package com.machankura.dedun.compose.repository

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.GroupInboxMessage
import com.machankura.dedun.compose.database.model.GroupInboxPublication
import com.machankura.dedun.compose.database.model.InboxKey
import com.machankura.dedun.compose.database.model.InboxKeyCeremony
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

/** The group's inbox, as the screens and the sync see it (docs/group-inbox.md). */
interface GroupInboxRepository {
    /** What this device owes relays on its groups' behalf and has not yet got onto one. */
    fun observeUnpublished(): Flow<List<GroupInboxPublication>>

    suspend fun markPublished(eventId: HexKey, at: Instant)

    /**
     * A group whose mail this identity can open: its npub, the inbox keys this device holds for
     * it, and where its mail is announced to arrive.
     */
    data class Route(val chatRoomId: String, val keys: List<HexKey>, val relays: List<NormalizedRelayUrl>)

    /** Every group [userPublicKey] holds an inbox key for, re-emitted when that changes (D8). */
    fun observeRoutes(userPublicKey: HexKey): Flow<List<Route>>

    /** Files a gift wrap heard on a relay for one of [userPublicKey]'s groups. */
    suspend fun receive(wrap: Event, userPublicKey: HexKey): GroupInboxMessage?

    // ---- the screens (Phase 7) ----------------------------------------------------------------

    /** One conversation in a group's inbox, as its list shows it. */
    data class ConversationSummary(
        val conversationId: String,
        val participants: List<HexKey>,
        val names: List<String>,
        val lastText: String,
        val lastAt: Long,
        val lastOutgoing: Boolean,
        val unread: Int,
    )

    /** What a group's inbox is on this device: its keys, its newest ceremony, what it announced. */
    data class Status(
        val keys: List<InboxKey>,
        val ceremony: InboxKeyCeremony?,
        /** The key the group's newest signed 10044 names, or null if it has announced none. */
        val announcedKey: HexKey?,
        val relays: List<NormalizedRelayUrl>,
        /** Why this identity cannot start a ceremony now, or null if it can. */
        val setUpRefusal: String?,
        /** The admins a running ceremony waits on, by name. */
        val waitingOn: List<String>,
    ) {
        val heldKey: InboxKey? get() = keys.firstOrNull { it.secretKey != null }
    }

    fun observeConversations(chatRoomId: String): Flow<List<ConversationSummary>> = emptyFlow()

    fun observeConversation(chatRoomId: String, conversationId: String): Flow<List<GroupInboxMessage>> = flowOf(emptyList())

    fun observeUnopenedCount(chatRoomId: String): Flow<Int> = flowOf(0)

    fun observeStatus(localChatRoom: LocalChatRoom, userPublicKey: HexKey): Flow<Status> = emptyFlow()

    suspend fun markRead(chatRoomId: String, conversationId: String) = Unit

    suspend fun namesOf(publicKeys: List<HexKey>): Map<HexKey, String> = emptyMap()

    /** Starts the first inbox key ceremony, with mail to arrive on [relays]. */
    suspend fun setUp(localChatRoom: LocalChatRoom, relays: List<NormalizedRelayUrl>): InboxKeyCeremony =
        throw UnsupportedOperationException()

    /** Starts a ceremony for the next generation of the key (D14). */
    suspend fun rotate(localChatRoom: LocalChatRoom): InboxKeyCeremony = throw UnsupportedOperationException()

    suspend fun abandon(localChatRoom: LocalChatRoom, ceremonyId: String): Boolean = false

    companion object {
        val NO_OP = object : GroupInboxRepository {
            override fun observeUnpublished(): Flow<List<GroupInboxPublication>> = flowOf(emptyList())
            override suspend fun markPublished(eventId: HexKey, at: Instant) = Unit
            override fun observeRoutes(userPublicKey: HexKey): Flow<List<Route>> = flowOf(emptyList())
            override suspend fun receive(wrap: Event, userPublicKey: HexKey): GroupInboxMessage? = null
        }
    }
}
