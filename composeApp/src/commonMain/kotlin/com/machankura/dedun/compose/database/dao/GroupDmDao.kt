package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.GroupConversationKey
import com.machankura.dedun.compose.database.model.GroupDmMessage
import com.machankura.dedun.compose.database.model.GroupDmOutbound
import com.machankura.dedun.compose.database.model.GroupDmWrap
import com.machankura.dedun.compose.database.model.GroupEcdhPartial
import com.machankura.dedun.compose.database.model.GroupEcdhRound

/** A group's direct messages and the rounds that open them (docs/group-threshold-ecdh.md, Phase 4). */
@Dao
abstract class GroupDmDao {
    // --- rounds

    @Query("SELECT * FROM GroupEcdhRound WHERE id = :id")
    abstract suspend fun getRound(id: HexKey): GroupEcdhRound?

    @Query("SELECT * FROM GroupEcdhRound WHERE chatRoomId = :chatRoomId AND state = 'OPEN'")
    abstract suspend fun getOpenRounds(chatRoomId: HexKey): List<GroupEcdhRound>

    @Query("SELECT * FROM GroupEcdhRound WHERE chatRoomId = :chatRoomId AND state = 'OPEN'")
    abstract fun observeOpenRounds(chatRoomId: HexKey): Flow<List<GroupEcdhRound>>

    @Query("SELECT * FROM GroupEcdhRound WHERE state = 'OPEN'")
    abstract suspend fun getAllOpenRounds(): List<GroupEcdhRound>

    @Query("SELECT COUNT(*) FROM GroupEcdhRound WHERE chatRoomId = :chatRoomId AND purpose = 'WRAP' AND openedAt >= :since")
    abstract suspend fun countWrapRoundsSince(chatRoomId: HexKey, since: Instant): Int

    /** Once per round: two devices opening the same wrap open the same round. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertRound(round: GroupEcdhRound): Long

    @Upsert
    abstract suspend fun upsertRound(round: GroupEcdhRound)

    // --- partials

    @Query("SELECT * FROM GroupEcdhPartial WHERE roundId = :roundId")
    abstract suspend fun getPartials(roundId: HexKey): List<GroupEcdhPartial>

    @Query("SELECT * FROM GroupEcdhPartial WHERE roundId IN (SELECT id FROM GroupEcdhRound WHERE chatRoomId = :chatRoomId AND state = 'OPEN')")
    abstract fun observeOpenPartials(chatRoomId: HexKey): Flow<List<GroupEcdhPartial>>

    /** Once per round and participant: redelivery is a no-op, as `DkgParticipantMessage` absorbs it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPartial(partial: GroupEcdhPartial): Long

    @Upsert
    abstract suspend fun upsertPartial(partial: GroupEcdhPartial)

    // --- kept conversation keys

    @Query("SELECT * FROM GroupConversationKey WHERE chatRoomId = :chatRoomId AND peer = :peer")
    abstract suspend fun getConversationKey(chatRoomId: HexKey, peer: HexKey): GroupConversationKey?

    @Query("SELECT * FROM GroupConversationKey WHERE chatRoomId = :chatRoomId")
    abstract suspend fun getConversationKeys(chatRoomId: HexKey): List<GroupConversationKey>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertConversationKey(key: GroupConversationKey): Long

    // --- the wrap ledger

    @Query("SELECT * FROM GroupDmWrap WHERE id = :id")
    abstract suspend fun getWrap(id: HexKey): GroupDmWrap?

    @Query("SELECT * FROM GroupDmWrap WHERE chatRoomId = :chatRoomId AND state = :state ORDER BY createdAt DESC")
    abstract suspend fun getWrapsInState(chatRoomId: HexKey, state: String): List<GroupDmWrap>

    @Query("SELECT * FROM GroupDmWrap WHERE chatRoomId = :chatRoomId AND state IN ('WAITING', 'QUEUED') ORDER BY createdAt DESC")
    abstract fun observePendingWraps(chatRoomId: HexKey): Flow<List<GroupDmWrap>>

    /** Emits as the ledger grows, so the subscription's catch-up window moves with it. */
    @Query("SELECT MAX(createdAt) FROM GroupDmWrap")
    abstract fun observeNewestWrapTimes(): Flow<Instant?>

    @Query("SELECT MAX(createdAt) FROM GroupDmWrap WHERE chatRoomId = :chatRoomId")
    abstract suspend fun newestWrapCreatedAt(chatRoomId: HexKey): Instant?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertWrap(wrap: GroupDmWrap): Long

    @Upsert
    abstract suspend fun upsertWrap(wrap: GroupDmWrap)

    // --- messages

    @Query("SELECT * FROM GroupDmMessage WHERE rumorId = :rumorId")
    abstract suspend fun getMessage(rumorId: HexKey): GroupDmMessage?

