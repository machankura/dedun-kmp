package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.InboxKey
import com.machankura.dedun.compose.database.model.InboxKeyCeremony
import com.machankura.dedun.compose.database.model.InboxKeyCeremonyMessage
import kotlin.time.Instant

/** The group inbox's key ceremonies and the keys they leave (docs/group-inbox.md, Phase 3). */
@Dao
interface InboxKeyDao {
    @Query("SELECT * FROM InboxKeyCeremony WHERE id = :id")
    suspend fun getCeremony(id: String): InboxKeyCeremony?

    @Query("SELECT * FROM InboxKeyCeremony WHERE id = :id")
    fun observeCeremony(id: String): Flow<InboxKeyCeremony?>

    @Query("SELECT * FROM InboxKeyCeremony WHERE chatRoomId = :chatRoomId AND userPublicKey = :userPublicKey ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestCeremony(chatRoomId: String, userPublicKey: HexKey): InboxKeyCeremony?

    @Query("SELECT * FROM InboxKeyCeremony WHERE chatRoomId = :chatRoomId AND userPublicKey = :userPublicKey ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestCeremony(chatRoomId: String, userPublicKey: HexKey): Flow<InboxKeyCeremony?>

    @Query("SELECT * FROM InboxKeyCeremony WHERE userPublicKey = :userPublicKey AND stage NOT IN ('COMPLETE', 'FAILED')")
    suspend fun getRunningCeremonies(userPublicKey: HexKey): List<InboxKeyCeremony>

    @Query("SELECT * FROM InboxKeyCeremony WHERE chatRoomId = :chatRoomId AND stage NOT IN ('COMPLETE', 'FAILED')")
    suspend fun getRunningCeremoniesIn(chatRoomId: String): List<InboxKeyCeremony>

    /** Every finished ceremony in a room as this identity saw it, newest generation first. */
    @Query("SELECT * FROM InboxKeyCeremony WHERE chatRoomId = :chatRoomId AND userPublicKey = :userPublicKey AND stage = 'COMPLETE' ORDER BY generation DESC, createdAt DESC")
    suspend fun getCompletedCeremonies(chatRoomId: String, userPublicKey: HexKey): List<InboxKeyCeremony>

    @Upsert
    suspend fun upsertCeremony(ceremony: InboxKeyCeremony)

    @Query("SELECT * FROM InboxKeyCeremonyMessage WHERE sessionId = :sessionId")
    suspend fun getMessages(sessionId: String): List<InboxKeyCeremonyMessage>

    @Query("SELECT * FROM InboxKeyCeremonyMessage WHERE sessionId = :sessionId")
    fun observeMessages(sessionId: String): Flow<List<InboxKeyCeremonyMessage>>

    @Query("SELECT * FROM InboxKeyCeremonyMessage WHERE sessionId = :sessionId AND senderPublicKey = :sender AND kind = :kind")
    suspend fun getMessage(sessionId: String, sender: HexKey, kind: Kind): InboxKeyCeremonyMessage?

    /** Ignores a second copy: relays redeliver, and the first one a ceremony read is the one it used. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: InboxKeyCeremonyMessage): Long

    @Query("SELECT * FROM InboxKey WHERE chatRoomId = :chatRoomId AND userPublicKey = :userPublicKey ORDER BY generation DESC")
    suspend fun getKeys(chatRoomId: String, userPublicKey: HexKey): List<InboxKey>

    @Query("SELECT * FROM InboxKey WHERE chatRoomId = :chatRoomId AND userPublicKey = :userPublicKey ORDER BY generation DESC")
    fun observeKeys(chatRoomId: String, userPublicKey: HexKey): Flow<List<InboxKey>>

    @Query("SELECT * FROM InboxKey WHERE chatRoomId = :chatRoomId AND userPublicKey = :userPublicKey AND generation = :generation")
    suspend fun getKey(chatRoomId: String, userPublicKey: HexKey, generation: Int): InboxKey?

    /** Every key this identity can still open mail with, in every room, newest generation first. */
    @Query("SELECT * FROM InboxKey WHERE userPublicKey = :userPublicKey AND secretKey IS NOT NULL ORDER BY chatRoomId, generation DESC")
    suspend fun getHeldKeys(userPublicKey: HexKey): List<InboxKey>

    @Query("SELECT * FROM InboxKey WHERE userPublicKey = :userPublicKey AND secretKey IS NOT NULL ORDER BY chatRoomId, generation DESC")
    fun observeHeldKeys(userPublicKey: HexKey): Flow<List<InboxKey>>

    /** Any key a 10044 could name in this room, held here or not: [publicKey] is public. */
    @Query("SELECT * FROM InboxKey WHERE chatRoomId = :chatRoomId AND publicKey = :publicKey LIMIT 1")
    suspend fun findByPublicKey(chatRoomId: String, publicKey: HexKey): InboxKey?

    /** The room an inbox key belongs to, for a wrap tagged with the key rather than the group. */
    @Query("SELECT * FROM InboxKey WHERE userPublicKey = :userPublicKey AND publicKey = :publicKey LIMIT 1")
    suspend fun findHeldByPublicKey(userPublicKey: HexKey, publicKey: HexKey): InboxKey?

    @Upsert
    suspend fun upsertKey(key: InboxKey)

    @Query("UPDATE InboxKey SET secretKey = NULL, recoveryData = NULL, state = 'WIPED', wipedAt = :at WHERE chatRoomId = :chatRoomId AND userPublicKey = :userPublicKey")
    suspend fun wipeKeys(chatRoomId: String, userPublicKey: HexKey, at: Instant)
}
