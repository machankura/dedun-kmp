package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.CoinAssignments
import com.machankura.dedun.compose.managers.DepositLedger
import com.machankura.dedun.compose.managers.Depositors
import com.machankura.dedun.compose.managers.MemberBalances
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.repository.PayoutRepository
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DatabasePayoutRepository(
    private val database: DedunDatabase,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
) : PayoutRepository {

    override suspend fun balances(chatRoomId: String, chain: Chain, state: GroupWalletState?): MemberBalances.Balances =
        MemberBalances.balancesIn(database, chatRoomId, chain, state)

    override suspend fun suggestedShares(chatRoomId: String, chain: Chain, amount: Long): Map<HexKey, Long> {
        val depositors = Depositors.statementsIn(database, chatRoomId, chain).keys
        val net = DepositLedger.tallies(database, chatRoomId, chain).members
            .filterKeys { it in depositors }
            .mapValues { (_, tally) -> tally.depositedMsat - tally.paidOutMsat }
        return CoinAssignments.suggest(amount, net)
    }

    override suspend fun depositors(chatRoomId: String, chain: Chain): List<HexKey> =
        Depositors.statementsIn(database, chatRoomId, chain).keys.sorted()

    override suspend fun assign(localChatRoom: LocalChatRoom, chain: Chain, coins: List<CoinAssignmentEvent.Coin>): FrostSigningSession {
        val me = activeIdentity.value?.nostrPublicKey ?: throw IllegalStateException("No profile")
        return CoinAssignments.propose(database, localChatRoom, me, chain, coins)
    }
}
