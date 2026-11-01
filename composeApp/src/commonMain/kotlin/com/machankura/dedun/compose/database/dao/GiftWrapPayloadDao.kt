package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.coroutines.flow.Flow

@Dao
interface GiftWrapPayloadDao {
    @Query("SELECT * FROM GiftWrapPayload")
    suspend fun  getAllGiftWrapUnsigned(): List<GiftWrapPayload>

    /**
     * Everything this key has queued for sealing and not yet sealed, oldest first.
     *
     * A backlog, not a head, for the reason spelled out on
     * `MarmotInnerEventDao.observeUnprocessedMarmotInnerEvents`: nothing takes a row off
     * this queue but a successful seal, so one payload that cannot be sealed used to be
     * the whole queue. That is not hypothetical here -- `sealGiftWrapPayload` refuses a
     * payload belonging to an MLS room outright, and a Welcome whose joiner published no
     * key package produces no wraps at all. Either one returns leaving `giftWrapSealId`
     * null, and every payload behind it waited on a row that would never move.
     *
     * `id` breaks the tie because `createdAt` is stored to the second; there was no
     * ordering here at all before, so which row was "the head" was whatever SQLite
     * happened to return first.
     */
    @Query(
        "SELECT * FROM GiftWrapPayload " +
            "WHERE publicKey = :publicKey AND giftWrapSealId IS NULL " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observeUnsealedGiftWrapPayloads(publicKey: String): Flow<List<GiftWrapPayload>>

    /**
     * Every payload of the given kinds this device holds for a room, oldest first.
     *
     * Lets a handler read back messages it could not act on when they arrived.
     * Payloads are stored before the inbound path dispatches on kind, so one that
     * turned up too early — a ChillDKG round message ahead of the proposal that
     * opens the ritual — is here waiting rather than lost.
     */
    @Query(
        "SELECT * FROM GiftWrapPayload " +
            "WHERE chatRoomId = :chatRoomId AND kind IN (:kinds) " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getByChatRoomAndKinds(chatRoomId: String, kinds: List<Kind>): List<GiftWrapPayload>

    @Upsert
    suspend fun upsert(giftWrapPayload: GiftWrapPayload)

    /**
     * One row by its id. The same job as `MarmotInnerEventDao.getById`, for the
     * other transport: an id is a hash over `created_at` to the second, so a
     * re-publish inside that second collides with the copy it is repeating and
     * an upsert would un-send it.
     */
    @Query("SELECT * FROM GiftWrapPayload WHERE id = :id")
    suspend fun getById(id: String): GiftWrapPayload?

    @Insert
    suspend fun insert(giftWrapPayload: GiftWrapPayload)
}