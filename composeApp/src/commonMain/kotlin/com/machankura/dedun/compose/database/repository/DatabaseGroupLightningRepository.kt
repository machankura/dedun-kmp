package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.IcebergParticipantMessage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergApprovalStep
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupLightningInvoiceRow
import com.machankura.dedun.compose.database.model.GroupLightningPaymentRow
import com.machankura.dedun.compose.managers.GroupLightningAuthorisations
import com.machankura.dedun.compose.managers.GroupLightningCoSigner
import com.machankura.dedun.compose.managers.GroupLightningOperatorManager
import com.machankura.dedun.compose.managers.GroupLightningReports
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.managers.GroupWalletManager
import fr.acinq.phoenixd.conf.LSP
import com.machankura.dedun.compose.managers.IcebergRitualManager
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import com.machankura.dedun.compose.managers.GroupLightningSweeps
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DatabaseGroupLightningRepository(private val database: DedunDatabase) : GroupLightningRepository {
    override suspend fun completedCeremony(chatRoomId: String): IcebergKeySession? = IcebergRitualManager.completedKey(database, chatRoomId)

    override suspend fun lightningWallet(chatRoomId: String, chain: Chain): GroupLightningWalletManager.Wallet? =
        GroupLightningWalletManager.current(database, chatRoomId, chain)

    override suspend fun earlierWallets(chatRoomId: String, chain: Chain): List<GroupLightningWalletManager.Wallet> =
        GroupLightningWalletManager.wallets(database, chatRoomId, chain).dropLast(1)

    override suspend fun departed(wallet: GroupLightningWalletManager.Wallet): List<HexKey> =
        GroupLightningWalletManager.departed(database, wallet)

    override suspend fun hasUnusedKey(chatRoomId: String, chain: Chain): Boolean =
        GroupLightningWalletManager.unusedKey(database, chatRoomId, chain) != null

    override suspend fun unusedKey(chatRoomId: String, chain: Chain): IcebergKeySession? =
        GroupLightningWalletManager.unusedKey(database, chatRoomId, chain)

    override suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long): GroupLightningOperatorManager.Operator? =
        GroupLightningOperatorManager.current(database, chatRoomId, chain, wallet)

    override fun lspNodeId(chain: Chain): HexKey? =
        runCatching { LSP.from(chain).walletParams.trampolineNode.id.toString() }.getOrNull()

    override suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession =
        GroupLightningWalletManager.proposeWallet(database, localChatRoom, userPublicKey, nostrPrivateKey, chain, requireNotNull(lspNodeId(chain)) { "No LSP is known for ${chain.name}" })

    override suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession =
        GroupLightningOperatorManager.nominateSelf(database, localChatRoom, userPublicKey, chain, wallet)

    override suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession =
        GroupLightningOperatorManager.stepDown(database, localChatRoom, userPublicKey, chain, wallet)

    override fun observeReport(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupLightningReports.Report?> {
        val key = GroupLightningCoSigner.walletKey(chatRoomId, chain, wallet)
        return database.groupLightningRecordsDao().observeSnapshot(key).map { GroupLightningReports.latest(database, key) }
    }

    override fun observePayments(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupLightningPaymentRow>> =
        database.groupLightningRecordsDao().observePayments(GroupLightningCoSigner.walletKey(chatRoomId, chain, wallet))

    override suspend fun requestInvoice(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, amountMsat: Long?, description: String): String =
        GroupLightningReports.requestInvoice(database, localChatRoom, userPublicKey, wallet, amountMsat, description)

    override fun observeInvoice(chatRoomId: String, chain: Chain, wallet: Long, requestId: String): Flow<GroupLightningInvoiceRow?> =
        database.groupLightningRecordsDao().observeInvoice(GroupLightningCoSigner.walletKey(chatRoomId, chain, wallet), requestId)

    override suspend fun proposePayment(localChatRoom: LocalChatRoom, userPublicKey: HexKey, pay: GroupLightningAuthorisationEvent.Pay): FrostSigningSession =
        GroupLightningAuthorisations.propose(database, localChatRoom, userPublicKey, pay)

    override suspend fun proposeLiquidity(localChatRoom: LocalChatRoom, userPublicKey: HexKey, buy: GroupLightningAuthorisationEvent.BuyLiquidity): FrostSigningSession =
        GroupLightningAuthorisations.proposeBuyLiquidity(database, localChatRoom, userPublicKey, buy)

    override suspend fun proposeClose(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, channelId: String, force: Boolean): FrostSigningSession =
        GroupLightningAuthorisations.proposeChannelAction(database, localChatRoom, userPublicKey, wallet, channelId, force)

    override suspend fun proposeCloseAll(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, channelIds: List<String>): FrostSigningSession =
        GroupLightningAuthorisations.proposeCloseAll(database, localChatRoom, userPublicKey, wallet, channelIds)

    override suspend fun unswept(chatRoomId: String, report: GroupLightningReports.Report?): List<GroupSweep.Input> =
        GroupLightningSweeps.unswept(database, chatRoomId, report)

    override suspend fun proposeSweep(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, inputs: List<GroupSweep.Input>): FrostSigningSession =
        GroupLightningSweeps.propose(database, localChatRoom, userPublicKey, wallet, inputs)

    override suspend fun proposeSwapInRefund(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, member: HexKey, coins: List<GroupLightningSweeps.RefundableCoin>, toIndex: Long): FrostSigningSession =
        GroupLightningSweeps.proposeSwapInRefund(database, localChatRoom, userPublicKey, wallet, member, coins, toIndex)

    override fun observeCeremony(sessionId: String): Flow<IcebergKeySession?> =
        database.icebergKeySessionDao().observeSessionById(sessionId)

    override fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>> =
        database.icebergKeySessionDao().observeSessionsForChatRoom(chatRoomId)

    override suspend fun ceremonyMessages(sessionId: String): List<IcebergParticipantMessage> =
        GroupLightningEvents.CEREMONY.flatMap { database.icebergKeySessionDao().getMessagesByKind(sessionId, it) }

    override suspend fun pendingApproval(session: IcebergKeySession): IcebergApprovalStep? =
        IcebergRitualManager.pendingApproval(database, session)

    override suspend fun ceremonyThresholds(chatRoomId: String): List<Int> =
        IcebergRitualManager.thresholds(database, chatRoomId)

    override suspend fun keyHolderCount(chatRoomId: String): Int =
        FrostSigningManager.completedKey(database, chatRoomId)?.let { IcebergRitualManager.holdersInRoom(database, chatRoomId, it).size } ?: 0

    override suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean =
        GroupWalletManager.walletFor(database, chatRoomId, chain) != null

    override suspend fun proposeCeremony(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, threshold: Int, chain: Chain, purpose: String): IcebergKeySession =
        IcebergRitualManager.proposeCeremony(database, localChatRoom, userPublicKey, nostrPrivateKey, threshold, chain, purpose)

    override suspend fun approveCeremony(localChatRoom: LocalChatRoom, sessionId: String, step: IcebergApprovalStep, nostrPrivateKey: ByteArray) =
        IcebergRitualManager.approve(database, localChatRoom, sessionId, step, nostrPrivateKey)

    override suspend fun resumeCeremony(localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray) =
        IcebergRitualManager.resume(database, localChatRoom, sessionId, nostrPrivateKey)

    override suspend fun abandonCeremony(localChatRoom: LocalChatRoom, sessionId: String): Boolean =
        IcebergRitualManager.abandon(database, localChatRoom, sessionId)
}
