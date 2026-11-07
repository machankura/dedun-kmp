package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWavelengthDaemonStatus
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthReports
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthLedger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

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
        /** This device's daemon for the wallet, which only says anything on the operator's device. */
        val daemon: GroupWavelengthDaemonStatus = GroupWavelengthDaemonStatus.NotOnThisDevice,
        /** The rows [ledger] sums, for the advanced screen. */
        val coins: List<GroupArkVtxo> = emptyList(),
        /** What the operator last said it holds, or null before it has said anything. */
        val snapshot: GroupWavelengthSnapshot? = null,
        val comparison: GroupWavelengthReports.Comparison? = null,
        /** When this device's coins run out of time, by its own count, or [WalletExpiry.Nothing] where it can't tell. */
        val expiry: WalletExpiry = WalletExpiry.Nothing,
        /** When each signer's device last answered anything: not whether it's online, which nobody can say. */
        val seen: Map<HexKey, Instant> = emptyMap(),
        /** What this device has lately refused, or been alarmed by, in its own words. */
        val alarms: List<String> = emptyList(),
        /** What this device has agreed to pay in fees this term, in the budget's window. */
        val feesSat: Long = 0,
        /** How many more signatures round one is ready for, on this device's count. */
        val labelsLeft: Long = 0,
        /** Signers who have left the room and still hold a share. Only a new wallet leaves them out. */
        val departed: List<HexKey> = emptyList(),
        /** Whether a new wallet can be proposed now: a key no wallet uses, and this device can propose. */
        val canOpenNew: Boolean = false,
        /** Wallets a new one replaced, with what this device has signed for in each, until they're empty. */
        val earlier: List<Earlier> = emptyList(),
        /** Whether the group has asked the wallet to leave, and that request still stands. */
        val leaveAsked: Boolean = false,
        /**
         * Everything the wallet has done, as this device reads it: its own records with the operator's claim
         * joined on (`docs/group-wavelength-transaction-details.md`). The one list the screen draws, in place
         * of the three it drew before -- approved payouts, onchain payouts, and the operator's activity.
         */
        val entries: List<GroupWavelengthEntry> = emptyList(),
    ) : GroupWavelengthWalletUIState() {
        /** Whether enough signers have answered lately for a round to be likely to work: `2t-1` of them have to. */
        val enoughSeen: Boolean get() = seenRecently() >= 2 * wallet.statement.threshold - 1

        fun seenRecently(now: Instant = Clock.System.now()): Int =
            wallet.statement.participants.count { p -> seen[p]?.let { now - it <= SEEN_WINDOW } == true }

        companion object {
            val SEEN_WINDOW = 1.hours
        }
    }

    /** A wallet a new one replaced, whose coins still need to leave. */
    data class Earlier(val wallet: GroupWavelengthWalletManager.Wallet, val operator: GroupWavelengthOperatorManager.Operator?, val settledSat: Long, val leaveAsked: Boolean)
}
