package com.machankura.dedun.compose.repository

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.GroupInboxMessage
import com.machankura.dedun.compose.database.model.GroupInboxPublication

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
}
