package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.GroupArkChecked
import com.machankura.dedun.compose.database.model.GroupArkFloor
import com.machankura.dedun.compose.database.model.GroupArkMark
import com.machankura.dedun.compose.database.model.GroupArkRound
import com.machankura.dedun.compose.database.model.GroupArkTreeBinding
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

    @Query("UPDATE GroupArkVtxo SET settled = 1 WHERE wallet = :wallet AND origin = :origin")
    suspend fun settle(wallet: String, origin: String)

    @Query("UPDATE GroupArkVtxo SET spent = 1 WHERE wallet = :wallet AND reservedBy = :by")
    suspend fun spendReservedBy(wallet: String, by: String)

    @Upsert
    suspend fun upsertRound(row: GroupArkRound)

    @Query("SELECT * FROM GroupArkRound WHERE wallet = :wallet")
    suspend fun rounds(wallet: String): List<GroupArkRound>

    @Upsert
    suspend fun upsertTreeBinding(row: GroupArkTreeBinding)

    @Query("SELECT * FROM GroupArkTreeBinding WHERE wallet = :wallet")
    suspend fun treeBindings(wallet: String): List<GroupArkTreeBinding>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChecked(row: GroupArkChecked)

    @Query("SELECT * FROM GroupArkChecked WHERE wallet = :wallet AND label = :label")
    suspend fun checked(wallet: String, label: String): GroupArkChecked?

    @Query("SELECT MAX(counter) FROM GroupArkChecked WHERE wallet = :wallet AND child IS NULL")
    suspend fun highestPlainChecked(wallet: String): Long?

    @Query("SELECT MAX(child) FROM GroupArkChecked WHERE wallet = :wallet AND child IS NOT NULL")
    suspend fun highestChildChecked(wallet: String): Long?

    @Query("SELECT * FROM GroupArkFloor WHERE wallet = :wallet")
    suspend fun floor(wallet: String): GroupArkFloor?

    @Upsert
    suspend fun upsertFloor(row: GroupArkFloor)

    @Upsert
    suspend fun upsertMark(row: GroupArkMark)

    @Query("SELECT * FROM GroupArkMark WHERE wallet = :wallet")
    suspend fun marks(wallet: String): List<GroupArkMark>
}
