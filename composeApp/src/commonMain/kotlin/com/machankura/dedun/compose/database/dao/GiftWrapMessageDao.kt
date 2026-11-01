package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface GiftWrapMessageDao {
    @Query("SELECT * FROM GiftWrapMessage")
    suspend fun  getAllGiftWrapMessages(): List<com.machankura.dedun.compose.database.model.GiftWrapMessage>

    /**
     * Every wrap addressed to [receiverPublicKey] that the device stored and never
     * opened -- no seal names it. Fetched under another identity's key, or under a
     * read-only identity's none; the inbox sweep at activation opens them.
     */
    @Query(
        "SELECT * FROM GiftWrapMessage WHERE receiverPublicKey = :receiverPublicKey " +
            "AND id NOT IN (SELECT giftWrapMessageId FROM GiftWrapSeal WHERE giftWrapMessageId IS NOT NULL) " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getUnopenedAddressedTo(receiverPublicKey: String): List<com.machankura.dedun.compose.database.model.GiftWrapMessage>

    @Upsert
    suspend fun upsert(giftWrapMessage: com.machankura.dedun.compose.database.model.GiftWrapMessage)

}