package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.MemberBalances
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain

/**
 * Paying members out of the group's wallet, for the screens: who owns what, and asking the group
 * to say whose the rest is. `docs/payouts-only.md`. The key is the repository's, never the screen's.
 */
interface PayoutRepository {

    /** Every member's balance in [chatRoomId] on [chain] over [state], and the coins nobody owns. */
    suspend fun balances(chatRoomId: String, chain: Chain, state: GroupWalletState?): MemberBalances.Balances

    /** The shares a coin of [amount] sats is suggested in: pro rata to what each depositor has in (D3). */
    suspend fun suggestedShares(chatRoomId: String, chain: Chain, amount: Long): Map<HexKey, Long>

    /** Everybody the group has opened deposits for in [chatRoomId] on [chain]: whom a coin can be assigned to. */
    suspend fun depositors(chatRoomId: String, chain: Chain): List<HexKey>

    /** Proposes that the group sign whose [coins] are, as the active profile, which has to hold a share. */
    suspend fun assign(localChatRoom: LocalChatRoom, chain: Chain, coins: List<CoinAssignmentEvent.Coin>): FrostSigningSession

    companion object {
        val NO_OP = object : PayoutRepository {
            override suspend fun balances(chatRoomId: String, chain: Chain, state: GroupWalletState?) = MemberBalances.Balances(emptyMap(), emptyList())
            override suspend fun suggestedShares(chatRoomId: String, chain: Chain, amount: Long): Map<HexKey, Long> = emptyMap()
            override suspend fun depositors(chatRoomId: String, chain: Chain): List<HexKey> = emptyList()
            override suspend fun assign(localChatRoom: LocalChatRoom, chain: Chain, coins: List<CoinAssignmentEvent.Coin>): FrostSigningSession =
                throw UnsupportedOperationException("no repository")
        }
    }
}
