package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What a group's Wavelength wallet screens read and propose (`docs/group-wavelength-ui.md`). Nothing here moves money: a proposal is signed by the group. */
interface GroupWavelengthRepository {
    suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean

    /** The sizes of key the group could make, which is empty for a group too small. */
    suspend fun ceremonyThresholds(chatRoomId: String): List<Int>

    /** The room's ceremonies for this wallet's key, newest first. */
    fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>>

    suspend fun unusedKey(chatRoomId: String): IcebergKeySession?

    /** Every wallet this device believes on [chain], lowest number first. */
    suspend fun wallets(chatRoomId: String, chain: Chain): List<GroupWavelengthWalletManager.Wallet>

    /** Why the room's newest statement on [chain] isn't believed, or null if it is or there is none. */
    suspend fun refusal(chatRoomId: String, chain: Chain): GroupWavelengthWalletManager.Refusal?

    suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long): GroupWavelengthOperatorManager.Operator?

    /** The terms this build would propose a wallet under, or null where there is no public operator. */
    fun terms(chain: Chain): GroupWavelengthTerms.Terms?

    /** What the operator says now, or null if it can't be asked. A proposer's check, and nothing a device signs depends on it. */
    suspend fun liveTerms(chain: Chain): GroupWavelengthTerms.Terms?

    suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession

    suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession

    suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession

    /** What this device has itself signed for the wallet: its own ledger, never the operator's word. */
    fun observeCoins(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkVtxo>>

    companion object {
        val NO_OP: GroupWavelengthRepository = object : GroupWavelengthRepository {
            override suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean = false
            override suspend fun ceremonyThresholds(chatRoomId: String): List<Int> = emptyList()
            override fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>> = flowOf(emptyList())
            override suspend fun unusedKey(chatRoomId: String): IcebergKeySession? = null
            override suspend fun wallets(chatRoomId: String, chain: Chain): List<GroupWavelengthWalletManager.Wallet> = emptyList()
            override suspend fun refusal(chatRoomId: String, chain: Chain): GroupWavelengthWalletManager.Refusal? = null
            override suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long): GroupWavelengthOperatorManager.Operator? = null
            override fun terms(chain: Chain): GroupWavelengthTerms.Terms? = null
            override suspend fun liveTerms(chain: Chain): GroupWavelengthTerms.Terms? = null
            override suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession = error("No room to propose in")
            override suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession = error("No room to propose in")
            override suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession = error("No room to propose in")
            override fun observeCoins(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkVtxo>> = flowOf(emptyList())
        }
    }
}
