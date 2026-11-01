package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.managers.DepositLedger
import com.machankura.dedun.compose.wallet.GroupOnchainWallets

/**
 * Who deposits with a group, for the screens: whether the active profile can be handed an
 * address or an invoice yet, and opening a member's deposits. `docs/member-deposits.md`.
 */
interface DepositRepository {

    /** Where the active profile stands in [chatRoomId] on [chain], as the receive card says it. */
    sealed interface Standing {
        /** A profile that can't sign: it sees tallies, and is handed nothing. */
        data object CannotSign : Standing

        /** No payout destination yet, so nothing can be attributed to them (D1). */
        data object NeedsDestination : Standing

        /** A destination, and the group has not opened their deposits. [canPropose] when this device holds a share. */
        data class CanOpen(val canPropose: Boolean) : Standing

        /** A depositor: their branch is theirs. */
        data class Open(val depositor: DepositorStatementEvent.Depositor) : Standing
    }

    suspend fun standing(chatRoomId: String, chain: Chain, canPropose: Boolean): Standing

    /** Every member the group has opened deposits for, by member. */
    suspend fun depositors(chatRoomId: String, chain: Chain): Map<HexKey, DepositorStatementEvent.Depositor>

    /** Whether [member] has a destination and no depositor statement yet, so a signer could open them. */
    suspend fun canBeOpened(chatRoomId: String, chain: Chain, member: HexKey): Boolean

    /** Proposes opening [member]'s deposits, as the active profile, which has to hold a share. */
    suspend fun open(localChatRoom: LocalChatRoom, chain: Chain, member: HexKey): FrostSigningSession

    /** Files the outputs that paid the room's receive and deposit branches (Phase 8). */
    suspend fun recordOnchain(chatRoomId: String, chain: Chain, deposits: List<GroupOnchainWallets.OnchainDeposit>)

    /** Every member's tally, the unattributed and the total (Phase 8). */
    suspend fun tallies(chatRoomId: String, chain: Chain): DepositLedger.Tallies

    /** Every deposit on record, newest first, with the group's signed attributions applied. */
    suspend fun deposits(chatRoomId: String, chain: Chain): List<DepositRow>

    /** Proposes that the group sign whose each deposit in [assignments] is -- a member, or null for the group's own (Phase 9). */
    suspend fun attribute(localChatRoom: LocalChatRoom, chain: Chain, assignments: Map<String, HexKey?>): FrostSigningSession

    companion object {
        val NO_OP = object : DepositRepository {
            override suspend fun standing(chatRoomId: String, chain: Chain, canPropose: Boolean): Standing = Standing.CannotSign
            override suspend fun depositors(chatRoomId: String, chain: Chain): Map<HexKey, DepositorStatementEvent.Depositor> = emptyMap()
            override suspend fun canBeOpened(chatRoomId: String, chain: Chain, member: HexKey): Boolean = false
            override suspend fun open(localChatRoom: LocalChatRoom, chain: Chain, member: HexKey): FrostSigningSession =
                throw UnsupportedOperationException("no repository")
            override suspend fun recordOnchain(chatRoomId: String, chain: Chain, deposits: List<GroupOnchainWallets.OnchainDeposit>) = Unit
            override suspend fun tallies(chatRoomId: String, chain: Chain) = DepositLedger.Tallies(emptyMap(), 0, 0)
            override suspend fun deposits(chatRoomId: String, chain: Chain): List<DepositRow> = emptyList()
            override suspend fun attribute(localChatRoom: LocalChatRoom, chain: Chain, assignments: Map<String, HexKey?>): FrostSigningSession =
                throw UnsupportedOperationException("no repository")
        }
    }
}
