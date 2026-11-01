package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.MemberWallet
import com.machankura.dedun.compose.managers.GuardianManager
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.machankura.dedun.compose.database.model.MemberWalletSpend
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.psbt.Psbt
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DatabaseMemberWalletRepository(
    private val database: DedunDatabase,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
) : MemberWalletRepository {
    private val logger = Logger.withTag(TAG)

    override suspend fun groupsOf(ownerPublicKey: HexKey): List<LocalChatRoom> = try {
        MemberWalletManager.groupsOf(database, ownerPublicKey)
    } catch (e: Throwable) {
        logger.e("Error reading the groups that could guard a wallet", e)
        emptyList()
    }

    override suspend fun refusal(chatRoomId: String, chain: Chain): MemberWalletManager.Refusal? = try {
        MemberWalletManager.refusal(database, chatRoomId, canSign = activeIdentity.value?.canSign == true, chain = chain)
    } catch (e: Throwable) {
        logger.e("Error reading whether $chatRoomId can guard a wallet", e)
        MemberWalletManager.Refusal.KEY_UNKNOWN
    }

    override suspend fun reachOf(chatRoomId: String): Int? = try {
        MemberWalletManager.reachOf(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading how far $chatRoomId's guard reaches", e)
        null
    }

    override suspend fun delaysOf(chatRoomId: String): List<Long>? = try {
        MemberWalletManager.delaysOf(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading how far $chatRoomId's guard reaches", e)
        null
    }

    override suspend fun askForGroupKey(chatRoomId: String): Boolean {
        val identity = activeIdentity.value ?: return false
        return MemberWalletManager.askForGroupKey(database, chatRoomId, identity.nostrPublicKey)
    }

    override suspend fun request(chatRoomId: String, chain: Chain, label: String?): MemberWallet {
        val key = activeIdentity.value?.nostrPrivateKey ?: throw IllegalStateException("This profile cannot sign")
        return MemberWalletManager.request(database, chatRoomId, key, chain, label)
    }

    override suspend fun walletsOf(ownerPublicKey: HexKey): List<MemberWalletManager.MemberWalletRow> = try {
        MemberWalletManager.walletsOf(database, ownerPublicKey)
    } catch (e: Throwable) {
        // A profile that lists no wallets is wrong and survivable; one that will
        // not open because reading them threw is neither.
        logger.e("Error reading $ownerPublicKey's guarded wallets", e)
        emptyList()
    }

    override suspend fun walletOf(ownerPublicKey: HexKey, walletId: String): MemberWalletManager.MemberWalletRow? = try {
        MemberWalletManager.walletOf(database, ownerPublicKey, walletId)
    } catch (e: Throwable) {
        logger.e("Error reading guarded wallet $walletId", e)
        null
    }

    override suspend fun askAgain(walletId: String): String {
        val wallet = database.memberWalletDao().getById(walletId) ?: throw IllegalStateException("No key for $walletId here")
        check(wallet.ownerPublicKey == activeIdentity.value?.nostrPublicKey) { "Not this profile's wallet" }
        val key = activeIdentity.value?.nostrPrivateKey ?: throw IllegalStateException("This profile cannot sign")
        return MemberWalletManager.askAgain(database, wallet, key).walletId
    }

    override suspend fun discard(walletId: String) {
        val wallet = database.memberWalletDao().getById(walletId) ?: return
        check(wallet.ownerPublicKey == activeIdentity.value?.nostrPublicKey) { "Not this profile's wallet" }
        MemberWalletManager.discard(database, walletId)
    }

    override suspend fun exportKey(walletId: String): Pair<String, String>? {
        val owner = activeIdentity.value?.nostrPublicKey ?: return null
        return MemberWalletManager.exportKey(database, owner, walletId)
    }

    override suspend fun roomName(chatRoomId: String): String? =
        database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.subject?.takeIf { it.isNotBlank() }

    override suspend fun signSpend(walletId: String, psbt: Psbt): Transaction {
        val owner = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile is active")
        return MemberWalletManager.signSpend(database, owner, walletId, psbt)
    }

    override suspend fun recordChain(txid: String, confirmations: Int?, broadcastAt: Instant?) =
        MemberWalletManager.recordChain(database, txid, confirmations, broadcastAt)

    override fun observeSpends(walletId: String): Flow<List<MemberWalletSpend>> = database.memberWalletDao().observeSpends(walletId)

    override suspend fun couldGuard(chatRoomId: String): Boolean = try {
        database.chatRoomDao().findChatRoomById(chatRoomId)?.let { MemberWalletManager.couldGuard(database, it) } == true
    } catch (e: Throwable) {
        logger.e("Error reading whether $chatRoomId could guard a wallet", e)
        false
    }

    override suspend fun policyOf(chatRoomId: String): MemberWalletPolicyEvent.Policy? = try {
        MemberWalletManager.policyFor(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading $chatRoomId's member-wallet policy", e)
        null
    }

    override suspend fun memberWalletsOf(chatRoomId: String): List<MemberWalletManager.GroupMemberWallet> = try {
        MemberWalletManager.memberWalletsOf(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading the member wallets $chatRoomId guards", e)
        emptyList()
    }

    override suspend fun putToGroup(chatRoomId: String, request: Event): FrostSigningSession {
        val identity = activeIdentity.value ?: throw IllegalStateException("No profile is active")
        val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: throw IllegalStateException("No such group here")
        return MemberWalletManager.putToGroup(database, room, identity.nostrPublicKey, request)
    }

    override suspend fun guardedByGroupsHere(ownerPublicKey: HexKey): List<MemberWalletManager.GuardedHere> = try {
        val viewer = activeIdentity.value?.nostrPublicKey ?: return emptyList()
        MemberWalletManager.guardedByGroupsHere(database, viewer, ownerPublicKey)
    } catch (e: Throwable) {
        logger.e("Error reading the wallets of $ownerPublicKey this profile's groups guard", e)
        emptyList()
    }

    companion object {
        private const val TAG = "DatabaseMemberWalletRepository"
    }
}
