package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.Depositors
import com.machankura.dedun.compose.managers.GroupWavelengthPayouts
import com.machankura.dedun.compose.managers.MemberTransfers
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.machankura.dedun.compose.repository.TransferRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.machankura.dedun.compose.managers.GroupWalletManager
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import kotlin.time.Duration.Companion.seconds

class DatabaseTransferRepository(
    private val database: DedunDatabase,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
    /** The identity's group wallets, which come and go with its Electrum client: what says a coin is still unspent. */
    private val wallets: () -> GroupOnchainWallets? = { null },
) : TransferRepository {

    private fun me(): HexKey = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile")

    override fun canAsk(): Boolean = activeIdentity.value?.canSign == true

    override suspend fun givable(chatRoomId: String, chain: Chain, rail: Rail): TransferRepository.Givable {
        val me = activeIdentity.value?.nostrPublicKey ?: return TransferRepository.Givable(0, 0)
        return when (rail) {
            Rail.WAVELENGTH -> GroupWavelengthPayouts.balancesIn(database, chatRoomId, chain)[me.lowercase()]
                ?.let { TransferRepository.Givable(it.netSat, minOf(it.heldSat, it.netSat)) }
                ?: TransferRepository.Givable(0, 0)
            Rail.ONCHAIN -> {
                val unspent = unspentCoins(chatRoomId, chain) ?: return TransferRepository.Givable(0, 0)
                val coins = MemberTransfers.ownedCoins(database, chatRoomId, chain, me).filterKeys { it in unspent }
                coins.values.sum().let { TransferRepository.Givable(it, it, coins) }
            }
            else -> (MemberTransfers.givableSat(database, chatRoomId, chain, rail, me) ?: 0).let { TransferRepository.Givable(it, it) }
        }
    }

    /**
     * The coins the group's onchain wallet on [chain] holds unspent, by what Electrum last said, or null with no wallet to ask:
     * a coin this device knows from its ledger may have been spent since, and transferring it would move nothing.
     */
    private suspend fun unspentCoins(chatRoomId: String, chain: Chain): Set<DepositKey.Onchain>? {
        val wallets = wallets() ?: return null
        val wallet = GroupWalletManager.walletFor(database, chatRoomId, chain) ?: return null
        val state = withTimeoutOrNull(WALLET_WAIT) { wallets.walletFor(wallet.account, chatRoomId).state.first() } ?: return null
        return state.utxos.map { DepositKey.Onchain(it.utxo.outPoint.txid.toString().lowercase(), it.utxo.outPoint.index) }.toSet()
    }

    override suspend fun receivers(chatRoomId: String, chain: Chain): List<TransferRepository.Receiver> {
        val me = activeIdentity.value?.nostrPublicKey?.lowercase()
        val depositors = Depositors.statementsIn(database, chatRoomId, chain).keys.map { it.lowercase() }.toSet()
        return database.participantDao().findParticipantsByChatRoomId(chatRoomId)
            .map { it.participantPublicKey.lowercase() }
            .filter { it != me }
            .distinct()
            .map { TransferRepository.Receiver(it, it in depositors) }
    }

    override suspend fun request(localChatRoom: LocalChatRoom, chain: Chain, rail: Rail, to: HexKey, amountSat: Long, note: String?): FrostSigningSession? {
        val key = activeIdentity.value?.nostrPrivateKey ?: throw IllegalStateException("This profile cannot sign")
        // Onchain, the coins come from what the wallet still holds, as the form showed them.
        val coins = if (rail == Rail.ONCHAIN) {
            givable(localChatRoom.chatRoom.id, chain, rail).coinsFor(amountSat) ?: throw IllegalArgumentException("No choice of your coins makes $amountSat sats")
        } else null
        return MemberTransfers.request(database, localChatRoom, key, chain, rail, to, amountSat, note, coins = coins).second
    }

    override suspend fun requests(chatRoomId: String): List<MemberTransfers.Asked> = MemberTransfers.requestsIn(database, chatRoomId)

    override suspend fun putToGroup(localChatRoom: LocalChatRoom, request: TransferRequestEvent.Request): FrostSigningSession =
        MemberTransfers.putToGroup(database, localChatRoom, me(), request)

    override suspend fun withdraw(localChatRoom: LocalChatRoom, request: TransferRequestEvent.Request) {
        val key = activeIdentity.value?.nostrPrivateKey ?: throw IllegalStateException("This profile cannot sign")
        MemberTransfers.withdraw(database, localChatRoom, key, request)
    }

    companion object {
        private val WALLET_WAIT = 5.seconds
    }
}