    @Query("SELECT * FROM GroupDmMessage WHERE signingSessionId = :signingSessionId LIMIT 1")
    abstract suspend fun getMessageBySigningSession(signingSessionId: String): GroupDmMessage?

    @Query("SELECT * FROM GroupDmMessage WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    abstract fun observeMessages(chatRoomId: HexKey): Flow<List<GroupDmMessage>>

    @Query("SELECT * FROM GroupDmMessage WHERE chatRoomId = :chatRoomId AND conversationId = :conversationId ORDER BY createdAt ASC")
    abstract fun observeConversation(chatRoomId: HexKey, conversationId: HexKey): Flow<List<GroupDmMessage>>

    @Query("SELECT * FROM GroupDmMessage WHERE chatRoomId = :chatRoomId")
    abstract suspend fun getMessages(chatRoomId: HexKey): List<GroupDmMessage>

    @Query("SELECT COUNT(*) FROM GroupDmMessage WHERE chatRoomId = :chatRoomId AND outgoing = 0 AND readAt IS NULL")
    abstract fun observeUnreadCount(chatRoomId: HexKey): Flow<Int>

    @Query("SELECT COUNT(*) FROM GroupDmMessage WHERE chatRoomId = :chatRoomId AND outgoing = 0 AND readAt IS NULL")
    abstract suspend fun countUnread(chatRoomId: HexKey): Int

    @Query("UPDATE GroupDmMessage SET readAt = :at WHERE chatRoomId = :chatRoomId AND conversationId = :conversationId AND readAt IS NULL")
    abstract suspend fun markRead(chatRoomId: HexKey, conversationId: HexKey, at: Instant)

    /** Once per rumor: the same rumor in two wraps is one message. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMessage(message: GroupDmMessage): Long

    // --- what the group owes recipients

    @Query("SELECT * FROM GroupDmOutbound WHERE publishedAt IS NULL")
    abstract suspend fun getOwed(): List<GroupDmOutbound>

    /** What this device owes relays and has the event for: what [com.machankura.dedun.compose.managers.GroupDmPublisher] sends. */
    @Query("SELECT * FROM GroupDmOutbound WHERE publishedAt IS NULL AND wrapJson IS NOT NULL")
    abstract fun observeOwed(): Flow<List<GroupDmOutbound>>

    @Query("UPDATE GroupDmOutbound SET publishedAt = :at WHERE signingSessionId = :signingSessionId AND sealId = :sealId")
    abstract suspend fun markPublished(signingSessionId: String, sealId: HexKey, at: Instant)

    @Query("UPDATE GroupDmOutbound SET attempts = attempts + 1, nextAttemptAt = :next WHERE signingSessionId = :signingSessionId AND sealId = :sealId")
    abstract suspend fun markAttempt(signingSessionId: String, sealId: HexKey, next: Instant)

    @Query("SELECT * FROM GroupDmOutbound WHERE signingSessionId = :signingSessionId")
    abstract suspend fun getOutbound(signingSessionId: String): List<GroupDmOutbound>

    @Query("SELECT * FROM GroupDmOutbound WHERE chatRoomId = :chatRoomId")
    abstract fun observeOutbound(chatRoomId: HexKey): Flow<List<GroupDmOutbound>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertOutbound(outbound: GroupDmOutbound): Long

    @Upsert
    abstract suspend fun upsertOutbound(outbound: GroupDmOutbound)

    // --- wiping

    @Query("DELETE FROM GroupConversationKey WHERE chatRoomId = :chatRoomId")
    abstract suspend fun deleteConversationKeys(chatRoomId: HexKey)

    @Query("DELETE FROM GroupDmMessage WHERE chatRoomId = :chatRoomId")
    abstract suspend fun deleteMessages(chatRoomId: HexKey)

    @Query("DELETE FROM GroupDmWrap WHERE chatRoomId = :chatRoomId")
    abstract suspend fun deleteWraps(chatRoomId: HexKey)

    @Query("DELETE FROM GroupEcdhRound WHERE chatRoomId = :chatRoomId")
    abstract suspend fun deleteRounds(chatRoomId: HexKey)

    @Query("DELETE FROM GroupDmOutbound WHERE chatRoomId = :chatRoomId")
    abstract suspend fun deleteOutbound(chatRoomId: HexKey)

    /**
     * Everything this device holds of a room's mail: its kept keys, what it opened, and the
     * rounds in flight (D14). Cooperation, not enforcement: what was read stays read.
     */
    @Transaction
    open suspend fun wipeRoom(chatRoomId: HexKey) {
        deleteConversationKeys(chatRoomId)
        deleteMessages(chatRoomId)
        deleteWraps(chatRoomId)
        deleteRounds(chatRoomId)
        deleteOutbound(chatRoomId)
    }
}
