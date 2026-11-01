package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface GiftWrapSealDao {
    @Query("SELECT * FROM GiftWrapSeal")
    suspend fun  getAllGiftWrapSeals(): List<com.machankura.dedun.compose.database.model.GiftWrapSeal>

    @Query("SELECT COUNT(*) FROM GiftWrapSeal WHERE giftWrapMessageId = :giftWrapMessageId")
    suspend fun countForMessage(giftWrapMessageId: String): Int

    @Upsert
    suspend fun upsert(giftWrapSeal: com.machankura.dedun.compose.database.model.GiftWrapSeal)

}