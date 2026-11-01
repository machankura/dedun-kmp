package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.FakeElectrum
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlin.test.assertTrue
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.view.state.GroupWalletUIState
import com.machankura.dedun.compose.ui.view.state.SpendStatus
import com.machankura.dedun.compose.wallet.GroupWalletRollout
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * What the wallet screen's view model decides: which of its states a room is in,
 * and what it asks the group to sign when a member opens a wallet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupWalletViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    /** A chain with nothing on it: the wallet screen's rules, not its coins. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val me = "a".repeat(64)

    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = me,
            subject = "Treasury",
            description = null,
            initialGiftWrapPayloadId = "payload",
            mlsGroupState = "state",
        )
    )

    private val account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(),
        Chain.Mainnet,
    )

    /** The repository's answer is the thing under test's input; the statement's own checks are `GroupWalletStatementEventTest`'s. */
    private val wallet = GroupWallet(
        signedEvent = GroupSignedEvent(
            id = "e".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = GroupWalletStatementEvent.KIND,
            tags = GroupWalletStatementEvent.template(account).tags,
            content = account.xpub,
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        ),
        account = account,
    )

    private fun repository(opened: GroupWallet?, canSign: Boolean = true, hasKey: Boolean = true) =
        object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(identifier: String): LocalChatRoom? = localChatRoom
            override suspend fun canSign(chatRoomId: String): Boolean = canSign
            override suspend fun groupWallet(chatRoomId: String, chain: Chain): GroupWallet? = opened
            override suspend fun groupWalletAccount(chatRoomId: String, chain: Chain): GroupWalletDerivation.Account? =
                account.takeIf { hasKey }
        }

    private class RecordingSigning : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        var proposed: Triple<Kind, Array<Array<String>>, String>? = null

        override suspend fun proposeSigning(
            localChatRoom: LocalChatRoom,
            userPublicKey: HexKey,
            kind: Kind,
            tags: Array<Array<String>>,
            content: String,
        ): FrostSigningSession? {
            proposed = Triple(kind, tags, content)
            return null
        }
    }

    private fun viewModel(chat: ChatRepository, signing: FrostSigningRepository = RecordingSigning(), chain: Chain = Chain.Mainnet) =
        GroupWalletViewModel(
            activeUserPublicKey = me,
            chatRoomId = chatRoomId,
            chain = chain,
            initialGroupWalletUIState = GroupWalletUIState.Loading,
            chatRepository = chat,
            frostSigningRepository = signing,
            wallets = GroupOnchainWallets(FakeElectrum(), scope),
        )

    private fun GroupWalletViewModel.settled(): GroupWalletUIState = runBlocking {
        withTimeout(5_000) {
            while (groupWalletUIState == GroupWalletUIState.Loading) delay(10)
            groupWalletUIState
        }
    }

    @Test
    fun `a group that has not opened a wallet is offered the account it would open`() {
        val state = assertIs<GroupWalletUIState.NotOpened>(viewModel(repository(opened = null)).apply { initiate() }.settled())

        assertEquals(account, state.account)
        assertEquals(true, state.canSign)
    }

    @Test
    fun `a room with no key has nothing to open`() {
        val state = assertIs<GroupWalletUIState.NotOpened>(
            viewModel(repository(opened = null, hasKey = false)).apply { initiate() }.settled()
        )

        assertNull(state.account)
    }

    @Test
    fun `an open wallet is shown`() {
        val state = assertIs<GroupWalletUIState.Loaded>(viewModel(repository(opened = wallet)).apply { initiate() }.settled())

        assertEquals(wallet, state.wallet)
    }

    @Test
    fun `opening a wallet proposes the statement for the account, and nothing else`() {
        val signing = RecordingSigning()
        val model = viewModel(repository(opened = null), signing).apply { initiate() }
        model.settled()

        var failed = false
        model.openWallet(onSuccess = {}, onFailure = { failed = true })
        runBlocking { withTimeout(5_000) { while (signing.proposed == null || model.isActionPending.value) delay(10) } }

        val (kind, tags, content) = assertNotNull(signing.proposed)
        assertEquals(GroupWalletStatementEvent.KIND, kind)
        assertEquals(account.xpub, content)
        assertEquals(account, GroupWalletStatementEvent.read(tags, content)?.verifiedAgainst(
            PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString()
        ))
        runBlocking { withTimeout(5_000) { while (!failed) delay(10) } }
    }

    // ---- a guarded wallet ---------------------------------------------------

    /** What the repository would hand a subgroup whose certificate grants it guardians; the checks behind it are `GroupWalletStatementEventTest`'s. */
    private val certificate = com.vitorpamplona.quartz.nip01Core.core.Event(
        "c".repeat(64), "b".repeat(64), 1_700_000_000, 30329, emptyArray(), chatRoomId, "f".repeat(128)
    )
    private val guardians = com.machankura.dedun.compose.managers.GuardianDerivation.grant(
        PrivateKey(ByteVector32(Hex.decode("9a4e47".padStart(64, '0')))).publicKey().toString(),
        "b".repeat(64), emptyList(), chatRoomId,
    )

    private fun guardedRepository(
        chain: Chain,
        policy: WalletPolicyReading? = WalletPolicyReading.Undecided,
    ) = object : ChatRepository by repository(opened = null) {
        override suspend fun walletPolicyOf(chatRoomId: String): WalletPolicyReading? = policy

        override suspend fun groupWalletGuardedOpening(chatRoomId: String, chain: Chain) =
            com.machankura.dedun.compose.managers.GroupWalletManager.GuardedOpening(
                GroupWalletDerivation.account(
                    PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(),
                    chain, guardians = guardians,
                ),
                certificate,
            )
    }

    @Test
    fun `a subgroup is offered a guarded wallet on a test network and on mainnet`() {
        val regtest = assertIs<GroupWalletUIState.NotOpened>(
            viewModel(guardedRepository(Chain.Regtest), chain = Chain.Regtest).apply { initiate() }.settled()
        )
        assertEquals(guardians, regtest.guarded?.account?.guardians)

        // Mainnet follows GroupWalletRollout.GUARDIANS_ON_MAINNET, which is on.
        val mainnet = assertIs<GroupWalletUIState.NotOpened>(
            viewModel(guardedRepository(Chain.Mainnet)).apply { initiate() }.settled()
        )
        assertEquals(GroupWalletRollout.GUARDIANS_ON_MAINNET, mainnet.guarded != null)
        assertEquals(guardians, mainnet.guarded?.account?.guardians)
    }

    @Test
    fun `opening a guarded wallet proposes a statement naming the guardians and carrying the certificate`() {
        val signing = RecordingSigning()
        val model = viewModel(guardedRepository(Chain.Regtest), signing, chain = Chain.Regtest).apply { initiate() }
        model.settled()

        model.openWallet(guarded = true, onSuccess = {}, onFailure = {})
        runBlocking { withTimeout(5_000) { while (signing.proposed == null || model.isActionPending.value) delay(10) } }

        val (_, tags, content) = assertNotNull(signing.proposed)
        val reading = assertNotNull(GroupWalletStatementEvent.read(tags, content))
        assertEquals(guardians, reading.guardians)
        assertEquals(certificate.id, reading.certificate?.id)
    }

    // ---- the parent's decision: docs/subgroup-wallet-policy.md -------------

    @Test
    fun `a subgroup whose parent decided guarded is offered the guarded wallet alone`() {
        val state = assertIs<GroupWalletUIState.NotOpened>(
            viewModel(guardedRepository(Chain.Regtest, WalletPolicyReading.Decided(SubgroupWalletPolicy.GUARDED)), chain = Chain.Regtest)
                .apply { initiate() }.settled()
        )

        assertNotNull(state.guarded)
        assertFalse(state.offersPlain)
    }

    @Test
    fun `a subgroup whose parent decided unguarded is offered the plain wallet alone`() {
        val state = assertIs<GroupWalletUIState.NotOpened>(
            viewModel(guardedRepository(Chain.Regtest, WalletPolicyReading.Decided(SubgroupWalletPolicy.UNGUARDED)), chain = Chain.Regtest)
                .apply { initiate() }.settled()
        )

        assertNull(state.guarded)
        assertTrue(state.offersPlain)
        assertEquals(account, state.account)
    }

    @Test
    fun `an undecided subgroup is offered both, and a root group the plain one`() {
        val undecided = assertIs<GroupWalletUIState.NotOpened>(
            viewModel(guardedRepository(Chain.Regtest), chain = Chain.Regtest).apply { initiate() }.settled()
        )
        assertNotNull(undecided.guarded)
        assertTrue(undecided.offersPlain)

        val root = assertIs<GroupWalletUIState.NotOpened>(viewModel(repository(opened = null)).apply { initiate() }.settled())
        assertNull(root.guarded)
        assertTrue(root.offersPlain)
    }

    @Test
    fun `each guardian is named by the room this device holds for it`() {
        val state = assertIs<GroupWalletUIState.NotOpened>(
            viewModel(guardedRepository(Chain.Regtest), chain = Chain.Regtest).apply { initiate() }.settled()
        )

        // The fixture's repository answers every room id with the one room.
        assertEquals(mapOf(guardians.single().chatRoomId to localChatRoom.chatRoom.subject!!), state.guardianNames)
    }

    @Test
    fun `a plain wallet is never proposed under a parent that decided guarded`() {
        val signing = RecordingSigning()
        val model = viewModel(
            guardedRepository(Chain.Regtest, WalletPolicyReading.Decided(SubgroupWalletPolicy.GUARDED)), signing, chain = Chain.Regtest
        ).apply { initiate() }
        model.settled()

        model.openWallet(guarded = false, onSuccess = {}, onFailure = {})
        runBlocking { delay(200) }

        assertNull(signing.proposed)
        assertFalse(model.isActionPending.value)
    }

    // ---- spends ------------------------------------------------------------

    private val coinTx = fr.acinq.bitcoin.Transaction(
        2, listOf(fr.acinq.bitcoin.TxIn(fr.acinq.bitcoin.OutPoint(fr.acinq.bitcoin.TxId(ByteVector32(ByteArray(32) { 1 })), 0), fr.acinq.bitcoin.ByteVector.empty, 0xffffffffL)),
        listOf(fr.acinq.bitcoin.TxOut(fr.acinq.bitcoin.Satoshi(60_000), GroupWalletDerivation.derive(account, 0, 0).pubkeyScript)), 0,
    )

    private val spend: com.machankura.dedun.compose.wallet.GroupSpend.Checked = run {
        val first = GroupWalletDerivation.derive(account, 0, 0)
        val change = GroupWalletDerivation.derive(account, 1, 0)
        val tx = fr.acinq.bitcoin.Transaction(
            2, listOf(fr.acinq.bitcoin.TxIn(fr.acinq.bitcoin.OutPoint(coinTx, 0), fr.acinq.bitcoin.ByteVector.empty, 0xfffffffdL)),
            listOf(
                fr.acinq.bitcoin.TxOut(fr.acinq.bitcoin.Satoshi(30_000), fr.acinq.bitcoin.ByteVector("0014" + "00".repeat(20))),
                fr.acinq.bitcoin.TxOut(fr.acinq.bitcoin.Satoshi(29_000), change.pubkeyScript),
            ), 0,
        )
        val psbt = com.machankura.dedun.compose.wallet.GroupSpend.psbtOf(tx, listOf(coinTx.txOut[0] to first), mapOf(1 to change), account)
        (com.machankura.dedun.compose.wallet.GroupSpend.check(psbt, account) as com.machankura.dedun.compose.wallet.GroupSpend.Result.Ok).checked
    }

    private val signedRow = com.machankura.dedun.compose.database.model.GroupTransaction(
        frostSigningSessionId = "spend-1", chatRoomId = chatRoomId, chain = "mainnet", account = 0,
        psbt = com.machankura.dedun.compose.wallet.GroupSpend.encode(spend.psbt), txid = spend.tx.txid.toString(), signedTransaction = "00",
    )

    private inner class SpendingRepository : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        var proposed: fr.acinq.bitcoin.psbt.Psbt? = null
        override fun observeGroupTransactions(chatRoomId: String) = kotlinx.coroutines.flow.flowOf(listOf(signedRow))
        override suspend fun checkedSpend(sessionId: String) = spend
        override suspend fun getSessionById(sessionId: String): FrostSigningSession? = null
        override suspend fun proposeSpend(localChatRoom: LocalChatRoom, userPublicKey: HexKey, psbt: fr.acinq.bitcoin.psbt.Psbt, chain: Chain): FrostSigningSession? {
            proposed = psbt
            return null
        }
    }

    @Test
    fun `the room's spends are listed, and a bump asks for twice the feerate on the same coins`() {
        val signing = SpendingRepository()
        val model = viewModel(repository(opened = wallet), signing).apply { initiate() }

        val loaded = runBlocking {
            withTimeout(5_000) {
                while ((model.groupWalletUIState as? GroupWalletUIState.Loaded)?.spends.isNullOrEmpty()) delay(10)
                model.groupWalletUIState as GroupWalletUIState.Loaded
            }
        }
        val row = loaded.spends.single()
        assertEquals(com.machankura.dedun.compose.ui.view.state.SpendStatus.SIGNED, row.status)
        assertEquals(true, row.canBump)

        model.bumpFee("spend-1", onSuccess = {}, onFailure = {})
        runBlocking { withTimeout(5_000) { while (signing.proposed == null) delay(10) } }

        val bumped = (com.machankura.dedun.compose.wallet.GroupSpend.check(signing.proposed!!, account) as com.machankura.dedun.compose.wallet.GroupSpend.Result.Ok).checked
        assertEquals(spend.tx.txIn.map { it.outPoint }, bumped.tx.txIn.map { it.outPoint })
        assertEquals(spend.feeratePerVbyte * 2, bumped.feeratePerVbyte)
    }

    @Test
    fun `a spend's status is the session's until it is signed, and the chain's after`() {
        val unsigned = signedRow.copy(signedTransaction = null)

        assertEquals(SpendStatus.WAITING_FOR_SIGNATURES, SpendStatus.of(FrostSigningStage.COLLECTING_NONCES, unsigned))
        assertEquals(SpendStatus.ABANDONED, SpendStatus.of(FrostSigningStage.FAILED, unsigned))
        assertEquals(SpendStatus.SIGNED, SpendStatus.of(FrostSigningStage.COMPLETE, signedRow))
        assertEquals(SpendStatus.SENT, SpendStatus.of(FrostSigningStage.COMPLETE, signedRow.copy(seenAt = Instant.fromEpochSeconds(1))))
        assertEquals(SpendStatus.CONFIRMED, SpendStatus.of(FrostSigningStage.COMPLETE, signedRow.copy(seenAt = Instant.fromEpochSeconds(1), confirmedAt = Instant.fromEpochSeconds(2))))
        assertEquals(SpendStatus.REPLACED, SpendStatus.of(FrostSigningStage.COMPLETE, signedRow.copy(replacedByTxid = "ab".repeat(32))))
    }
}
