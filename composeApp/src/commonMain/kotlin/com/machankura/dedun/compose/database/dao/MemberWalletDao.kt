package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.dedun.compose.database.model.MemberWallet
import com.machankura.dedun.compose.database.model.MemberWalletSpend
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MemberWalletDao {
    @Query("SELECT * FROM MemberWallet WHERE ownerPublicKey = :ownerPublicKey ORDER BY createdAt DESC")
    abstract suspend fun getByOwner(ownerPublicKey: String): List<MemberWallet>

    @Query("SELECT * FROM MemberWallet WHERE ownerPublicKey = :ownerPublicKey ORDER BY createdAt DESC")
    abstract fun observeByOwner(ownerPublicKey: String): Flow<List<MemberWallet>>

    @Query("SELECT * FROM MemberWallet WHERE walletId = :walletId")
    abstract suspend fun getById(walletId: String): MemberWallet?

    @Query("SELECT * FROM MemberWallet WHERE guardingChatRoomId = :chatRoomId ORDER BY createdAt DESC")
    abstract suspend fun getByGuardingChatRoomId(chatRoomId: String): List<MemberWallet>

    @Upsert
    abstract suspend fun upsert(wallet: MemberWallet)

    /**
     * Deletes a wallet's key. `MemberWalletManager.discard` is the only caller,
     * and only for a wallet no group has certified: before then no address was
     * ever offered, so no coin can be behind the key.
     */
    @Query("DELETE FROM MemberWallet WHERE walletId = :walletId")
    abstract suspend fun delete(walletId: String)

    @Query("SELECT * FROM MemberWalletSpend WHERE walletId = :walletId ORDER BY createdAt DESC")
    abstract suspend fun getSpends(walletId: String): List<MemberWalletSpend>

    @Query("SELECT * FROM MemberWalletSpend WHERE walletId = :walletId ORDER BY createdAt DESC")
    abstract fun observeSpends(walletId: String): Flow<List<MemberWalletSpend>>

    @Query("SELECT * FROM MemberWalletSpend WHERE txid = :txid")
    abstract suspend fun getSpend(txid: String): MemberWalletSpend?

    @Upsert
    abstract suspend fun upsertSpend(spend: MemberWalletSpend)
}
