package com.machankura.dedun.compose.repository

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupDmOutbound
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

/**
 * A group's direct messages on this device, for the relay layer and the screens
 * (docs/group-threshold-ecdh.md).
 */
interface GroupDmRepository {
    /** What this device owes relays: a signed 10050, or a wrap of a message the group signed. */
    fun observeOwed(): Flow<List<GroupDmOutbound>>

    suspend fun markPublished(outbound: GroupDmOutbound, at: Instant)

    suspend fun markAttempt(outbound: GroupDmOutbound, next: Instant)

    /** Where [chatRoomId] receives messages, as it changes; null while it has not opened them. */
    fun observeRelays(chatRoomId: HexKey): Flow<List<String>?>

    /** Whether [userPublicKey] is a current admin of [chatRoomId] holding a share: who sees the group's mail (D4, D12). */
    suspend fun isCurrentAdmin(chatRoomId: HexKey, userPublicKey: HexKey): Boolean

    /**
     * The rooms whose mail [userPublicKey] reads, with the relays to ask on and how far back,
     * as they change: what `live-group-dm` subscribes to (D9).
     */
    fun observeReadableRooms(userPublicKey: HexKey): Flow<List<com.machankura.dedun.compose.managers.GroupDmInbox>>

    /** A wrap a relay sent: into the group's own pipeline if it is one of its, else nothing. Whether it was. */
    suspend fun receiveWrap(wrap: com.vitorpamplona.quartz.nip01Core.core.Event, userPublicKey: HexKey, nostrPrivateKey: ByteArray): Boolean

    /** The group's conversations, newest first (Phase 8). */
    fun observeConversations(chatRoomId: HexKey): Flow<List<com.machankura.dedun.compose.ui.view.state.GroupDmConversationSummary>>

    /** What the group was sent and has not opened yet, and on whom it waits. */
    fun observePending(chatRoomId: HexKey): Flow<com.machankura.dedun.compose.ui.view.state.GroupDmPending>

    /** One conversation's messages, oldest first, in and out. */
    fun observeConversation(chatRoomId: HexKey, conversationId: HexKey): Flow<List<com.machankura.dedun.compose.ui.view.state.GroupDmLine>>

    /** The participants of [conversationId], from the messages this device holds. */
    suspend fun participantsOf(chatRoomId: HexKey, conversationId: HexKey): List<HexKey>

    suspend fun markRead(chatRoomId: HexKey, conversationId: HexKey)

    /** How a screen names a person: their profile's name, or the start of their key. */
    suspend fun nameOf(publicKey: HexKey): String

    /** Asks the group to send [text] to [recipients] (D10), as the active identity. */
    suspend fun send(localChatRoom: LocalChatRoom, recipients: List<HexKey>, text: String, replyTo: HexKey?): com.machankura.dedun.compose.managers.GroupDmSender.Outcome

    /** Wraps and sends again what a signed message still owes its recipients. */
    suspend fun sendAgain(signingSessionId: String): Boolean

    /** Asks the group to sign [relays] as where it receives messages (D8). */
    suspend fun proposeRelays(localChatRoom: LocalChatRoom, userPublicKey: HexKey, relays: List<String>): FrostSigningSession
}
