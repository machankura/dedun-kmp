package com.machankura.dedun.compose.database.repository

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.Depositors
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.repository.DepositRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.managers.DepositLedger
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DatabaseDepositRepository(
    private val database: DedunDatabase,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
) : DepositRepository {
    private val logger = Logger.withTag(TAG)

    override suspend fun standing(chatRoomId: String, chain: Chain, canPropose: Boolean): DepositRepository.Standing = try {
        val identity = activeIdentity.value
        val me = identity?.nostrPublicKey
        when {
            identity == null || me == null || !identity.canSign -> DepositRepository.Standing.CannotSign
            else -> Depositors.statementOf(database, chatRoomId, chain, me)?.let { DepositRepository.Standing.Open(it) }
                ?: when (Depositors.refusal(database, chatRoomId, chain, me)) {
                    Depositors.Refusal.NO_DESTINATION, Depositors.Refusal.NOT_A_MEMBER -> DepositRepository.Standing.NeedsDestination
                    else -> DepositRepository.Standing.CanOpen(canPropose)
                }
        }
    } catch (e: Throwable) {
        logger.e("Error reading whether $chatRoomId takes this profile's deposits", e)
        DepositRepository.Standing.CannotSign
    }

    override suspend fun depositors(chatRoomId: String, chain: Chain): Map<HexKey, DepositorStatementEvent.Depositor> = try {
        Depositors.statementsIn(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error reading $chatRoomId's depositors", e)
        emptyMap()
    }

    override suspend fun canBeOpened(chatRoomId: String, chain: Chain, member: HexKey): Boolean =
        runCatching { Depositors.refusal(database, chatRoomId, chain, member) == null }.getOrDefault(false)

    override suspend fun open(localChatRoom: LocalChatRoom, chain: Chain, member: HexKey): FrostSigningSession {
        val me = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile")
        return Depositors.propose(database, localChatRoom, me, member, chain)
    }

    override suspend fun recordOnchain(chatRoomId: String, chain: Chain, deposits: List<GroupOnchainWallets.OnchainDeposit>) {
        runCatching { DepositLedger.recordOnchain(database, chatRoomId, chain, deposits) }
            .onFailure { logger.e("Error recording $chatRoomId's deposits", it) }
    }

    override suspend fun tallies(chatRoomId: String, chain: Chain): DepositLedger.Tallies = try {
        DepositLedger.tallies(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error reading $chatRoomId's tallies", e)
        DepositLedger.Tallies(emptyMap(), 0, 0)
    }

    override suspend fun deposits(chatRoomId: String, chain: Chain): List<DepositRow> = try {
        DepositLedger.depositsIn(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error reading $chatRoomId's deposits", e)
        emptyList()
    }

    override suspend fun attribute(localChatRoom: LocalChatRoom, chain: Chain, assignments: Map<String, HexKey?>): FrostSigningSession {
        val me = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile")
        return DepositLedger.proposeAttribution(database, localChatRoom, me, chain, assignments)
    }

    companion object {
        private const val TAG = "DatabaseDepositRepository"
    }
}
