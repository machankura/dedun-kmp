package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.coroutines.flow.Flow

@Dao
interface DkgSessionDao {
    @Query("SELECT * FROM DkgSession WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): DkgSession?

    @Query("SELECT * FROM DkgSession WHERE id = :sessionId")
    fun observeSessionById(sessionId: String): Flow<DkgSession?>

    /**
     * The ritual a room is currently running, newest first. A group may have
     * abandoned earlier attempts; the live one is the most recent.
     */
    @Query("SELECT * FROM DkgSession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestSessionForChatRoom(chatRoomId: String): Flow<DkgSession?>

    @Query("SELECT * FROM DkgSession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestSessionForChatRoom(chatRoomId: String): DkgSession?

    /**
     * The room's newest ceremony **of its own**, newest first -- never one it is
     * only hosting on a subgroup's behalf.
     *
     * The distinction did not exist while every ceremony ran in a NIP-17 room of
     * its own. It does now: a subgroup's ChillDKG runs in the parent's Marmot
     * room, so that room holds a completed ceremony whose key is the *child's*,
     * and a caller asking "what key does this room sign with" must not be handed
     * it. That is `FrostSigningManager.completedKey`'s last fallback, which is
     * reached by every parent member who holds no key-state row -- which is
     * every member welcomed after the parent's own ceremony.
     *
     * A ceremony run to make a subgroup is never the room's own key, and
     * `parentChatRoomId` is the only thing that has to be read to know it. It is
     * an unverified claim, but not a dangerous one here: the direction it can be
     * abused in is a member *excluding* a ceremony from this answer, and a
     * ceremony they can exclude is one they proposed, whose key they would
     * simply not have proposed at all.
     */
    @Query(
        "SELECT * FROM DkgSession WHERE chatRoomId = :chatRoomId AND parentChatRoomId IS NULL " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun getLatestOwnSessionForChatRoom(chatRoomId: String): DkgSession?

    /**
     * The room's newest ceremony *for one purpose*: the group's own when
     * [parentChatRoomId] is null, or the subgroup of that parent when it is not.
     *
     * A room routinely holds more than one, and since the move it is the ordinary
     * case rather than a corner: a parent's Marmot room hosts every subgroup
     * ceremony the group ever runs, alongside whatever it holds of its own.
     * `DkgSession.parentChatRoomId` is what tells those apart.
     *
     * It does **not** tell two subgroups of the same parent apart -- they share
     * both columns. Use [getLatestSubgroupSessionFor] where the question is which
     * subgroup; this answers "does this room hold a ceremony for that parent at
     * all", which is what a screen scoped to one lineage wants.
     *
     * Everything else about a ceremony is already keyed by session id -- the
     * messages, the approvals, the transcript -- so nothing below this needed to
     * learn about the second one.
     */
    @Query(
        "SELECT * FROM DkgSession WHERE chatRoomId = :chatRoomId AND " +
            "(parentChatRoomId = :parentChatRoomId OR " +
            "(:parentChatRoomId IS NULL AND parentChatRoomId IS NULL)) " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun getLatestSessionFor(chatRoomId: String, parentChatRoomId: String?): DkgSession?

    @Query(
        "SELECT * FROM DkgSession WHERE chatRoomId = :chatRoomId AND " +
            "(parentChatRoomId = :parentChatRoomId OR " +
            "(:parentChatRoomId IS NULL AND parentChatRoomId IS NULL)) " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    fun observeLatestSessionFor(chatRoomId: String, parentChatRoomId: String?): Flow<DkgSession?>

    /**
     * The newest ceremony making a subgroup of [parentChatRoomId] over exactly
     * these admins, or null if this device knows of none.
     *
     * What "may I open another" means for a subgroup, now that the ceremony runs
     * in the parent's room. It used to be answered by the room: the ceremony room
     * was derived from the admins, so the same admins were the same room and
     * [getLatestSessionFor] folded a second ask into the first. The parent's room
     * is the same room for every subgroup it ever makes, so that scoping would
     * refuse the parent's *second* subgroup on the strength of its first.
     *
     * [participantPublicKeys] is the same sorted string
     * `DkgSession.formatParticipants` writes, which is why it is sorted: the
     * coordinator builds the set from a picker and every other device from
     * p-tags, and the two have to match as strings for this to find anything.
     *
     * Deliberately not scoped by stage -- the caller decides what a *finished*
     * ceremony means. `ChillDkgRitualManager.proposeRitual` folds into one that
     * is still running and opens a new one otherwise, because a subgroup that
     * has been made is not a reason to refuse making another over the same
     * people.
     */
    @Query(
        "SELECT * FROM DkgSession WHERE chatRoomId = :chatRoomId AND " +
            "parentChatRoomId = :parentChatRoomId AND " +
            "participantPublicKeys = :participantPublicKeys " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun getLatestSubgroupSessionFor(
        chatRoomId: String,
        parentChatRoomId: String,
        participantPublicKeys: String
    ): DkgSession?

    /**
     * Every ceremony this room holds, newest first.
     *
     * A room holds more than one the moment a subgroup's admins are the whole
     * group, so the standing "this needs you" notice in the transcript cannot ask
     * about "the room's ceremony" -- it has to look at all of them and say which.
     */
    @Query("SELECT * FROM DkgSession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<DkgSession>>

    /**
     * Every ceremony this device came out of holding a share, newest first.
     *
     * Signing happens in the admin room, whose id is derived from the key
     * rather than from the room the ceremony ran in, so the key is found by
     * matching that derivation rather than by a stored room id.
     */
    @Query("SELECT * FROM DkgSession WHERE thresholdPublicKey IS NOT NULL AND secretShare IS NOT NULL ORDER BY createdAt DESC")
    suspend fun getKeyHoldingSessions(): List<DkgSession>

    /**
     * Ceremonies opened to make a subgroup of [parentChatRoomId], newest first.
     *
     * How a member gets back into a subgroup flow they closed the app during:
     * the child's room does not exist yet and its certificate may not be signed,
     * so the ceremony is the only thing on this device that knows the flow was
     * ever started.
     *
     * The parent named here is an unverified claim off the proposal -- see
     * `DkgSession.parentChatRoomId` -- so nothing may be granted on the strength
     * of appearing in this list. It decides which screen to offer, not who may
     * do what.
     */
    @Query("SELECT * FROM DkgSession WHERE parentChatRoomId = :parentChatRoomId ORDER BY createdAt DESC")
    suspend fun getByParentChatRoomId(parentChatRoomId: String): List<DkgSession>

    @Upsert
    suspend fun upsert(dkgSession: DkgSession)

    @Upsert
    suspend fun upsert(dkgParticipantMessage: DkgParticipantMessage)

    @Query("SELECT * FROM DkgParticipantMessage WHERE sessionId = :sessionId AND kind = :kind ORDER BY participantPublicKey ASC")
    suspend fun getMessagesByKind(sessionId: String, kind: Kind): List<DkgParticipantMessage>

    @Query("SELECT * FROM DkgParticipantMessage WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeMessages(sessionId: String): Flow<List<DkgParticipantMessage>>

    @Query("SELECT COUNT(*) FROM DkgParticipantMessage WHERE sessionId = :sessionId AND kind = :kind")
    suspend fun countMessagesByKind(sessionId: String, kind: Kind): Int

    @Query("SELECT * FROM DkgParticipantMessage WHERE sessionId = :sessionId AND kind = :kind AND participantPublicKey = :participantPublicKey")
    suspend fun getMessage(sessionId: String, kind: Kind, participantPublicKey: HexKey): DkgParticipantMessage?
}
