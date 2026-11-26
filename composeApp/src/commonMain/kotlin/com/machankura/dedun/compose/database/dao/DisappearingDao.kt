package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.DisappearingSetting
import com.machankura.dedun.compose.database.model.ExpiredGiftWrap
import com.machankura.dedun.compose.database.model.GiftWrapPayload

/** The settings and the tombstones of docs/disappearing-messages.md. */
@Dao
abstract class DisappearingDao {

    // -- the identity's default (D4) ---------------------------------------------------------

    @Query("SELECT defaultSeconds FROM DisappearingSetting WHERE userPublicKey = :userPublicKey")
    abstract suspend fun getDefault(userPublicKey: HexKey): Long?

    @Query("SELECT defaultSeconds FROM DisappearingSetting WHERE userPublicKey = :userPublicKey")
    abstract fun observeDefault(userPublicKey: HexKey): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSetting(setting: DisappearingSetting)

    // -- a conversation's override (D4) ------------------------------------------------------

    @Query("SELECT disappearingMessageSecs FROM ChatRoom WHERE id = :chatRoomId")
    abstract suspend fun getRoomOverride(chatRoomId: String): Long?

    @Query("SELECT disappearingMessageSecs FROM ChatRoom WHERE id = :chatRoomId")
    abstract fun observeRoomOverride(chatRoomId: String): Flow<Long?>

    @Query("UPDATE ChatRoom SET disappearingMessageSecs = :seconds WHERE id = :chatRoomId")
    abstract suspend fun setRoomOverride(chatRoomId: String, seconds: Long?): Int

    @Query("SELECT userPublicKey FROM ChatRoom WHERE id = :chatRoomId")
    abstract suspend fun ownerOf(chatRoomId: String): HexKey?

    // -- the one-time notice (D9) ------------------------------------------------------------

    @Query("SELECT disappearingNoticeShownAt FROM ChatRoom WHERE id = :chatRoomId")
    abstract suspend fun noticeShownAt(chatRoomId: String): Instant?

    @Query("SELECT disappearingNoticeShownAt IS NOT NULL FROM ChatRoom WHERE id = :chatRoomId")
    abstract fun observeNoticeShown(chatRoomId: String): Flow<Boolean?>

    @Query("UPDATE ChatRoom SET disappearingNoticeShownAt = :at WHERE id = :chatRoomId AND disappearingNoticeShownAt IS NULL")
    abstract suspend fun markNoticeShown(chatRoomId: String, at: Instant): Int

    // -- tombstones (D6) ---------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun tombstone(wrap: ExpiredGiftWrap)

    @Query("SELECT EXISTS (SELECT 1 FROM ExpiredGiftWrap WHERE id = :wrapId)")
    abstract suspend fun isTombstoned(wrapId: HexKey): Boolean

    @Query("DELETE FROM ExpiredGiftWrap WHERE dropAfter <= :now")
    abstract suspend fun pruneTombstones(now: Instant): Int

    // -- the sweep (D6, Phase 4) -------------------------------------------------------------
    //
    // The cascade runs NostrEvent -> GiftWrapMessage -> GiftWrapSeal -> GiftWrapPayload ->
    // ChatMessage, and GiftWrapPayload -> ChatRoom through `initialGiftWrapPayloadId`. So a wrap is
    // only ever deleted together with its payload, never before it, and the room is let go of the
    // payload first.

