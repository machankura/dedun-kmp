package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain

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

    companion object {
        val NO_OP = object : DepositRepository {
            override suspend fun standing(chatRoomId: String, chain: Chain, canPropose: Boolean): Standing = Standing.CannotSign
            override suspend fun depositors(chatRoomId: String, chain: Chain): Map<HexKey, DepositorStatementEvent.Depositor> = emptyMap()
            override suspend fun canBeOpened(chatRoomId: String, chain: Chain, member: HexKey): Boolean = false
            override suspend fun open(localChatRoom: LocalChatRoom, chain: Chain, member: HexKey): FrostSigningSession =
                throw UnsupportedOperationException("no repository")
        }
    }
}
