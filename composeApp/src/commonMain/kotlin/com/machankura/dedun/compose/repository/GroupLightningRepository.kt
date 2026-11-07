package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.managers.GroupLightningSweeps
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.GroupLightningInvoiceRow
import com.machankura.dedun.compose.database.model.GroupLightningPaymentRow
import com.machankura.dedun.compose.managers.GroupLightningOperatorManager
import com.machankura.dedun.compose.managers.GroupLightningReports
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.database.model.IcebergParticipantMessage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergApprovalStep
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Reads, opens and approves a group's Lightning wallet: for now, the Iceberg ceremony its key comes
 * from (`docs/group-lightning-wallet.md`, Phase 5). Advancing a ceremony is the inbound path's job,
 * except that a step waiting on this device's owner waits until [approveCeremony].
 */
interface GroupLightningRepository {
    fun observeCeremony(sessionId: String): Flow<IcebergKeySession?>

    fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>>

    suspend fun ceremonyMessages(sessionId: String): List<IcebergParticipantMessage>

    suspend fun pendingApproval(session: IcebergKeySession): IcebergApprovalStep?

    /** The thresholds a ceremony in [chatRoomId] could use, smallest first; empty when the room cannot have one. */
    suspend fun ceremonyThresholds(chatRoomId: String): List<Int>

    /** How many people hold the onchain wallet's key, and so would be in a ceremony; 0 for a room without one. */
    suspend fun keyHolderCount(chatRoomId: String): Int

    /** Whether [chatRoomId] has opened the onchain wallet a Lightning wallet on [chain] would close to. */
    suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean

    suspend fun proposeCeremony(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, threshold: Int, chain: Chain, purpose: String = IcebergKeySession.PURPOSE_LIGHTNING): IcebergKeySession

    suspend fun approveCeremony(localChatRoom: LocalChatRoom, sessionId: String, step: IcebergApprovalStep, nostrPrivateKey: ByteArray)

    /** Takes whatever steps the ceremony can take now, for one a gate of an earlier build left waiting. */
    suspend fun resumeCeremony(localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray)

    /** Ends the ceremony for everyone in it; false when it is already over. */
    suspend fun abandonCeremony(localChatRoom: LocalChatRoom, sessionId: String): Boolean

    /** The newest completed Lightning wallet key this device holds a share of, or null. */
    suspend fun completedCeremony(chatRoomId: String): IcebergKeySession?

    /** The wallet the room uses on [chain] now -- the highest-numbered it agreed -- as this device can check it, or null. */
    suspend fun lightningWallet(chatRoomId: String, chain: Chain): GroupLightningWalletManager.Wallet?

    /** The wallets a re-key left behind on [chain], lowest number first: their channels are still to close. */
    suspend fun earlierWallets(chatRoomId: String, chain: Chain): List<GroupLightningWalletManager.Wallet>

    /** The participants of [wallet] who have left its room, and still hold a share of its key and its seed. */
    suspend fun departed(wallet: GroupLightningWalletManager.Wallet): List<HexKey>

    /** Whether the room has a completed Lightning wallet key no wallet uses yet, for a new wallet to open under. */
    suspend fun hasUnusedKey(chatRoomId: String, chain: Chain): Boolean

    /** That key itself, the newest of them, or null: what the chat's standing notice names. */
    suspend fun unusedKey(chatRoomId: String, chain: Chain): IcebergKeySession?

    suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long): GroupLightningOperatorManager.Operator?

    /** The LSP a Lightning wallet on [chain] opens channels with, or null for a network with none. */
    fun lspNodeId(chain: Chain): HexKey?

    suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession

    suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession

    suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession

    /** The operator's newest snapshot of the wallet on [chain], each channel checked against this device's ledger. */
    fun observeReport(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupLightningReports.Report?>

    /** Every payment any snapshot has reported, newest first. */
    fun observePayments(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupLightningPaymentRow>>

    /** Asks the operator's node for an invoice; returns the request id its answer will carry. */
    suspend fun requestInvoice(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, amountMsat: Long?, description: String): String

    /** The operator's invoice for [requestId], once it has arrived and been checked. */
    fun observeInvoice(chatRoomId: String, chain: Chain, wallet: Long, requestId: String): Flow<GroupLightningInvoiceRow?>

    /** Proposes [pay] to the group; the operator's node pays it once a quorum signs. */
    suspend fun proposePayment(localChatRoom: LocalChatRoom, userPublicKey: HexKey, pay: GroupLightningAuthorisationEvent.Pay): FrostSigningSession

    /** Proposes [buy] to the group; the operator's node buys the liquidity once a quorum signs. */
    suspend fun proposeLiquidity(localChatRoom: LocalChatRoom, userPublicKey: HexKey, buy: GroupLightningAuthorisationEvent.BuyLiquidity): FrostSigningSession

    /** Proposes closing [channelId] to the onchain wallet, or force-closing it when [force]. */
    suspend fun proposeClose(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, channelId: String, force: Boolean): FrostSigningSession

    /** Proposes closing every one of [channelIds] to the onchain wallet, in one signing session. */
    suspend fun proposeCloseAll(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, channelIds: List<String>): FrostSigningSession

    /** What closed channels left the group that no sweep spends yet, from [report]. */
    suspend fun unswept(chatRoomId: String, report: GroupLightningReports.Report?): List<GroupSweep.Input>

    /** Proposes sweeping [inputs] into the onchain wallet. */
    suspend fun proposeSweep(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, inputs: List<GroupSweep.Input>): FrostSigningSession

    /** Proposes refunding [member]'s swap-in [coins] to their deposit branch's address [toIndex] (`docs/group-lightning-swap-in.md`, Phase 7). */
    suspend fun proposeSwapInRefund(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, member: HexKey, coins: List<GroupLightningSweeps.RefundableCoin>, toIndex: Long): FrostSigningSession

    companion object {
        /**
         * For previews and for screens that only read: every flow empty, every read empty or
         * null, and every proposal refused, since there is no room behind it to propose in.
         */
        val NO_OP_GROUP_LIGHTNING_REPOSITORY: GroupLightningRepository = object : GroupLightningRepository {
            override fun observeCeremony(sessionId: String): Flow<IcebergKeySession?> = flowOf(null)
            override fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>> = flowOf(emptyList())
            override suspend fun ceremonyMessages(sessionId: String): List<IcebergParticipantMessage> = emptyList()
            override suspend fun pendingApproval(session: IcebergKeySession): IcebergApprovalStep? = null
            override suspend fun ceremonyThresholds(chatRoomId: String): List<Int> = emptyList()
            override suspend fun keyHolderCount(chatRoomId: String): Int = 0
            override suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean = false
            override suspend fun proposeCeremony(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, threshold: Int, chain: Chain, purpose: String): IcebergKeySession = error("No room to propose in")
            override suspend fun approveCeremony(localChatRoom: LocalChatRoom, sessionId: String, step: IcebergApprovalStep, nostrPrivateKey: ByteArray) = Unit
            override suspend fun resumeCeremony(localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray) = Unit
            override suspend fun abandonCeremony(localChatRoom: LocalChatRoom, sessionId: String): Boolean = false
            override suspend fun completedCeremony(chatRoomId: String): IcebergKeySession? = null
            override suspend fun lightningWallet(chatRoomId: String, chain: Chain): GroupLightningWalletManager.Wallet? = null
            override suspend fun earlierWallets(chatRoomId: String, chain: Chain): List<GroupLightningWalletManager.Wallet> = emptyList()
            override suspend fun departed(wallet: GroupLightningWalletManager.Wallet): List<HexKey> = emptyList()
            override suspend fun hasUnusedKey(chatRoomId: String, chain: Chain): Boolean = false
            override suspend fun unusedKey(chatRoomId: String, chain: Chain): IcebergKeySession? = null
            override suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long): GroupLightningOperatorManager.Operator? = null
            override fun lspNodeId(chain: Chain): HexKey? = null
            override suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession = error("No room to propose in")
            override suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession = error("No room to propose in")
            override suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession = error("No room to propose in")
            override fun observeReport(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupLightningReports.Report?> = flowOf(null)
            override fun observePayments(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupLightningPaymentRow>> = flowOf(emptyList())
            override suspend fun requestInvoice(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, amountMsat: Long?, description: String): String = error("No room to ask in")
            override fun observeInvoice(chatRoomId: String, chain: Chain, wallet: Long, requestId: String): Flow<GroupLightningInvoiceRow?> = flowOf(null)
            override suspend fun proposePayment(localChatRoom: LocalChatRoom, userPublicKey: HexKey, pay: GroupLightningAuthorisationEvent.Pay): FrostSigningSession = error("No room to propose in")
            override suspend fun proposeLiquidity(localChatRoom: LocalChatRoom, userPublicKey: HexKey, buy: GroupLightningAuthorisationEvent.BuyLiquidity): FrostSigningSession = error("No room to propose in")
            override suspend fun proposeClose(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, channelId: String, force: Boolean): FrostSigningSession = error("No room to propose in")
            override suspend fun proposeCloseAll(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, channelIds: List<String>): FrostSigningSession = error("No room to propose in")
            override suspend fun unswept(chatRoomId: String, report: GroupLightningReports.Report?): List<GroupSweep.Input> = emptyList()
            override suspend fun proposeSweep(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, inputs: List<GroupSweep.Input>): FrostSigningSession = error("No room to propose in")
            override suspend fun proposeSwapInRefund(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, member: HexKey, coins: List<GroupLightningSweeps.RefundableCoin>, toIndex: Long): FrostSigningSession = error("No room to propose in")
        }
    }
}
