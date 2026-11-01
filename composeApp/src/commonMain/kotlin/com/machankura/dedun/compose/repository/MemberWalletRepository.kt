package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.MemberWallet
import com.machankura.dedun.compose.managers.GuardianManager
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.machankura.dedun.compose.database.model.MemberWalletSpend
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.psbt.Psbt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain

/**
 * A member's guarded wallets, for the screens: asking a group to guard one, and
 * -- from the group's side -- the requests and certificates in a room.
 * `docs/member-guarded-wallets.md`. The key-holding identity is the
 * repository's, never the screen's: nothing above this layer touches a key.
 */
interface MemberWalletRepository {
    /** The groups [ownerPublicKey] is in that could guard a wallet: Marmot rooms, whether or not their key is known yet. */
    suspend fun groupsOf(ownerPublicKey: HexKey): List<LocalChatRoom>

    /** Why [chatRoomId] cannot guard a wallet for the active profile on [chain], or null when it can. */
    suspend fun refusal(chatRoomId: String, chain: Chain): MemberWalletManager.Refusal?

    /** How many groups a wallet guarded by [chatRoomId] would be guarded by, or null while its key is unknown. */
    suspend fun reachOf(chatRoomId: String): Int?

    /** Each guardian's delay for a wallet [chatRoomId] would guard, nearest first, or null while its key is unknown. */
    suspend fun delaysOf(chatRoomId: String): List<Long>?

    /** Asks [chatRoomId] what it signs with, for a member who holds no key state. Whether it asked. */
    suspend fun askForGroupKey(chatRoomId: String): Boolean

    /** Makes the key, works out the leaves, stores the key, and asks [chatRoomId] to guard it. */
    suspend fun request(chatRoomId: String, chain: Chain, label: String?): MemberWallet

    /** Every guarded wallet of [ownerPublicKey]'s this device knows about, newest first. */
    suspend fun walletsOf(ownerPublicKey: HexKey): List<MemberWalletManager.MemberWalletRow>

    /** One of them, or null. */
    suspend fun walletOf(ownerPublicKey: HexKey, walletId: String): MemberWalletManager.MemberWalletRow?

    /** Asks the group again, with the request the wallet was first asked with. */
    /** Asks again for [walletId]; returns the wallet now asked for, a new one when the old request would be refused. */
    suspend fun askAgain(walletId: String): String

    /** Forgets a wallet's key -- refused once a group guards it. */
    suspend fun discard(walletId: String)

    /** The wallet's receive and change descriptors with its private key in them, or null when the key is not here. */
    suspend fun exportKey(walletId: String): Pair<String, String>?

    /** What [chatRoomId] is called on this device, or null when it has no room for it. */
    suspend fun roomName(chatRoomId: String): String?

    /** Signs [psbt] as [walletId]'s own key, files the spend, and returns the transaction to broadcast. */
    suspend fun signSpend(walletId: String, psbt: Psbt): Transaction

    /** Files what the chain said of a member's spend. */
    suspend fun recordChain(txid: String, confirmations: Int?, broadcastAt: Instant? = null)

    /** The spends the member signed from [walletId], newest first. */
    fun observeSpends(walletId: String): Flow<List<MemberWalletSpend>>

    /** The member wallets [chatRoomId] guards, and the requests waiting for a signer to put them to it. */
    suspend fun memberWalletsOf(chatRoomId: String): List<MemberWalletManager.GroupMemberWallet>

    /** [chatRoomId]'s newest member-wallet policy this device holds, or null. See `MemberWalletManager.policyFor`. */
    suspend fun policyOf(chatRoomId: String): MemberWalletPolicyEvent.Policy?

    /** Whether [chatRoomId] is a group that could guard a member's wallet -- not a direct chat. See `MemberWalletManager.couldGuard`. */
    suspend fun couldGuard(chatRoomId: String): Boolean

    /** Puts a member's request to [chatRoomId]'s quorum as the active profile, which counts as its approval. */
    suspend fun putToGroup(chatRoomId: String, request: Event): FrostSigningSession

    /** Every wallet of [ownerPublicKey]'s a group the active profile is in guards. */
    suspend fun guardedByGroupsHere(ownerPublicKey: HexKey): List<MemberWalletManager.GuardedHere>

    companion object {
        val NO_OP = object : MemberWalletRepository {
            override suspend fun groupsOf(ownerPublicKey: HexKey): List<LocalChatRoom> = emptyList()
            override suspend fun refusal(chatRoomId: String, chain: Chain): MemberWalletManager.Refusal? = MemberWalletManager.Refusal.CANNOT_SIGN
            override suspend fun reachOf(chatRoomId: String): Int? = null

            override suspend fun delaysOf(chatRoomId: String): List<Long>? = null
            override suspend fun askForGroupKey(chatRoomId: String): Boolean = false
            override suspend fun request(chatRoomId: String, chain: Chain, label: String?): MemberWallet =
                throw UnsupportedOperationException("no repository")
            override suspend fun walletsOf(ownerPublicKey: HexKey): List<MemberWalletManager.MemberWalletRow> = emptyList()
            override suspend fun walletOf(ownerPublicKey: HexKey, walletId: String): MemberWalletManager.MemberWalletRow? = null
            override suspend fun askAgain(walletId: String): String = walletId
            override suspend fun discard(walletId: String) = Unit
            override suspend fun exportKey(walletId: String): Pair<String, String>? = null
            override suspend fun roomName(chatRoomId: String): String? = null
            override suspend fun signSpend(walletId: String, psbt: Psbt): Transaction = throw UnsupportedOperationException("no repository")
            override suspend fun recordChain(txid: String, confirmations: Int?, broadcastAt: Instant?) = Unit
            override fun observeSpends(walletId: String): Flow<List<MemberWalletSpend>> = flowOf(emptyList())
            override suspend fun memberWalletsOf(chatRoomId: String): List<MemberWalletManager.GroupMemberWallet> = emptyList()

            override suspend fun policyOf(chatRoomId: String): MemberWalletPolicyEvent.Policy? = null

            override suspend fun couldGuard(chatRoomId: String): Boolean = false
            override suspend fun putToGroup(chatRoomId: String, request: Event): FrostSigningSession = throw UnsupportedOperationException("no repository")
            override suspend fun guardedByGroupsHere(ownerPublicKey: HexKey): List<MemberWalletManager.GuardedHere> = emptyList()
        }
    }
}
