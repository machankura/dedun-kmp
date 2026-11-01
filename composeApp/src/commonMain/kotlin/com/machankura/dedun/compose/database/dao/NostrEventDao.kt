package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.IGNORE
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant

@Dao
interface NostrEventDao {
    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE kind in (:kinds) ORDER BY createdAt DESC")
    fun  observeNostrEvents(kinds: Array<Kind>): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE content LIKE '%' || :search || '%' AND kind in (:kinds) ORDER BY createdAt DESC")
    fun  observeFilteredNostrEvents(
        kinds: Array<Kind>,
        search: String
    ): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE kind in (:kinds) AND id in (:ids) ORDER BY createdAt DESC")
    fun  observeFilteredNostrEvents(
        kinds: Array<Kind>,
        ids: Array<HexKey>,
    ): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE kind in (:kinds) AND pubKey in (:authors) ORDER BY createdAt DESC LIMIT 50")
    fun  observeAuthoredNostrEvents(
        kinds: Array<Kind>,
        authors: Array<HexKey>,
    ): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE tags LIKE '%' || :publicKey || '%' AND kind in (:kinds) ORDER BY createdAt DESC")
    fun  observePublicKeyMentionedNostrEvents(
        kinds: Array<Kind>,
        publicKey: HexKey
    ): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE tags LIKE '%' || :eventId || '%reply%' AND kind in (:kinds) AND createdAt > :since ORDER BY createdAt DESC")
    fun  observeNostrEventReplies(
        kinds: Array<Kind>,
        eventId: HexKey,
        since: Instant
    ): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>>

    @Transaction
    @RawQuery
    fun  observePublicKeyFollowingNostrEvents(
        query: RoomRawQuery
    ): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>>

    /**
     * Events matching an arbitrary NIP-01 filter, built by
     * [com.machankura.dedun.compose.database.query.NostrEventFilterQuery]. Raw because a nostr filter is
     * a variable set of constraints over variable-length lists, which `@Query` cannot express
     * without one method per shape — and the per-shape methods this replaced each dropped the
     * constraints they had no parameter for.
     */
    @RawQuery
    suspend fun  getNostrEventsMatchingFilter(
        query: RoomRawQuery
    ): List<NostrEvent>

    /**
     * The same query, watched. Observed tables named explicitly because a raw query cannot
     * infer them: the events themselves, and the profiles joined onto them, so a row whose
     * author's kind 0 arrives after the row does re-emits with the name filled in.
     */
    @Transaction
    @RawQuery(observedEntities = [NostrEvent::class, com.machankura.dedun.compose.database.model.Profile::class])
    fun  observeNostrEventsMatchingFilter(
        query: RoomRawQuery
    ): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE kind in (:kinds) AND createdAt > :since ORDER BY createdAt ASC LIMIT :limit")
    suspend fun  getNostrEvents(
        kinds: Array<Kind>,
        since: Instant,
        limit: Int
    ): List<NostrEvent>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE content LIKE '%' || :search || '%' AND kind in (:kinds) AND createdAt > :since ORDER BY createdAt ASC LIMIT :limit")
    suspend fun  getFilteredNostrEvents(
        kinds: Array<Kind>,
        search: String,
        since: Instant,
        limit: Int
    ): List<NostrEvent>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE kind in (:kinds) AND id in (:ids) AND createdAt > :since ORDER BY createdAt ASC LIMIT :limit")
    suspend fun  getFilteredNostrEvents(
        kinds: Array<Kind>,
        ids: Array<HexKey>,
        since: Instant,
        limit: Int
    ): List<NostrEvent>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE id in (:ids) AND createdAt > :since ORDER BY createdAt ASC LIMIT :limit")
    suspend fun  getNostrEventsByIds(
        ids: Array<HexKey>,
        since: Instant,
        limit: Int
    ): List<NostrEvent>


    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE kind = ${GroupEvent.KIND} AND createdAt > :since ORDER BY createdAt ASC LIMIT :limit")
    suspend fun  getMLSGroupEvents(
        since: Instant,
        limit: Int
    ): List<NostrEvent>

