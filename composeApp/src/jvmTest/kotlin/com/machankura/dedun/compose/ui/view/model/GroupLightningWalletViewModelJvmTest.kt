package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupLightningInvoiceRow
import com.machankura.dedun.compose.database.model.GroupLightningPaymentRow
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.IcebergParticipantMessage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergApprovalStep
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.managers.GroupLightningAuthorisations
import com.machankura.dedun.compose.managers.GroupLightningDerivation
import com.machankura.dedun.compose.managers.GroupLightningNodeStatus
import com.machankura.dedun.compose.managers.GroupLightningOperatorManager
import com.machankura.dedun.compose.managers.LspLink
import com.machankura.dedun.compose.managers.GroupLightningReports
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningOperatorStatementEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.repository.GroupNodeStatusSource
import com.machankura.dedun.compose.ui.view.state.GroupLightningReceiveUIState
import com.machankura.dedun.compose.ui.view.state.GroupLightningSendUIState
import com.machankura.dedun.compose.ui.view.state.GroupLightningWalletUIState
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.machankura.dedun.compose.wallet.lightning.WalletWire
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import fr.acinq.secp256k1.Hex
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

/** Which of its states the Lightning wallet screen puts a room in, on the way from nothing to an elected operator. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupLightningWalletViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val me = "a".repeat(64)
    private val localChatRoom = LocalChatRoom(chatRoom = ChatRoom(id = chatRoomId, userPublicKey = me, subject = "Treasury", description = null, mlsGroupState = "state"))
    private val roomKey = PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString()

    private val key = IcebergKeySession(
        id = "ab".repeat(32), chatRoomId = chatRoomId, coordinatorPublicKey = me, userPublicKey = me, threshold = 2, participantCount = 4,
        participantPublicKeys = IcebergKeySession.formatParticipants(listOf(me, "b".repeat(64), "c".repeat(64), "d".repeat(64))),
        onchainDkgSessionId = "x", stage = IcebergRitualStage.COMPLETE, share = "00", groupPublicKey = "02" + "11".repeat(32),
    )

    private val wallet = GroupLightningWalletManager.Wallet(
        statement = GroupLightningWalletStatementEvent.Statement(Chain.Testnet3, 0, 0, key.groupPublicKey!!, key.id, 2, key.participants(), "02" + "33".repeat(32), "02" + "44".repeat(32), GroupLightningWalletStatementEvent.Policy.DEFAULT),
        signedEvent = GroupSignedEvent(id = "e".repeat(64), chatRoomId = chatRoomId, publicKey = chatRoomId, kind = GroupLightningWalletStatementEvent.KIND, tags = emptyArray(), content = "", signature = "f".repeat(128), createdAt = Instant.fromEpochSeconds(1)),
        basepoints = GroupLightningDerivation.basepoints(roomKey, Chain.Testnet3, 0),
        ceremony = key,
        seed = null,
    )

    private fun operator(publicKey: HexKey?) = GroupLightningOperatorManager.Operator(
        GroupLightningOperatorStatementEvent.Statement(Chain.Testnet3, 0, publicKey, 4, null),
        wallet.signedEvent,
    )

    private class Lightning(
        val onchain: Boolean = true,
        val completed: IcebergKeySession? = null,
        val running: IcebergKeySession? = null,
        val wallet: GroupLightningWalletManager.Wallet? = null,
        val operator: GroupLightningOperatorManager.Operator? = null,
        val thresholds: List<Int> = listOf(2),
        val report: GroupLightningReports.Report? = null,
        val payments: List<GroupLightningPaymentRow> = emptyList(),
        val invoice: GroupLightningInvoiceRow? = null,
        val departedMembers: List<HexKey> = emptyList(),
        val earlier: List<GroupLightningWalletManager.Wallet> = emptyList(),
        val unusedKey: Boolean = false,
    ) : GroupLightningRepository {
        val requests = mutableListOf<Pair<Long?, String>>()
        override fun observeCeremony(sessionId: String): Flow<IcebergKeySession?> = flowOf(running)
        override fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>> = flowOf(listOfNotNull(running))
        override suspend fun ceremonyMessages(sessionId: String): List<IcebergParticipantMessage> = emptyList()
        override suspend fun pendingApproval(session: IcebergKeySession): IcebergApprovalStep? = null
        override suspend fun ceremonyThresholds(chatRoomId: String): List<Int> = thresholds
        override suspend fun keyHolderCount(chatRoomId: String): Int = 4
        override suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean = onchain
        override suspend fun proposeCeremony(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, threshold: Int, chain: Chain): IcebergKeySession = error("unused")
        override suspend fun approveCeremony(localChatRoom: LocalChatRoom, sessionId: String, step: IcebergApprovalStep, nostrPrivateKey: ByteArray) = Unit
        override suspend fun resumeCeremony(localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray) = Unit
        override suspend fun abandonCeremony(localChatRoom: LocalChatRoom, sessionId: String): Boolean = false
        override suspend fun completedCeremony(chatRoomId: String): IcebergKeySession? = completed
        override suspend fun lightningWallet(chatRoomId: String, chain: Chain): GroupLightningWalletManager.Wallet? = wallet
        override suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long): GroupLightningOperatorManager.Operator? = operator
        override fun lspNodeId(chain: Chain): HexKey? = "02" + "44".repeat(32)
        override suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession = error("unused")
        override suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession = error("unused")
        override suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long): FrostSigningSession = error("unused")
        override fun observeReport(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupLightningReports.Report?> = flowOf(report)
        override fun observePayments(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupLightningPaymentRow>> = flowOf(payments)
        override suspend fun requestInvoice(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, amountMsat: Long?, description: String): String =
            "r1".also { requests += amountMsat to description }
        override fun observeInvoice(chatRoomId: String, chain: Chain, wallet: Long, requestId: String): Flow<GroupLightningInvoiceRow?> = flowOf(invoice?.takeIf { it.requestId == requestId })
        override suspend fun proposePayment(localChatRoom: LocalChatRoom, userPublicKey: HexKey, pay: GroupLightningAuthorisationEvent.Pay): FrostSigningSession = error("unused")
        override suspend fun proposeLiquidity(localChatRoom: LocalChatRoom, userPublicKey: HexKey, buy: GroupLightningAuthorisationEvent.BuyLiquidity): FrostSigningSession = error("unused")
        override suspend fun proposeClose(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, channelId: String, force: Boolean): FrostSigningSession = error("unused")
        override suspend fun earlierWallets(chatRoomId: String, chain: Chain): List<GroupLightningWalletManager.Wallet> = earlier
        override suspend fun departed(wallet: GroupLightningWalletManager.Wallet): List<HexKey> = departedMembers
        override suspend fun hasUnusedKey(chatRoomId: String, chain: Chain): Boolean = unusedKey
        override suspend fun unusedKey(chatRoomId: String, chain: Chain): IcebergKeySession? = completed?.takeIf { unusedKey }
        override suspend fun proposeCloseAll(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, channelIds: List<String>): FrostSigningSession = error("unused")
        override suspend fun unswept(chatRoomId: String, report: GroupLightningReports.Report?): List<GroupSweep.Input> = report?.snapshot?.heldOutputs.orEmpty()
        override suspend fun proposeSweep(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, inputs: List<GroupSweep.Input>): FrostSigningSession = error("unused")
        override suspend fun proposeSwapInRefund(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, member: HexKey, coins: List<com.machankura.dedun.compose.managers.GroupLightningSweeps.RefundableCoin>, toIndex: Long): FrostSigningSession = error("unused")
    }

    private val chat = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun getChatRoomByIdentifier(identifier: String): LocalChatRoom? = localChatRoom
    }

    private fun settled(lightning: GroupLightningRepository): GroupLightningWalletUIState {
        val viewModel = GroupLightningWalletViewModel(me, chatRoomId, Chain.Testnet3, GroupLightningWalletUIState.Loading, MutableStateFlow(null), chat, lightning)
        viewModel.initiate()
        return runBlocking {
            withTimeout(5_000) {
                while (viewModel.uiState == GroupLightningWalletUIState.Loading) delay(10)
                viewModel.uiState
            }
        }
    }

    @Test
    fun `without an onchain wallet there is nothing to open`() {
        assertIs<GroupLightningWalletUIState.NeedsOnchainWallet>(settled(Lightning(onchain = false, completed = key, wallet = wallet)))
    }

    @Test
    fun `with no key the group is asked to make one, or told it is too small`() {
        assertTrue(assertIs<GroupLightningWalletUIState.NeedsKey>(settled(Lightning())).canMakeKey)
        assertTrue(!assertIs<GroupLightningWalletUIState.NeedsKey>(settled(Lightning(thresholds = emptyList()))).canMakeKey)
        val running = key.copy(stage = IcebergRitualStage.COLLECTING_REVEALS, share = null)
        assertEquals(running, assertIs<GroupLightningWalletUIState.NeedsKey>(settled(Lightning(running = running))).ceremony)
    }

    @Test
    fun `with a key and no statement the wallet can be opened, given the key to sign it`() {
        val state = assertIs<GroupLightningWalletUIState.NotOpened>(settled(Lightning(completed = key)))
        assertEquals(key, state.key)
        assertTrue(!state.canPropose, "a profile with no private key to send the seed with cannot propose")
    }

    @Test
    fun `an open wallet says who runs it, and whether that is this device`() {
        val nobody = assertIs<GroupLightningWalletUIState.Opened>(settled(Lightning(completed = key, wallet = wallet)))
        assertTrue(nobody.isParticipant && !nobody.isOperator && nobody.operator == null)
        val mine = assertIs<GroupLightningWalletUIState.Opened>(settled(Lightning(completed = key, wallet = wallet, operator = operator(me))))
        assertTrue(mine.isOperator)
        val theirs = assertIs<GroupLightningWalletUIState.Opened>(settled(Lightning(completed = key, wallet = wallet, operator = operator("b".repeat(64)))))
        assertTrue(!theirs.isOperator)
    }

    /** This device's nodes, as a test sets them, and what was asked of them. */
    private class Nodes : GroupNodeStatusSource {
        val status = MutableStateFlow<GroupLightningNodeStatus>(GroupLightningNodeStatus.Starting)
        val observed = mutableListOf<Long>()
        val reconnected = mutableListOf<Long>()
        override fun observe(chatRoomId: String, wallet: Long): Flow<GroupLightningNodeStatus> = status.also { observed += wallet }
        override fun reconnectNow(chatRoomId: String, wallet: Long) { reconnected += wallet }
    }

    private fun opened(viewModel: GroupLightningWalletViewModel, until: (GroupLightningWalletUIState.Opened) -> Boolean): GroupLightningWalletUIState.Opened = runBlocking {
        withTimeout(5_000) {
            while ((viewModel.uiState as? GroupLightningWalletUIState.Opened)?.let(until) != true) delay(10)
            viewModel.uiState as GroupLightningWalletUIState.Opened
        }
    }

    @Test
    fun `the operator's device shows its node's status as it changes`() {
        val nodes = Nodes()
        val viewModel = GroupLightningWalletViewModel(me, chatRoomId, Chain.Testnet3, GroupLightningWalletUIState.Loading, MutableStateFlow(null), chat, Lightning(completed = key, wallet = wallet, operator = operator(me)), nodes)
        viewModel.initiate()
        assertEquals(GroupLightningNodeStatus.Starting, opened(viewModel) { it.nodeStatus != null }.nodeStatus)
        val connected = GroupLightningNodeStatus.Running(4, LspLink.Connected(Instant.fromEpochSeconds(1_000)))
        nodes.status.value = connected
        assertEquals(connected, opened(viewModel) { it.nodeStatus == connected }.nodeStatus)
        viewModel.reconnectNow()
        assertEquals(listOf(wallet.statement.wallet), nodes.reconnected)
    }

    @Test
    fun `a member's device never asks for a node it does not run`() {
        val nodes = Nodes()
        val viewModel = GroupLightningWalletViewModel(me, chatRoomId, Chain.Testnet3, GroupLightningWalletUIState.Loading, MutableStateFlow(null), chat, Lightning(completed = key, wallet = wallet, operator = operator("b".repeat(64))), nodes)
        viewModel.initiate()
        val state = opened(viewModel) { true }
        runBlocking { delay(100) }
        assertEquals(null, (viewModel.uiState as GroupLightningWalletUIState.Opened).nodeStatus)
        assertTrue(nodes.observed.isEmpty())
        assertTrue(!state.isOperator)
    }

    @Test
    fun `an open wallet shows the operator's report and the payments it has heard of`() {
        val channel = WalletWire.ChannelView("cc".repeat(32), "Normal", 1_000_000, 400_000_000, 600_000_000, "dd".repeat(32), 0, 3)
        val snapshot = WalletWire.Snapshot("w", 4, wallet.statement.nodeId, 100, listOf(channel), emptyList())
        val report = GroupLightningReports.Report(snapshot, listOf(GroupLightningReports.ChannelCheck(channel, 400_000_000)))
        val payment = GroupLightningPaymentRow("w", "p", incoming = true, amountMsat = 21_000, feesMsat = 0, paymentHash = null, description = "lunch", completedAt = null, succeeded = true)
        val viewModel = GroupLightningWalletViewModel(me, chatRoomId, Chain.Testnet3, GroupLightningWalletUIState.Loading, MutableStateFlow(null), chat, Lightning(completed = key, wallet = wallet, report = report, payments = listOf(payment)))
        viewModel.initiate()
        val state = runBlocking {
            withTimeout(5_000) {
                while ((viewModel.uiState as? GroupLightningWalletUIState.Opened)?.report == null) delay(10)
                viewModel.uiState as GroupLightningWalletUIState.Opened
            }
        }
        assertEquals(400_000_000, state.report!!.snapshot.spendableMsat)
        assertEquals(true, state.report!!.checks.single().agrees)
        assertEquals(listOf(payment), state.payments)
    }

    @Test
    fun `receiving asks the operator for an invoice and shows it once it arrives`() {
        assertIs<GroupLightningReceiveUIState.Unavailable>(receiving(Lightning(completed = key, wallet = wallet)).uiState, "nobody runs the node")

        val lightning = Lightning(completed = key, wallet = wallet, operator = operator("b".repeat(64)), invoice = GroupLightningInvoiceRow("w", "r1", "lnbcrt1"))
        // Only a depositor is issued an invoice: docs/member-deposits.md, Phase 6.
        assertIs<GroupLightningReceiveUIState.NotDepositor>(receiving(lightning, DepositRepository.Standing.NeedsDestination).uiState)

        val viewModel = receiving(lightning)
        assertIs<GroupLightningReceiveUIState.Form>(viewModel.uiState)
        assertTrue(viewModel.descriptionField.text.startsWith("Deposit from @"), "the payer reads whose deposit it is")
        viewModel.amountField.edit { append("2,100") }
        viewModel.descriptionField.edit { replace(0, length, " lunch ") }
        viewModel.request(onFailure = { error("the request failed") })
        val shown = runBlocking { withTimeout(5_000) { while (viewModel.uiState !is GroupLightningReceiveUIState.Invoice) delay(10); viewModel.uiState } }
        assertEquals("lnbcrt1", assertIs<GroupLightningReceiveUIState.Invoice>(shown).bolt11)
        assertEquals(listOf<Pair<Long?, String>>(2_100_000L to "lunch"), lightning.requests.toList())
    }

    /** A depositor's standing, as the group would have signed it for this profile. */
    private val depositor: DepositRepository.Standing = run {
        val member = fr.acinq.bitcoin.PrivateKey(fr.acinq.bitcoin.ByteVector32(fr.acinq.secp256k1.Hex.decode("a11ce".padStart(64, '0'))))
        val destination = com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent.verified(
            com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent.build(
                member, chatRoomId, Chain.Testnet3, com.machankura.dedun.compose.text.LightningAddress("me", "example.com"), createdAt = 1_700_000_000,
            )
        ) as com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
        val event = com.vitorpamplona.quartz.nip01Core.core.Event("00".repeat(32), chatRoomId, 1_700_000_100, 30339, emptyArray(), "", "")
        DepositRepository.Standing.Open(com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent.Depositor(destination.member, Chain.Testnet3, 2, 0, destination, event))
    }

    private fun receiving(lightning: GroupLightningRepository, standing: DepositRepository.Standing = depositor): GroupLightningReceiveViewModel {
        val deposits = object : DepositRepository by DepositRepository.NO_OP {
            override suspend fun standing(chatRoomId: String, chain: Chain, canPropose: Boolean) = standing
        }
        val viewModel = GroupLightningReceiveViewModel(me, chatRoomId, Chain.Testnet3, GroupLightningReceiveUIState.Loading, chat, lightning, deposits)
        viewModel.initiate()
        runBlocking { withTimeout(5_000) { while (viewModel.uiState == GroupLightningReceiveUIState.Loading) delay(10) } }
        return viewModel
    }

    @Test
    fun `sending previews the authorisation, with a fee limit when none is given`() {
        val lightning = Lightning(completed = key, wallet = wallet, operator = operator("b".repeat(64)))
        val viewModel = GroupLightningSendViewModel(me, chatRoomId, Chain.Testnet3, GroupLightningSendUIState.Loading, chat, lightning)
        viewModel.initiate()
        runBlocking<Unit> { withTimeout(5_000) { while (viewModel.uiState == GroupLightningSendUIState.Loading) delay(10) } }
        assertIs<GroupLightningSendUIState.Form>(viewModel.uiState)
        assertEquals(null, viewModel.preview(), "nothing typed, nothing to say")

        fun invoice(chain: Chain, amount: Long?) = Bolt11Invoice.create(
            chain, amount?.msat, ByteVector32(Hex.decode("07".repeat(32))), PrivateKey(ByteVector32(Hex.decode("05".repeat(32)))), Either.Left("tea"), CltvExpiryDelta(144),
            Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory),
        ).write()
        viewModel.invoiceField.edit { append(invoice(Chain.Testnet3, 50_000_000)) }
        val pay = viewModel.preview()!!.getOrThrow()
        assertEquals(50_000_000, pay.amountMsat)
        assertEquals(500_000, pay.maxFeeMsat, "a percent of fifty thousand sats")
        assertEquals(50_000_000, viewModel.invoiceAmountMsat())

        viewModel.maxFeeField.edit { append("20") }
        assertEquals(20_000, viewModel.preview()!!.getOrThrow().maxFeeMsat)

        viewModel.invoiceField.edit { replace(0, length, invoice(Chain.Mainnet, 1_000)) }
        val problem = (viewModel.preview()!!.exceptionOrNull() as GroupLightningAuthorisations.ProblemException).problem
        assertEquals(GroupLightningAuthorisations.Problem.OtherNetwork, problem)
    }

    @Test
    fun `after a member leaves, the wallet says so, and lists the wallet a re-key left behind`() {
        val earlier = wallet.copy(statement = wallet.statement.copy(wallet = 0))
        val current = wallet.copy(statement = wallet.statement.copy(wallet = 1))
        val state = assertIs<GroupLightningWalletUIState.Opened>(
            settled(Lightning(completed = key, wallet = current, departedMembers = listOf("d".repeat(64)), earlier = listOf(earlier), unusedKey = true))
        )
        assertEquals(listOf("d".repeat(64)), state.departed)
        assertEquals(listOf(0L), state.earlier.map { it.wallet.statement.wallet })
        assertTrue(!state.canOpenNew, "a profile with no private key to send the seed with cannot open the new wallet")
    }
}
