package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.KeyShareGeneration
import com.machankura.dedun.compose.database.model.KeyShareMember

/**
 * The generations a refresh has made of a key's shares (docs/frost-share-refresh.md).
 * Generation 0 is not here; see [KeyShareGeneration]. Read through
 * `ShareGenerations`, which puts the two together.
 */
@Dao
interface KeyShareDao {
    @Query("SELECT * FROM KeyShareGeneration WHERE dkgSessionId = :dkgSessionId AND generation = :generation")
    suspend fun getGeneration(dkgSessionId: String, generation: Int): KeyShareGeneration?

    /** The generation the group signs with, by this device's account, or null while that is still 0. */
    @Query("SELECT * FROM KeyShareGeneration WHERE dkgSessionId = :dkgSessionId AND state = 'ACTIVE' ORDER BY generation DESC LIMIT 1")
    suspend fun getActive(dkgSessionId: String): KeyShareGeneration?

    @Query("SELECT * FROM KeyShareGeneration WHERE dkgSessionId = :dkgSessionId ORDER BY generation ASC")
    suspend fun getGenerations(dkgSessionId: String): List<KeyShareGeneration>

    @Query("SELECT * FROM KeyShareMember WHERE dkgSessionId = :dkgSessionId AND generation = :generation ORDER BY participantId ASC")
    suspend fun getMembers(dkgSessionId: String, generation: Int): List<KeyShareMember>

    @Upsert
    suspend fun upsert(generation: KeyShareGeneration)

    @Upsert
    suspend fun upsert(members: List<KeyShareMember>)
}
