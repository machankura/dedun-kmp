package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface NostrEventRelayDao {

    @Upsert
    suspend fun upsert(nostrEventRelay: com.machankura.dedun.compose.database.model.NostrEventRelay)

    /** The relays an event was seen on, oldest first. */
    @Query("SELECT relayURL FROM NostrEventRelay WHERE nostrEventId = :nostrEventId ORDER BY createdAt ASC")
    suspend fun getRelayUrls(nostrEventId: String): List<String>

}