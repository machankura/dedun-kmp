package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.GroupArkFloor
import com.machankura.dedun.compose.database.model.GroupArkMark
import com.machankura.dedun.compose.database.model.GroupArkTreeShare
import com.machankura.dedun.compose.database.model.GroupArkVtxo

@Dao
interface GroupArkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTreeShare(row: GroupArkTreeShare)

    @Query("SELECT * FROM GroupArkTreeShare WHERE wallet = :wallet")
    suspend fun treeShares(wallet: String): List<GroupArkTreeShare>

    @Query("SELECT MAX(child) FROM GroupArkTreeShare WHERE wallet = :wallet AND participant = :participant")
    suspend fun highestTreeShare(wallet: String, participant: String): Long?

    @Upsert
    suspend fun upsertVtxo(row: GroupArkVtxo)

    @Query("SELECT * FROM GroupArkVtxo WHERE wallet = :wallet")
    suspend fun vtxos(wallet: String): List<GroupArkVtxo>

    @Query("SELECT * FROM GroupArkFloor WHERE wallet = :wallet")
    suspend fun floor(wallet: String): GroupArkFloor?

    @Upsert
    suspend fun upsertFloor(row: GroupArkFloor)

    @Upsert
    suspend fun upsertMark(row: GroupArkMark)

    @Query("SELECT * FROM GroupArkMark WHERE wallet = :wallet")
    suspend fun marks(wallet: String): List<GroupArkMark>
}