    /**
     * The events a relay would still serve for `{"kinds":[445],"#h":[...]}`.
     *
     * `createdAt >= :since AND createdAt <= :until` because NIP-01 bounds are inclusive, and
     * `expiresAt > :now` because a NIP-40 expiring event is one a relay has stopped serving.
     * The predicate here used to read `expiresAt < :now`, which kept exactly the expired
     * messages and dropped every live one — so for the whole group-chat sync path the set
     * handed to negentropy was the complement of the relay's.
     */
    @Transaction
    @Query(
        "SELECT * FROM MarmotGroupEvent WHERE chatRoomId in (:chatRoomIds) " +
            "AND createdAt >= :since AND createdAt <= :until " +
            "AND (expiresAt IS NULL OR expiresAt > :now) " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun  getMarmotGroupEvents(
        chatRoomIds: Array<HexKey>,
        since: Instant,
        until: Instant,
        limit: Int,
        now: Instant = Clock.System.now(),
    ): List<MarmotGroupEvent>

    /**
     * Every kind:445 held locally that mentions [chatRoomId], oldest first.
     *
     * The `LIKE` is a prefilter over the serialised tags, not the test: it can match
     * a group id that happens to appear in some other tag, so callers must confirm
     * the event's own `h` tag before treating it as this room's. Ordered ascending
     * because a replay has to apply commits in the order they were sent.
     *
     * Deliberately not a join on `MarmotGroupEvent`: an event whose indexing failed
     * part way may have been stored without ever reaching that table, and those are
     * exactly the ones worth replaying.
     */
    @Transaction
    @Query(
        "SELECT * FROM NostrEvent WHERE kind = ${GroupEvent.KIND} " +
            "AND tags LIKE '%' || :chatRoomId || '%' " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getMarmotGroupNostrEventsByChatRoomId(chatRoomId: HexKey): List<NostrEvent>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE kind in (:kinds) AND pubKey in (:authors)  AND createdAt > :since ORDER BY createdAt ASC LIMIT :limit")
    suspend fun  getAuthoredNostrEvents(
        kinds: Array<Kind>,
        authors: Array<HexKey>,
        since: Instant,
        limit: Int
    ): List<NostrEvent>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE tags LIKE '%' || :publicKey || '%' AND kind in (:kinds)  AND createdAt > :since ORDER BY createdAt ASC LIMIT :limit")
    suspend fun  getPublicKeyMentionedNostrEvents(
        kinds: Array<Kind>,
        publicKey: HexKey,
        since: Instant,
        limit: Int
    ): List<NostrEvent>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE tags LIKE '%' || :eventId || '%reply%' AND kind in (:kinds) AND createdAt > :since ORDER BY createdAt ASC LIMIT :limit")
    suspend fun  getNostrEventReplies(
        kinds: Array<Kind>,
        eventId: HexKey,
        since: Instant,
        limit: Int
    ): List<NostrEvent>

    @Query("SELECT * FROM NostrEvent WHERE kind in (:kinds) ORDER BY savedAt DESC")
    suspend fun  getAllNostrEvents(kinds: Array<Kind>): List<NostrEvent>

    @Transaction
    @Query("SELECT * FROM NostrEvent WHERE id = :id")
    fun observeNostrEventById(id: String): Flow<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent?>

    @Query("SELECT * FROM NostrEvent WHERE id = :id")
    suspend fun getNostrEventById(id: String): NostrEvent?

    @Query("SELECT * FROM NostrEvent WHERE pubKey = :publicKey AND kind = :kind ORDER BY createdAt DESC")
    suspend fun getNostrEventByPublicKeyAndKind(publicKey: HexKey, kind: Kind): NostrEvent?

    @Upsert
    suspend fun upsert(nostrEvent: NostrEvent)

    @Insert(
        onConflict = IGNORE
    )
    suspend fun insert(nostrEvent: NostrEvent)
}