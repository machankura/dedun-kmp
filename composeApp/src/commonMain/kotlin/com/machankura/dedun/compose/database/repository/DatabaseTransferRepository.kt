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

class DatabaseTransferRepository(
    private val database: DedunDatabase,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
) : TransferRepository {

    private fun me(): HexKey = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile")

    override fun canAsk(): Boolean = activeIdentity.value?.canSign == true

    override suspend fun givable(chatRoomId: String, chain: Chain, rail: Rail): TransferRepository.Givable {
        val me = activeIdentity.value?.nostrPublicKey ?: return TransferRepository.Givable(0, 0)
        return when (rail) {
            Rail.WAVELENGTH -> GroupWavelengthPayouts.balancesIn(database, chatRoomId, chain)[me.lowercase()]
                ?.let { TransferRepository.Givable(it.netSat, minOf(it.heldSat, it.netSat)) }
                ?: TransferRepository.Givable(0, 0)
            else -> (MemberTransfers.givableSat(database, chatRoomId, chain, rail, me) ?: 0).let { TransferRepository.Givable(it, it) }
        }
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
        return MemberTransfers.request(database, localChatRoom, key, chain, rail, to, amountSat, note).second
    }

    override suspend fun requests(chatRoomId: String): List<MemberTransfers.Asked> = MemberTransfers.requestsIn(database, chatRoomId)

    override suspend fun putToGroup(localChatRoom: LocalChatRoom, request: TransferRequestEvent.Request): FrostSigningSession =
        MemberTransfers.putToGroup(database, localChatRoom, me(), request)

    override suspend fun withdraw(localChatRoom: LocalChatRoom, request: TransferRequestEvent.Request) {
        val key = activeIdentity.value?.nostrPrivateKey ?: throw IllegalStateException("This profile cannot sign")
        MemberTransfers.withdraw(database, localChatRoom, key, request)
    }
}
