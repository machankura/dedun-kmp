package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.FrostSignerMessage
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.FrostSignerTally
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession

@Dao
interface FrostSigningSessionDao {
    @Query("SELECT * FROM FrostSigningSession WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): FrostSigningSession?

    @Query("SELECT * FROM FrostSigningSession WHERE id = :sessionId")
    fun observeSessionById(sessionId: String): Flow<FrostSigningSession?>

    /**
     * A room's signing sessions, newest first, each with the events it signs and
     * the member who proposed it.
     *
     * Unlike a DKG a group signs repeatedly, so there is no single "current" one
     * to observe -- and no single one to show, which is what the proposal list
     * exists for. See [LocalFrostSigningSession] for why the items travel with
     * the session rather than being fetched per row.
     */
    @Transaction
    @Query("SELECT * FROM FrostSigningSession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<LocalFrostSigningSession>>

    /**
     * How many members have sent each kind of message, for every session in a
     * room, in one query.
     *
     * The list shows each proposal's progress -- three of the four nonces it
     * needs, two of the three signatures -- and a query per row would be one per
     * proposal on a screen whose whole point is that a room has several.
     *
     * The join to `DkgParticipantMessage` is what makes the figure honest rather
     * than merely available. A nonce or a partial signature is recorded without
     * any check on who sent it, unlike a refusal; `FrostSigningManager`'s
     * `orderedNonces` then drops the ones from members who hold no share when it
     * aggregates. Counting those here would have a row read ahead of the session
     * it describes -- saying the group has what it needs while the coordinator
     * is still waiting -- which is the one direction a progress figure must
     * never be wrong in. `HOST_KEY` is the message that makes somebody a share
     * holder, and the entity's primary key makes it at most one row per member,
     * so the join cannot multiply a count either.
     *
     * @param hostKeyKind `DkgRitualEvents.HOST_KEY`; passed rather than written
     *   in, because a kind inlined into SQL is a kind nothing would renumber.
     */
    @Query(
        """
        SELECT m.sessionId AS sessionId,
               m.kind AS kind,
               COUNT(DISTINCT m.signerPublicKey) AS members
          FROM FrostSignerMessage m
          JOIN FrostSigningSession s ON s.id = m.sessionId
          JOIN DkgParticipantMessage d ON d.sessionId = s.dkgSessionId
                                      AND d.participantPublicKey = m.signerPublicKey
                                      AND d.kind = :hostKeyKind
         WHERE s.chatRoomId = :chatRoomId AND m.kind IN (:kinds)
         GROUP BY m.sessionId, m.kind
        """
    )
    fun observeSignerTalliesForChatRoom(
        chatRoomId: String,
        kinds: List<Kind>,
        hostKeyKind: Kind,
    ): Flow<List<FrostSignerTally>>

    @Query("SELECT * FROM FrostSigningSession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestSessionForChatRoom(chatRoomId: String): FrostSigningSession?

    @Query("SELECT * FROM FrostSigningSession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    suspend fun getSessionsForChatRoom(chatRoomId: String): List<FrostSigningSession>

    @Upsert
    suspend fun upsert(frostSigningSession: FrostSigningSession)

    @Upsert
    suspend fun upsert(frostSignerMessage: FrostSignerMessage)

    /**
     * A session's events, in the order the batch fixed at proposal.
     *
     * Every join of nonces and partial signatures is positional, so this
     * ordering is part of the protocol rather than a presentation choice —
     * two devices reading a batch in different orders aggregate against
     * different messages.
     */
    @Query("SELECT * FROM FrostSigningItem WHERE sessionId = :sessionId ORDER BY itemIndex ASC")
    suspend fun getItems(sessionId: String): List<FrostSigningItem>

    @Query("SELECT * FROM FrostSigningItem WHERE sessionId = :sessionId ORDER BY itemIndex ASC")
    fun observeItems(sessionId: String): Flow<List<FrostSigningItem>>

    @Query("SELECT * FROM FrostSigningItem WHERE sessionId = :sessionId AND itemIndex = :itemIndex")
    suspend fun getItem(sessionId: String, itemIndex: Int): FrostSigningItem?

    @Upsert
    suspend fun upsert(frostSigningItem: FrostSigningItem)

    /**
     * Writes a whole batch's worth of items at once.
     *
     * A transaction rather than a loop because the coordinator's two broadcasts
     * each settle every item together: a half-written aggregate would leave the
     * session gated on `signerIds` while some items had no nonce to aggregate
     * against, which is a stall no message can clear.
     */
    @Transaction
    @Upsert
    suspend fun upsertItems(items: List<FrostSigningItem>)

    /** How many events this session signs. Derived rather than stored — see [FrostSigningItem]. */
    @Query("SELECT COUNT(*) FROM FrostSigningItem WHERE sessionId = :sessionId")
    suspend fun countItems(sessionId: String): Int

    /** How many of them the group has finished. Equal to [countItems] once it is done. */
    @Query("SELECT COUNT(*) FROM FrostSigningItem WHERE sessionId = :sessionId AND signature IS NOT NULL")
    suspend fun countSignedItems(sessionId: String): Int

    @Query("SELECT * FROM FrostSignerMessage WHERE sessionId = :sessionId AND kind = :kind ORDER BY createdAt ASC")
    suspend fun getMessagesByKind(sessionId: String, kind: Kind): List<FrostSignerMessage>

    @Query("SELECT * FROM FrostSignerMessage WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeMessages(sessionId: String): Flow<List<FrostSignerMessage>>

    /**
     * Every message a session has received, of every kind.
     *
     * What a resend reads to work out who it is still waiting on. Which rows
     * count as an answer depends on what the session is collecting: while it
     * wants nonces, any row at all -- a nonce, a partial signature, a refusal;
     * once the signers are chosen, only their partial signature or their
     * refusal. See `FrostSigningManager.membersOwing`.
     */
    @Query("SELECT * FROM FrostSignerMessage WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getMessagesForSession(sessionId: String): List<FrostSignerMessage>

    @Query("SELECT COUNT(*) FROM FrostSignerMessage WHERE sessionId = :sessionId AND kind = :kind")
    suspend fun countMessagesByKind(sessionId: String, kind: Kind): Int

    @Query("SELECT * FROM FrostSignerMessage WHERE sessionId = :sessionId AND kind = :kind AND signerPublicKey = :signerPublicKey")
    suspend fun getMessage(sessionId: String, kind: Kind, signerPublicKey: HexKey): FrostSignerMessage?
}
