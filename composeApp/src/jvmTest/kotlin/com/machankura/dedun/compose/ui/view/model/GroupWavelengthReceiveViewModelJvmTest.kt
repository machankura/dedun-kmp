package com.machankura.dedun.compose.ui.view.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthReceiveUIState
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * When the group's Wavelength wallet stops showing a member's deposit request as a QR code --
 * `docs/paid-requests.md`, Phase 3. This device's own records first, the operator's word second.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupWavelengthReceiveViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    /**
     * Clearing the store cancels every `viewModelScope`, so no collector outlives the test.
     * `initiate()` combines six never-ending flows, and a collector still live when the next
     * test class calls `Dispatchers.setMain` fails *that* class with "Main is used concurrently
     * with setting it".
     */
    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private val store = ViewModelStore()
    private var kept = 0

    private fun <T : ViewModel> keep(viewModel: T): T = viewModel.also { store.put("vm-${kept++}", it) }

    private val me = GroupWavelengthUiFixtures.ME
    private val operatorKey: HexKey = "b".repeat(64)
    private val script: HexKey = "51" + "aa".repeat(32)
    private val hash: HexKey = "cd".repeat(32)

    private class Wavelength(
        val wallet: GroupWavelengthWalletManager.Wallet,
        val operator: GroupWavelengthOperatorManager.Operator,
    ) : GroupWavelengthRepository by GroupWavelengthRepository.NO_OP {
        val rows = MutableStateFlow<List<GroupArkDepositAddress>>(emptyList())
        val boarded = MutableStateFlow<List<GroupArkBoarded>>(emptyList())
        val claims = MutableStateFlow<List<GroupArkClaim>>(emptyList())
        val payments = MutableStateFlow<List<GroupArkPayment>>(emptyList())
        val snapshot = MutableStateFlow<GroupWavelengthSnapshot?>(null)

        override suspend fun wallets(chatRoomId: String, chain: Chain) = listOf(wallet)
        override suspend fun isDepositor(chatRoomId: String, chain: Chain, member: HexKey) = true
        override suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long) = operator
        override fun terms(chain: Chain): GroupWavelengthTerms.Terms? = GroupWavelengthTerms.of(Chain.Signet)
        override suspend fun seen(chatRoomId: String): Map<HexKey, Instant> = emptyMap()
        override fun observeDepositAddresses(chatRoomId: String, chain: Chain, wallet: Long, member: HexKey): Flow<List<GroupArkDepositAddress>> = rows
        override fun observeCoins(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkVtxo>> = MutableStateFlow(emptyList())
        override fun observeBoarded(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkBoarded>> = boarded
        override fun observeClaims(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkClaim>> = claims
        override fun observeArkPayments(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkPayment>> = payments
        override fun observeSnapshot(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupWavelengthSnapshot?> = snapshot
    }

    private val chat = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun getChatRoomByIdentifier(identifier: String): LocalChatRoom? = GroupWavelengthUiFixtures.localChatRoom
    }

    private fun receiving(repository: Wavelength): GroupWavelengthReceiveViewModel {
        val viewModel = keep(GroupWavelengthReceiveViewModel(me, GroupWavelengthUiFixtures.chatRoomId, Chain.Signet, chat, repository))
        viewModel.initiate()
        runBlocking { withTimeout(5_000) { while (viewModel.uiState !is GroupWavelengthReceiveUIState.Loaded) delay(10) } }
        return viewModel
    }

    private fun repository() = Wavelength(
        wallet = GroupWavelengthUiFixtures.wallet(),
        operator = GroupWavelengthUiFixtures.operator(operatorKey),
    )

    private fun loaded(viewModel: GroupWavelengthReceiveViewModel) =
        assertIs<GroupWavelengthReceiveUIState.Loaded>(viewModel.uiState)

    private fun settle() = runBlocking { delay(100) }

    private fun addressRow() = GroupArkDepositAddress(
        wallet = "w", requestId = "r1", requester = me, requestedAt = Instant.fromEpochSeconds(1),
        address = "tb1qaddress", script = script,
    )

    private fun invoiceRow() = GroupArkDepositAddress(
        wallet = "w", requestId = "r2", requester = me, requestedAt = Instant.fromEpochSeconds(2),
        invoiceAmountSat = 5_000, invoice = "lnbcrt5000", paymentHash = hash,
    )

    /**
     * A boarding coin this device signed into a round is how it knows the address was paid. There
     * is no operator fallback on this rail, so it is always the device's own word.
     */
    @Test
    fun `a boarded coin on the shown address marks it paid`() {
        val repository = repository()
        repository.rows.value = listOf(addressRow())
        val viewModel = receiving(repository)
        assertNull(loaded(viewModel).addressPaid)

        repository.boarded.value = listOf(GroupArkBoarded("w", "tx:0", "c1", 7_000, script))
        settle()
        val paid = loaded(viewModel).addressPaid
        assertEquals(Satoshi(7_000), paid?.amount)
        assertEquals(false, paid?.onTheOperatorsWord)
    }

    @Test
    fun `a boarded coin on another script says nothing about this address`() {
        val repository = repository()
        repository.rows.value = listOf(addressRow())
        val viewModel = receiving(repository)
        repository.boarded.value = listOf(GroupArkBoarded("w", "tx:0", "c1", 7_000, "51" + "bb".repeat(32)))
        settle()
        assertNull(loaded(viewModel).addressPaid)
    }

    /** A claim this device signed carries the amount it believed arrived. */
    @Test
    fun `a claim on the invoice's hash marks it paid, flatly`() {
        val repository = repository()
        repository.rows.value = listOf(invoiceRow())
        val viewModel = receiving(repository)
        assertNull(loaded(viewModel).invoicePaid)

        repository.claims.value = listOf(GroupArkClaim("w", "ark1", hash, 5_000))
        settle()
        val paid = loaded(viewModel).invoicePaid
        assertEquals(Satoshi(5_000), paid?.amount)
        assertEquals(false, paid?.onTheOperatorsWord, "this device signed the claim itself")
    }

    /** A filed payment says it happened and nothing about how much, which is what a null amount is for. */
    @Test
    fun `a filed payment marks the invoice paid with no figure`() {
        val repository = repository()
        repository.rows.value = listOf(invoiceRow())
        val viewModel = receiving(repository)
        repository.payments.value = listOf(GroupArkPayment("w", hash, "tx1"))
        settle()
        val paid = loaded(viewModel).invoicePaid
        assertEquals(null, paid?.amount)
        assertEquals(false, paid?.onTheOperatorsWord)
    }

    /**
     * With no record of its own, the screen takes the operator's -- and says so. A "pending" line
     * is no help: one exists from the moment the invoice is made.
     */
    @Test
    fun `only the operator's word is named as the operator's word`() {
        val repository = repository()
        repository.rows.value = listOf(invoiceRow())
        val viewModel = receiving(repository)

        repository.snapshot.value = snapshotWith(status = "pending")
        settle()
        assertNull(loaded(viewModel).invoicePaid, "a pending line exists before anybody has paid")

        repository.snapshot.value = snapshotWith(status = "complete")
        settle()
        val paid = loaded(viewModel).invoicePaid
        assertEquals(Satoshi(4_900), paid?.amount, "what the operator says arrived, not what was asked")
        assertEquals(true, paid?.onTheOperatorsWord)
        assertEquals(operatorKey.take(8), loaded(viewModel).operatorName)
    }

    @Test
    fun `the operator claiming another hash is not this invoice`() {
        val repository = repository()
        repository.rows.value = listOf(invoiceRow())
        val viewModel = receiving(repository)
        repository.snapshot.value = snapshotWith(status = "complete", paymentHash = "ef".repeat(32))
        settle()
        assertNull(loaded(viewModel).invoicePaid)
    }

    private fun snapshotWith(status: String, paymentHash: HexKey = hash) = GroupWavelengthSnapshot(
        wallet = "w", term = 1, sentAt = 1, tip = 100, confirmedSat = 0,
        activity = listOf(GroupWavelengthSnapshot.Line(id = "l1", kind = "receive", status = status, amountSat = 4_900, paymentHash = paymentHash)),
    )
}
