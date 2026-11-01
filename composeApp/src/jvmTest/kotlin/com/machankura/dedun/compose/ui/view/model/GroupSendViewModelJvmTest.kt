package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.FakeElectrum
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.GroupTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.ui.view.state.FundingTarget
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.ui.view.state.GroupSendUIState
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.GroupSpendBuilder
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.machankura.dedun.compose.wallet.GroupWalletRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.lightning.blockchain.electrum.WalletState
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The send form: what it previews is what it proposes, and it is not offered to
 * a device that cannot both sign and see the coins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSendViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    /** A chain with nothing on it: what is under test here is the form, not the coins. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wallets = GroupOnchainWallets(FakeElectrum(), scope)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(id = chatRoomId, userPublicKey = "a".repeat(64), subject = "Treasury", description = null, initialGiftWrapPayloadId = "p", mlsGroupState = "state")
    )
    private fun accountOn(chain: Chain): GroupWalletDerivation.Account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(), chain,
    )
    private val account: GroupWalletDerivation.Account = accountOn(Chain.Regtest)
    private val wallet = GroupWallet(
        GroupSignedEvent(
            id = "e".repeat(64), chatRoomId = chatRoomId, publicKey = chatRoomId, kind = GroupWalletStatementEvent.KIND,
            tags = GroupWalletStatementEvent.template(account).tags, content = account.xpub, signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        ),
        account,
    )

    /** One confirmed coin of [sats] under [account], and one unused change address. */
    private fun walletStateOf(account: GroupWalletDerivation.Account, sats: Long): GroupWalletState {
        val chain = account.chain
        val derived = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 1 })), 0), ByteVector.empty, 0xffffffffL)), listOf(TxOut(Satoshi(sats), derived.pubkeyScript)), 0)
        val meta = WalletState.AddressMeta.Derived(0)
        val change = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)
        return GroupWalletState(
            account,
            WalletState(mapOf(derived.address(chain) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(tx.txid, 0, 100, tx, meta))))),
            WalletState(mapOf(change.address(chain) to WalletState.AddressState(WalletState.AddressMeta.Derived(0), false, emptyList()))),
            tipHeight = 110,
        )
    }

    private val walletState: GroupWalletState = walletStateOf(account, 100_000)

    private class RecordingSigning : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        var proposed: Psbt? = null
        override suspend fun proposeSpend(localChatRoom: LocalChatRoom, userPublicKey: HexKey, psbt: Psbt, chain: Chain): FrostSigningSession? {
            proposed = psbt
            return null
        }
    }

    private fun viewModel(
        initial: GroupSendUIState,
        chat: ChatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
        signing: FrostSigningRepository = RecordingSigning(),
        chain: Chain = Chain.Regtest,
    ) = GroupSendViewModel("a".repeat(64), chatRoomId, chain, initial, chat, signing, wallets = wallets)

    @Test
    fun `a member with no share is not offered the form`() {
        val chat = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(identifier: String) = localChatRoom
            override suspend fun canSign(chatRoomId: String) = false
            override suspend fun groupWallet(chatRoomId: String, chain: Chain) = wallet
        }
        val model = viewModel(GroupSendUIState.Loading, chat).apply { initiate() }

        runBlocking { withTimeout(5_000) { while (model.groupSendUIState == GroupSendUIState.Loading) delay(10) } }
        assertEquals(GroupSendUIState.Unavailable, model.groupSendUIState)
    }

    @Test
    fun `the preview waits for the form, and what it builds is what is proposed`() {
        val signing = RecordingSigning()
        val model = viewModel(GroupSendUIState.Ready(localChatRoom, wallet, walletState, reserved = emptySet<OutPoint>()), signing = signing)

        assertNull(model.preview(), "nothing to build before an address and a feerate")

        model.addressField.setTextAndPlaceCursorAtEnd("bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080")
        model.feerateField.setTextAndPlaceCursorAtEnd("4")
        model.amountField.setTextAndPlaceCursorAtEnd("40,000")
        val built = assertIs<GroupSpendBuilder.Result.Built>(model.preview())
        assertEquals(Satoshi(40_000), built.amount)
        assertEquals(Satoshi(100_000), built.amount + built.fee + assertNotNull(built.change), "the rest comes back")

        model.propose(onSuccess = {}, onFailure = {})
        runBlocking { withTimeout(5_000) { while (signing.proposed == null) delay(10) } }
        assertEquals(built.psbt, signing.proposed)
        assertIs<GroupSpend.Result.Ok>(GroupSpend.check(signing.proposed!!, account), "the other members would accept it")
    }

    @Test
    fun `a decimal amount is refused, never sent ten times over`() {
        val model = viewModel(GroupSendUIState.Ready(localChatRoom, wallet, walletState, reserved = emptySet()))
        model.addressField.setTextAndPlaceCursorAtEnd("bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080")
        model.feerateField.setTextAndPlaceCursorAtEnd("4")
        model.amountField.setTextAndPlaceCursorAtEnd("21000.5")

        assertEquals(SatsInput.NotWhole, model.amount())
        assertEquals(GroupSpendBuilder.Result.InvalidAmount(SatsInput.NotWhole), model.preview())
    }

    @Test
    fun `on mainnet the cap holds an amount, as it holds sending everything`() {
        val mainnet = accountOn(Chain.Mainnet)
        val model = viewModel(
            GroupSendUIState.Ready(localChatRoom, wallet, walletStateOf(mainnet, 300_000), reserved = emptySet()),
            chain = Chain.Mainnet,
        )
        model.addressField.setTextAndPlaceCursorAtEnd("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        model.feerateField.setTextAndPlaceCursorAtEnd("4")

        model.amountField.setTextAndPlaceCursorAtEnd("150,000")
        assertEquals(GroupSpendBuilder.Result.OverCap(GroupWalletRollout.MAINNET_CAP), model.preview())

        model.amountField.setTextAndPlaceCursorAtEnd("100,000")
        val built = assertIs<GroupSpendBuilder.Result.Built>(model.preview(), "the cap itself is allowed")
        assertNotNull(built.change)
        assertIs<GroupSpend.Result.Ok>(GroupSpend.check(built.psbt, mainnet))
    }

    /** The room's spends as the database would report them, and what they hold, set by the test. */
    private class Reserving : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        val transactions = MutableStateFlow<List<GroupTransaction>>(emptyList())
        @Volatile var heldCoins: Set<OutPoint> = emptySet()
        @Volatile var heldChange: Set<Long> = emptySet()
        @Volatile var heldFunding: Set<Long> = emptySet()
        override fun observeGroupTransactions(chatRoomId: String): Flow<List<GroupTransaction>> = transactions
        override suspend fun reservedOutPoints(chatRoomId: String): Set<OutPoint> = heldCoins
        override suspend fun reservedChangeIndices(chatRoomId: String, account: GroupWalletDerivation.Account): Set<Long> = heldChange
        override suspend fun reservedFundingIndices(chatRoomId: String, account: GroupWalletDerivation.Account): Set<Long> = heldFunding
    }

    /** Two confirmed coins, 60,000 and 40,000 sats, on the first two receive addresses. */
    private val twoCoins: GroupWalletState = run {
        val coins = listOf(60_000L, 40_000L).mapIndexed { index, sats ->
            val derived = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, index.toLong())
            val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { (index + 1).toByte() })), 0), ByteVector.empty, 0xffffffffL)), listOf(TxOut(Satoshi(sats), derived.pubkeyScript)), 0)
            val meta = WalletState.AddressMeta.Derived(index)
            derived.address(Chain.Regtest) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(tx.txid, 0, 100, tx, meta)))
        }.toMap()
        walletState.copy(receive = WalletState(coins))
    }

    private fun <T : Any> eventually(read: () -> T?): T =
        runBlocking { withTimeout(5_000) { var value = read(); while (value == null) { delay(10); value = read() }; value } }

    @Test
    fun `a spend proposed while the form is open takes its coins out of what the form will send`() {
        val signing = Reserving()
        val ready = GroupSendUIState.Ready(localChatRoom, wallet, walletState = null, reserved = emptySet())
        val model = viewModel(ready, signing = signing)
        model.follow(ready, flowOf(twoCoins))
        model.addressField.setTextAndPlaceCursorAtEnd("bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080")
        model.feerateField.setTextAndPlaceCursorAtEnd("4")
        model.sendEverything.value = true

        val before = eventually { model.preview() as? GroupSpendBuilder.Result.Built }
        assertEquals(Satoshi(100_000), before.amount + before.fee, "both coins, before anybody holds one")

        // Another member's proposal arrives: it holds the 60,000 coin and change index 0.
        val bigCoin = twoCoins.utxos.single { it.amount == Satoshi(60_000) }.utxo.outPoint
        signing.heldCoins = setOf(bigCoin)
        signing.heldChange = setOf(0L)
        signing.transactions.value = listOf(
            GroupTransaction(frostSigningSessionId = "theirs", chatRoomId = chatRoomId, chain = "regtest", account = 0, psbt = "", txid = "t".repeat(64))
        )

        val following = eventually { (model.groupSendUIState as GroupSendUIState.Ready).takeIf { it.reserved.isNotEmpty() } }
        assertEquals(setOf(bigCoin), following.reserved)
        assertEquals(setOf(0L), following.reservedChange)
        assertEquals(twoCoins, following.walletState, "the coins are kept while the reservations change")

        val after = assertIs<GroupSpendBuilder.Result.Built>(model.preview())
        assertEquals(Satoshi(40_000), after.amount + after.fee, "only the coin nobody holds, without the form being opened again")
        assertEquals(Satoshi(40_000), model.spendable())
    }

    @Test
    fun `send everything ignores the amount`() {
        val model = viewModel(GroupSendUIState.Ready(localChatRoom, wallet, walletState, reserved = emptySet()))
        model.addressField.setTextAndPlaceCursorAtEnd("bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080")
        model.feerateField.setTextAndPlaceCursorAtEnd("4")
        model.sendEverything.value = true

        val built = assertIs<GroupSpendBuilder.Result.Built>(model.preview())
        assertEquals(Satoshi(100_000), built.amount + built.fee)
        assertNull(built.change)
    }

    // ---- Funding a descendant's guarded wallet ---------------------------------

    private val descendantRoomId = "c".repeat(64)
    private val descendant: GroupWalletDerivation.Account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("decaf".padStart(64, '0')))).publicKey().toString(), Chain.Regtest,
    )
    private val guarded = GuardianManager.GuardedWallet(
        ancestorChatRoomId = chatRoomId,
        descendantChatRoomId = descendantRoomId,
        statement = Event("1".repeat(64), descendantRoomId, 1_700_000_000, GroupWalletStatementEvent.KIND, emptyArray(), "", ""),
        account = descendant,
        certificate = Event("2".repeat(64), chatRoomId, 1_700_000_000, 0, arrayOf(arrayOf("name", "Savings")), "", ""),
        lineage = listOf(descendantRoomId),
    )

    /** The descendant's receive branch as Electrum reports it: its first ten addresses watched, [paid] of them used. */
    private fun descendantState(paid: Set<Int> = emptySet()): GroupWalletState {
        val receive = (0 until 10).associate { index ->
            GroupWalletDerivation.derive(descendant, GroupWalletDerivation.RECEIVE, index.toLong()).address(Chain.Regtest) to
                WalletState.AddressState(WalletState.AddressMeta.Derived(index), alreadyUsed = index in paid, utxos = emptyList())
        }
        return GroupWalletState(descendant, WalletState(receive), WalletState.empty, tipHeight = 110)
    }

    private fun fundingForm(signing: FrostSigningRepository, fundingState: Flow<GroupWalletState?>): GroupSendViewModel {
        val ready = GroupSendUIState.Ready(localChatRoom, wallet, walletState = null, reserved = emptySet(), funding = FundingTarget(guarded, walletState = null))
        return viewModel(ready, signing = signing).apply {
            follow(ready, flowOf(walletState), fundingState)
            feerateField.setTextAndPlaceCursorAtEnd("4")
            amountField.setTextAndPlaceCursorAtEnd("30,000")
        }
    }

    @Test
    fun `a funding pays two past the descendant's current address, and says so in the PSBT`() {
        val signing = RecordingSigning()
        val model = fundingForm(signing, flowOf(descendantState()))

        val built = eventually { model.preview() as? GroupSpendBuilder.Result.Built }
        assertEquals(2L, built.funded?.derived?.index)
        assertEquals(GroupWalletDerivation.derive(descendant, GroupWalletDerivation.RECEIVE, 2).pubkeyScript, built.psbt.global.tx.txOut[0].publicKeyScript)
        assertEquals(setOf(2L), GroupSpend.claimedFundingIndices(built.psbt, descendant))

        // The address field plays no part: whatever is typed there, the funding goes where it goes.
        model.addressField.setTextAndPlaceCursorAtEnd("bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080")
        assertEquals(built.psbt, assertIs<GroupSpendBuilder.Result.Built>(model.preview()).psbt)

        model.propose(onSuccess = {}, onFailure = {})
        runBlocking { withTimeout(5_000) { while (signing.proposed == null) delay(10) } }
        assertEquals(built.psbt, signing.proposed, "proposed as the room's own spend")
    }

    @Test
    fun `an open funding moves the next one on`() {
        val signing = Reserving()
        val model = fundingForm(signing, flowOf(descendantState()))
        assertEquals(2L, eventually { (model.preview() as? GroupSpendBuilder.Result.Built)?.funded }.derived.index)

        signing.heldFunding = setOf(2L)
        signing.transactions.value = listOf(
            GroupTransaction(frostSigningSessionId = "theirs", chatRoomId = chatRoomId, chain = "regtest", account = 0, psbt = "", txid = "t".repeat(64))
        )
        assertEquals(3L, eventually { (model.preview() as? GroupSpendBuilder.Result.Built)?.funded?.takeIf { it.derived.index != 2L } }.derived.index)
    }

    @Test
    fun `a payment to the address while the form is open moves it on`() {
        val chain = MutableStateFlow(descendantState())
        val model = fundingForm(RecordingSigning(), chain)
        assertEquals(2L, eventually { (model.preview() as? GroupSpendBuilder.Result.Built)?.funded }.derived.index)

        chain.value = descendantState(paid = setOf(2))
        assertEquals(3L, eventually { (model.preview() as? GroupSpendBuilder.Result.Built)?.funded?.takeIf { it.derived.index != 2L } }.derived.index)
    }

    @Test
    fun `nothing is built while the descendant's addresses are not known, or every one is held`() {
        val waiting = fundingForm(RecordingSigning(), flowOf(null))
        val state = eventually { (waiting.groupSendUIState as GroupSendUIState.Ready).takeIf { it.walletState != null } }
        assertNull(waiting.preview())
        assertEquals(false, state.funding?.allHeld)

        val signing = Reserving().apply { heldFunding = (2L until 10L).toSet() }
        val full = fundingForm(signing, flowOf(descendantState()))
        val held = eventually { (full.groupSendUIState as GroupSendUIState.Ready).takeIf { it.funding?.walletState != null } }
        assertEquals(true, held.funding?.allHeld)
        assertNull(full.preview())
    }

    @Test
    fun `a route naming a source and a destination is not opened, nor one from the room's own wallet`() {
        var asked = 0
        val chat = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(identifier: String) = localChatRoom
            override suspend fun canSign(chatRoomId: String) = true
            override suspend fun groupWallet(chatRoomId: String, chain: Chain) = wallet
            override suspend fun guardedWallets(chatRoomId: String): List<GuardianManager.GuardedWallet> { asked++; return emptyList() }
        }
        val both = GroupSendViewModel("a".repeat(64), chatRoomId, Chain.Regtest, GroupSendUIState.Loading, chat, RecordingSigning(), wallets = wallets,
            guardedChatRoomId = descendantRoomId, fundChatRoomId = descendantRoomId).apply { initiate() }
        runBlocking { withTimeout(5_000) { while (both.groupSendUIState == GroupSendUIState.Loading) delay(10) } }
        assertEquals(GroupSendUIState.Unavailable, both.groupSendUIState)
        assertEquals(0, asked, "refused before either wallet is looked for")

        val unknown = GroupSendViewModel("a".repeat(64), chatRoomId, Chain.Regtest, GroupSendUIState.Loading, chat, RecordingSigning(), wallets = wallets,
            fundChatRoomId = descendantRoomId).apply { initiate() }
        runBlocking { withTimeout(5_000) { while (unknown.groupSendUIState == GroupSendUIState.Loading) delay(10) } }
        assertEquals(GroupSendUIState.Unavailable, unknown.groupSendUIState)
        // The room's own wallet signs only payouts, which this form never builds: refused before
        // any guarded wallet is looked for. docs/payouts-only.md, Phase 6.
        assertEquals(0, asked)

        val own = GroupSendViewModel("a".repeat(64), chatRoomId, Chain.Regtest, GroupSendUIState.Loading, chat, RecordingSigning(), wallets = wallets).apply { initiate() }
        runBlocking { withTimeout(5_000) { while (own.groupSendUIState == GroupSendUIState.Loading) delay(10) } }
        assertEquals(GroupSendUIState.Unavailable, own.groupSendUIState)
    }

    @Test
    fun `a member's payout address starts the form`() {
        // docs/group-payout-destination.md, Phase 7: Pay onchain on a member's sheet.
        val address = "bcrt1p" + "q".repeat(58)
        val viewModel = GroupSendViewModel("a".repeat(64), chatRoomId, Chain.Regtest, GroupSendUIState.Loading,
            ChatRepository.NO_OP_CHAT_REPOSITORY, RecordingSigning(), wallets = wallets, initialAddress = address)
        assertEquals(address, viewModel.addressField.text.toString())
        assertEquals("", viewModel(GroupSendUIState.Loading).addressField.text.toString())
    }
}