    /** Payloads whose expiry has come. Whether each may go yet is the sweeper's question. */
    @Query("SELECT * FROM GiftWrapPayload WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    abstract suspend fun duePayloads(now: Instant): List<GiftWrapPayload>

    /** The soonest expiry still on this device, which is when the sweeper next has work. */
    @Query("SELECT MIN(expiresAt) FROM GiftWrapPayload WHERE expiresAt IS NOT NULL")
    abstract fun observeNextPayloadExpiry(): Flow<Instant?>

    @Query("SELECT id FROM ChatMessage WHERE giftWrapPayloadId = :payloadId")
    abstract suspend fun linesOf(payloadId: HexKey): List<Long>

    @Query("UPDATE ChatRoom SET initialGiftWrapPayloadId = NULL WHERE initialGiftWrapPayloadId = :payloadId")
    abstract suspend fun releaseRoomsFrom(payloadId: HexKey): Int

    /** Every wrap this device sealed or opened for [payloadId]: one per recipient when it sent it. */
    @Query("DELETE FROM NostrEvent WHERE id IN (SELECT nostrEventId FROM GiftWrapMessage WHERE giftWrapPayloadId = :payloadId)")
    abstract suspend fun deleteWrapsOf(payloadId: HexKey): Int

    /** The wrap around [sealId], for a payload filed before wraps carried their payload's id. */
    @Query(
        "DELETE FROM NostrEvent WHERE id IN (SELECT GiftWrapMessage.nostrEventId FROM GiftWrapMessage " +
            "JOIN GiftWrapSeal ON GiftWrapSeal.giftWrapMessageId = GiftWrapMessage.id WHERE GiftWrapSeal.id = :sealId)"
    )
    abstract suspend fun deleteWrapOfSeal(sealId: HexKey): Int

    @Query("DELETE FROM GiftWrapPayload WHERE id = :payloadId")
    abstract suspend fun deletePayload(payloadId: HexKey): Int

    /**
     * Wraps with no payload here -- addressed to somebody else, or never opened -- whose own expiry
     * has come. Nothing hangs off them: a wrap that was opened carries its payload's id.
     */
    @Query(
        "DELETE FROM NostrEvent WHERE id IN (SELECT nostrEventId FROM GiftWrapMessage " +
            "WHERE giftWrapPayloadId IS NULL AND expiresAt IS NOT NULL AND expiresAt <= :now)"
    )
    abstract suspend fun deleteDueUnlinkedWraps(now: Instant): Int

    /** Ties an opened wrap to what it carried, and to that payload's expiry. */
    @Query("UPDATE GiftWrapMessage SET giftWrapPayloadId = :payloadId, expiresAt = :expiresAt WHERE id = :wrapId")
    abstract suspend fun linkWrap(wrapId: HexKey, payloadId: HexKey, expiresAt: Instant?): Int

    @Query("DELETE FROM GiftWrapSeal WHERE giftWrapMessageId = :wrapId")
    abstract suspend fun deleteSealsOf(wrapId: HexKey): Int

    @Query("UPDATE NostrEvent SET content = '' WHERE id = :wrapId")
    abstract suspend fun blankEvent(wrapId: HexKey): Int

    @Query("UPDATE GiftWrapMessage SET content = '', giftWrapPayloadId = NULL, expiresAt = :dropAfter WHERE id = :wrapId")
    abstract suspend fun blankWrap(wrapId: HexKey, dropAfter: Instant): Int

    /**
     * A wrap whose message arrived already expired: the seal that this device could read goes at
     * once, and the wrap stays as an empty row until [dropAfter], when the sweep's unlinked-wrap
     * rule deletes it. Kept rather than deleted now because the sync that delivered it records the
     * event's id next, against a foreign key; and while the row is there a re-sync finds it already
     * stored and opens nothing. The tombstone covers a relay that serves it after that.
     */
    @Transaction
    open suspend fun dropOnArrival(wrapId: HexKey, dropAfter: Instant, tombstoneUntil: Instant) {
        deleteSealsOf(wrapId)
        blankEvent(wrapId)
        blankWrap(wrapId, dropAfter)
        tombstone(ExpiredGiftWrap(wrapId, tombstoneUntil))
    }

    // -- a group's conversations (Phase 7) ----------------------------------------------------
    //
    // A wrap the group opened is kept as an empty row rather than deleted: it is the ledger that
    // stops a re-sync from opening and filing the same message again.

    @Query("SELECT rumorJson FROM GroupDmMessage WHERE signingSessionId = :signingSessionId LIMIT 1")
    abstract suspend fun groupDmRumorOf(signingSessionId: String): String?

    @Query(
        "UPDATE GroupDmWrap SET wrapJson = '', sealJson = NULL WHERE id IN (SELECT wrapId FROM GroupDmMessage " +
            "WHERE expiresAt IS NOT NULL AND expiresAt <= :now AND wrapId IS NOT NULL)"
    )
    abstract suspend fun blankExpiredGroupDmWraps(now: Instant): Int

    @Query(
        "DELETE FROM GroupDmOutbound WHERE signingSessionId IN (SELECT signingSessionId FROM GroupDmMessage " +
            "WHERE expiresAt IS NOT NULL AND expiresAt <= :now AND signingSessionId IS NOT NULL)"
    )
    abstract suspend fun deleteExpiredGroupDmOutbound(now: Instant): Int

    @Query("DELETE FROM GroupDmMessage WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    abstract suspend fun deleteExpiredGroupDmMessages(now: Instant): Int

    @Query("UPDATE GroupDmWrap SET wrapJson = '', sealJson = NULL WHERE id = :wrapId")
    abstract suspend fun blankGroupDmWrap(wrapId: HexKey): Int

    @Query(
        "UPDATE GroupInboxWrap SET wrapJson = '' WHERE id IN (SELECT wrapId FROM GroupInboxMessage " +
            "WHERE expiresAt IS NOT NULL AND expiresAt <= :now AND wrapId IS NOT NULL AND kind IN (:kinds))"
    )
    abstract suspend fun blankExpiredInboxWraps(now: Instant, kinds: List<Int>): Int

    @Query(
        "DELETE FROM GroupInboxPublication WHERE signingSessionId IN (SELECT signingSessionId FROM GroupInboxMessage " +
            "WHERE expiresAt IS NOT NULL AND expiresAt <= :now AND signingSessionId IS NOT NULL AND kind IN (:kinds))"
    )
    abstract suspend fun deleteExpiredInboxPublications(now: Instant, kinds: List<Int>): Int

    @Query("DELETE FROM GroupInboxMessage WHERE expiresAt IS NOT NULL AND expiresAt <= :now AND kind IN (:kinds)")
    abstract suspend fun deleteExpiredInboxMessages(now: Instant, kinds: List<Int>): Int

    @Query("UPDATE GroupInboxWrap SET wrapJson = '' WHERE id = :wrapId")
    abstract suspend fun blankInboxWrap(wrapId: HexKey): Int

    /**
     * A group's expired messages, in both of its conversation kinds: what the group was sent and
     * what it sent, the wraps' words, and anything still owed to a relay. Only the kinds a person
     * writes: a confederation's statements are filed elsewhere and are the confederation's record.
     */
    @Transaction
    open suspend fun sweepGroupMessages(now: Instant): Int {
        val kinds = com.machankura.dedun.compose.nostr.DisappearingMessages.WRITTEN_KINDS.toList()
        blankExpiredGroupDmWraps(now)
        deleteExpiredGroupDmOutbound(now)
        val dms = deleteExpiredGroupDmMessages(now)
        blankExpiredInboxWraps(now, kinds)
        deleteExpiredInboxPublications(now, kinds)
        return dms + deleteExpiredInboxMessages(now, kinds)
    }

    /**
     * Deletes [payload] and everything holding its words: its wraps, their seals, its chat lines.
     * Returns the ids of the chat lines that went, so a notification showing one can be withdrawn.
     */
    @Transaction
    open suspend fun sweepPayload(payload: GiftWrapPayload): List<Long> {
        val lines = linesOf(payload.id)
        releaseRoomsFrom(payload.id)
        deleteWrapsOf(payload.id)
        payload.giftWrapSealId?.let { deleteWrapOfSeal(it) }
        deletePayload(payload.id)
        return lines
    }
}
