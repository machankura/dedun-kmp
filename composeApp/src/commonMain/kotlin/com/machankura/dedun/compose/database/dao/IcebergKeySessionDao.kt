package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.IcebergParticipantMessage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow

@Dao
interface IcebergKeySessionDao {
    @Query("SELECT * FROM IcebergKeySession WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): IcebergKeySession?

    @Query("SELECT * FROM IcebergKeySession WHERE id = :sessionId")
    fun observeSessionById(sessionId: String): Flow<IcebergKeySession?>

    @Query("SELECT * FROM IcebergKeySession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    suspend fun getSessionsForChatRoom(chatRoomId: String): List<IcebergKeySession>

    @Query("SELECT * FROM IcebergKeySession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<IcebergKeySession>>

    @Query("SELECT * FROM IcebergKeySession WHERE stage = 'COMPLETE'")
    suspend fun getCompletedSessions(): List<IcebergKeySession>

    /** Every ceremony still under way, on any identity's account: neither finished nor failed. */
    @Query("SELECT * FROM IcebergKeySession WHERE stage NOT IN ('COMPLETE', 'FAILED')")
    suspend fun getRunningSessions(): List<IcebergKeySession>

    @Upsert
    suspend fun upsert(session: IcebergKeySession)

    @Upsert
    suspend fun upsert(message: IcebergParticipantMessage)

    @Query("SELECT * FROM IcebergParticipantMessage WHERE sessionId = :sessionId AND kind = :kind")
    suspend fun getMessagesByKind(sessionId: String, kind: Int): List<IcebergParticipantMessage>

    @Query("SELECT * FROM IcebergParticipantMessage WHERE sessionId = :sessionId AND kind = :kind AND participantPublicKey = :participantPublicKey")
    suspend fun getMessage(sessionId: String, kind: Int, participantPublicKey: HexKey): IcebergParticipantMessage?

    @Query("SELECT COUNT(*) FROM IcebergParticipantMessage WHERE sessionId = :sessionId AND kind = :kind")
    suspend fun countMessagesByKind(sessionId: String, kind: Int): Int
}
