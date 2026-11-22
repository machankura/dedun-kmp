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

    /** Asks the group to sign [relays] as where it receives messages (D8). */
    suspend fun proposeRelays(localChatRoom: LocalChatRoom, userPublicKey: HexKey, relays: List<String>): FrostSigningSession
}
