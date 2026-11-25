package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.Contacts
import com.machankura.dedun.compose.managers.DepositLedger
import com.machankura.dedun.compose.nostr.wallet.PayoutDestination
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/** A contact's page: `docs/non-member-deposits.md`, Phase 4. */
sealed interface ContactUIState {
    data object Loading : ContactUIState

    data class Error(val message: String) : ContactUIState

    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val contact: Contacts.Contact,
        /** What they have put in and been paid, on this network; empty while nothing has. */
        val tally: DepositLedger.Tally?,
        /** Every deposit the group counts to them, newest first. */
        val deposits: List<DepositRow>,
        /** Where the group pays them on this network, or null while it has said nowhere (D9). */
        val destination: PayoutDestination?,
        /** Each member's name, by public key. */
        val names: Map<HexKey, String>,
        /** The current members, for saying which of them the contact was (D8). */
        val members: List<HexKey>,
        /** Whether the group has opened deposits for them on this network: a branch of their own (D10). */
        val depositsOpen: Boolean = false,
    ) : ContactUIState
}
