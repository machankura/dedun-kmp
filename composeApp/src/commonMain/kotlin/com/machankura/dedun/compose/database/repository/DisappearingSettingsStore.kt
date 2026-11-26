package com.machankura.dedun.compose.database.repository

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.DisappearingSetting
import com.machankura.dedun.compose.nostr.DisappearingMessages

/**
 * How long what a person writes lasts: their default, and a conversation's override.
 * docs/disappearing-messages.md, D4. The screens read and write through this; the send path asks
 * [policyFor] once per message.
 */
class DisappearingSettingsStore(private val database: DedunDatabase) {

    /** The identity's default, a day until they choose otherwise. */
    suspend fun defaultFor(userPublicKey: HexKey): Long =
        database.disappearingDao().getDefault(userPublicKey) ?: DisappearingMessages.DEFAULT_SECONDS

    fun observeDefault(userPublicKey: HexKey): Flow<Long> =
        database.disappearingDao().observeDefault(userPublicKey).map { it ?: DisappearingMessages.DEFAULT_SECONDS }

    suspend fun saveDefault(userPublicKey: HexKey, seconds: Long) {
        require(DisappearingMessages.isValidPolicy(seconds)) { "Not a policy: $seconds seconds" }
        database.disappearingDao().upsertSetting(DisappearingSetting(userPublicKey, seconds, Clock.System.now()))
    }

    /** The conversation's own setting: null follows the default, `0` is Off here only. */
    fun observeOverride(chatRoomId: String): Flow<Long?> = database.disappearingDao().observeRoomOverride(chatRoomId)

    suspend fun saveOverride(chatRoomId: String, seconds: Long?) {
        require(seconds == null || DisappearingMessages.isValidPolicy(seconds)) { "Not a policy: $seconds seconds" }
        database.disappearingDao().setRoomOverride(chatRoomId, seconds)
    }

    /** Whether the one-time notice has been shown in [chatRoomId] (D9); a room not found reads as shown. */
    fun observeNoticeShown(chatRoomId: String): Flow<Boolean> =
        database.disappearingDao().observeNoticeShown(chatRoomId).map { it ?: true }

    suspend fun markNoticeShown(chatRoomId: String, at: Instant) {
        database.disappearingDao().markNoticeShown(chatRoomId, at)
    }

    /** The policy a message queued now in [chatRoomId] by [userPublicKey] is stamped with. */
    suspend fun policyFor(chatRoomId: String, userPublicKey: HexKey): Long =
        DisappearingMessages.policyFor(database.disappearingDao().getRoomOverride(chatRoomId), defaultFor(userPublicKey))

    /**
     * [tags] with the rumor's expiry under the room's policy: what every producer of a NIP-17
     * `GiftWrapPayload` calls *before* it hashes the rumor's id, since the tag is one of the fields
     * the id covers. docs/disappearing-messages.md, Phase 3.
     */
    suspend fun stamp(chatRoomId: String, userPublicKey: HexKey, kind: Kind, createdAt: Long, tags: Array<Array<String>>): Array<Array<String>> =
        DisappearingMessages.stamp(kind, createdAt, policyFor(chatRoomId, userPublicKey), tags)
}
