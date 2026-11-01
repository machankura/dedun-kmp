package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.GroupLightningPaymentRow
import com.machankura.dedun.compose.managers.GroupLightningNodeStatus
import com.machankura.dedun.compose.managers.GroupLightningOperatorManager
import com.machankura.dedun.compose.managers.GroupLightningReports
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.wallet.lightning.GroupSweep

/**
 * The group's Lightning wallet screen, through every state a group passes on its way to one: no onchain
 * wallet to close to, no key, a key but no statement, and a wallet with or without an operator.
 */
sealed class GroupLightningWalletUIState {
    data object Loading : GroupLightningWalletUIState()

    data class Error(val message: String) : GroupLightningWalletUIState()

    /** The onchain wallet on the Lightning wallet's network is not open yet (decision D2). */
    data class NeedsOnchainWallet(val localChatRoom: LocalChatRoom) : GroupLightningWalletUIState()

    /** No completed Iceberg key; [ceremony] is one in progress, if any. [canMakeKey] is false for a group too small. */
    data class NeedsKey(val localChatRoom: LocalChatRoom, val ceremony: IcebergKeySession?, val canMakeKey: Boolean) : GroupLightningWalletUIState()

    /** A key, and no statement the group signed. [canPropose] is whether this device holds a share and knows the LSP. */
    data class NotOpened(val localChatRoom: LocalChatRoom, val key: IcebergKeySession, val canPropose: Boolean) : GroupLightningWalletUIState()

    data class Opened(
        val localChatRoom: LocalChatRoom,
        val wallet: GroupLightningWalletManager.Wallet,
        val operator: GroupLightningOperatorManager.Operator?,
        /** Whether this device holds a share of the key, and so can be elected and can co-sign. */
        val isParticipant: Boolean,
        val isOperator: Boolean,
        /** The operator's newest snapshot, or null before the first arrives. */
        val report: GroupLightningReports.Report? = null,
        val payments: List<GroupLightningPaymentRow> = emptyList(),
        /** What closed channels left the group that no sweep spends yet. */
        val unswept: List<GroupSweep.Input> = emptyList(),
        /** Participants who have left the room, and still hold a share of the key and the node's seed. */
        val departed: List<HexKey> = emptyList(),
        /** Whether a new wallet can be opened now: a key no wallet uses, and this device can propose. */
        val canOpenNew: Boolean = false,
        /** The wallets a re-key left behind, whose channels are still to be closed and swept. */
        val earlier: List<Earlier> = emptyList(),
        /** This device's node for the wallet, on the operator's device only; null everywhere else. */
        val nodeStatus: GroupLightningNodeStatus? = null,
    ) : GroupLightningWalletUIState()

    /** A wallet a re-key left behind, with what its operator last said of it. */
    data class Earlier(
        val wallet: GroupLightningWalletManager.Wallet,
        val operator: GroupLightningOperatorManager.Operator?,
        val report: GroupLightningReports.Report?,
        val unswept: List<GroupSweep.Input>,
        /** This device's node for that wallet, when this device is its operator. */
        val nodeStatus: GroupLightningNodeStatus? = null,
    ) {
        val openChannels: List<String> get() = report?.checks?.map { it.channel.channelId }.orEmpty()
    }
}
