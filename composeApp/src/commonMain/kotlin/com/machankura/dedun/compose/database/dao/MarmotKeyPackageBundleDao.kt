package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.MarmotKeyPackageBundle
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow

@Dao
interface MarmotKeyPackageBundleDao {
    /**
     * Every bundle still publishable for [publicKey], any profile. A list rather than
     * the first row, because there is one active bundle per profile and the notary
     * republishes whichever profile has none.
     */
    @Query("SELECT * FROM MarmotKeyPackageBundle WHERE publicKey = :publicKey AND consumed = false AND rotated = false ORDER BY createdAt DESC")
    fun observeActiveMarmotKeyPackageBundles(publicKey: String): Flow<List<MarmotKeyPackageBundle>>

    @Query("SELECT * FROM MarmotKeyPackageBundle WHERE publicKey = :publicKey ORDER BY createdAt DESC")
    fun observeAllMarmotKeyPackageBundles(publicKey: HexKey): Flow<List<MarmotKeyPackageBundle>>

    @Query("SELECT * FROM MarmotKeyPackageBundle WHERE publicKey = :publicKey ORDER BY createdAt DESC")
    suspend fun getAllMarmotKeyPackageBundles(publicKey: HexKey): List<MarmotKeyPackageBundle>

    @Query("SELECT * FROM MarmotKeyPackageBundle WHERE id = :id ORDER BY createdAt DESC")
    suspend fun getMarmotKeyPackageBundleById(id: String): MarmotKeyPackageBundle?

    @Upsert
    suspend fun upsert(marmotKeyPackageBundle: MarmotKeyPackageBundle)
}