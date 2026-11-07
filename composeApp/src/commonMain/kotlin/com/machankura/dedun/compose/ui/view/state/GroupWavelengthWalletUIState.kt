package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthLedger
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * The group's Wavelength wallet screen, through every state a group passes on its way to one (`docs/group-wavelength-ui.md`): no
 * onchain wallet to leave to, no key, a key but no statement, a statement this device can't yet or won't stand behind, and a wallet.
 */
sealed class GroupWavelengthWalletUIState {
    data object Loading : GroupWavelengthWalletUIState()

    data class Error(val message: String) : GroupWavelengthWalletUIState()

    /** The onchain wallet on this network isn't open yet: money leaving this wallet goes there. */
    data class NeedsOnchainWallet(val localChatRoom: LocalChatRoom) : GroupWavelengthWalletUIState()

    /** No completed key; [ceremony] is one in progress, if any. [canMakeKey] is false for a group too small. */
    data class NeedsKey(val localChatRoom: LocalChatRoom, val ceremony: IcebergKeySession?, val canMakeKey: Boolean) : GroupWavelengthWalletUIState()

    /** A key, and no statement the group signed. */
    data class NotOpened(
        val localChatRoom: LocalChatRoom,
        val key: IcebergKeySession,
        /** The signing session already asking for this, which the button then leads to rather than starting another. */
        val openingSession: String?,
        /** What stops this device proposing, or null when it can. */
        val blocked: Blocked?,
    ) : GroupWavelengthWalletUIState()

    enum class Blocked {
        /** No nostr key to send the seed with. Says nothing: the group's other members can propose. */
        NO_KEY,
        NO_OPERATOR_KNOWN,
        TERMS_CHANGED,
    }

    /** The group opened a wallet, and the seed the opener sent hasn't reached this device. */
    data class WaitingForSeed(val localChatRoom: LocalChatRoom) : GroupWavelengthWalletUIState()

    /** The group opened a wallet this device won't stand behind. There is nothing to offer: that would defeat the check. */
    data class Refused(val localChatRoom: LocalChatRoom, val why: GroupWavelengthWalletManager.Refusal) : GroupWavelengthWalletUIState()

    data class Opened(
        val localChatRoom: LocalChatRoom,
        val wallet: GroupWavelengthWalletManager.Wallet,
        val operator: GroupWavelengthOperatorManager.Operator?,
        /** Whether this device is one of the wallet's signers, and so can be elected and does co-sign. */
        val isParticipant: Boolean,
        val isOperator: Boolean,
        /** Whether this platform has a daemon at all. Only Android does (D2). */
        val canRunHere: Boolean,
        /** This device's own record of the wallet. */
        val ledger: GroupWavelengthLedger = GroupWavelengthLedger.of(emptyList()),
        val userPublicKey: HexKey = "",
    ) : GroupWavelengthWalletUIState()
}
