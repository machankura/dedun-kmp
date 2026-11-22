package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.DisappearingSetting
import com.machankura.dedun.compose.database.model.ExpiredGiftWrap

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

    @Query("UPDATE ChatRoom SET disappearingNoticeShownAt = :at WHERE id = :chatRoomId AND disappearingNoticeShownAt IS NULL")
    abstract suspend fun markNoticeShown(chatRoomId: String, at: Instant): Int

    // -- tombstones (D6) ---------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun tombstone(wrap: ExpiredGiftWrap)

    @Query("SELECT EXISTS (SELECT 1 FROM ExpiredGiftWrap WHERE id = :wrapId)")
    abstract suspend fun isTombstoned(wrapId: HexKey): Boolean

    @Query("DELETE FROM ExpiredGiftWrap WHERE dropAfter <= :now")
    abstract suspend fun pruneTombstones(now: Instant): Int
}
