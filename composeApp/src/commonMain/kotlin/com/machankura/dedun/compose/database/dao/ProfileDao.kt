package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.Profile
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

@Dao
interface ProfileDao {
    @Query("SELECT * FROM Profile WHERE createdAt > :createdAt LIMIT :limit")
    suspend fun  getAllProfiles(limit: Int = 21, createdAt: Instant = GENESIS_AT): List<Profile>

    @Query("SELECT * FROM Profile WHERE publicKey NOT IN (:excludedPublicKeys) AND createdAt > :createdAt LIMIT :limit")
    suspend fun  getAllProfilesExcludingOnesWithThesePublicKeys(
        excludedPublicKeys: Array<HexKey>,
        limit: Int = 21, createdAt: Instant = GENESIS_AT
    ): List<Profile>


    @Query("SELECT * FROM NostrEvent WHERE kind = 0 AND content LIKE '%' || :searchTerm || '%' LIMIT :limit")
    suspend fun  filterProfiles(searchTerm: String, limit: Int = 21): List<com.machankura.dedun.compose.database.model.intermdiate.LocalProfile>

    @Query("SELECT * FROM Profile WHERE publicKey = :publicKey")
    suspend fun getProfileByPublicKey(publicKey: String): Profile?

    @Query("SELECT * FROM Profile WHERE publicKey = :publicKey")
    fun observeProfileByPublicKey(publicKey: String): Flow<Profile?>

    @Query("SELECT * FROM Profile WHERE publicKey IN (:publicKeys)")
    suspend fun getProfileByPublicKeys(publicKeys: List<String>): List<Profile>

    @Query("SELECT * FROM NostrEvent WHERE kind = 0 AND pubKey = :publicKey")
    fun observeProfileWithFollowersByPublicKey(publicKey: String): Flow<com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowers?>

    @Query("SELECT * FROM NostrEvent WHERE kind = 0 AND pubKey = :publicKey")
    fun observeProfileWithFollowingByPublicKey(publicKey: String): Flow<com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowing?>

    @Insert
    suspend fun insertPlaceholderProfile(profile: Profile)

    @Upsert
    suspend fun upsert(profile: Profile)

}