package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.GroupChainClients
import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.ui.view.state.GroupWalletAddressesUIState
import com.machankura.dedun.compose.wallet.FakeElectrum
import com.machankura.dedun.compose.wallet.GroupAddress
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlin.test.assertNull
import kotlin.time.Instant

/** Which of its states the address list is in, and that it follows the chain while it is open. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupWalletAddressesViewModelJvmTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val chatRoomId = "d4c3b2a1".repeat(8)

    private val account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(),
        Chain.Regtest,
    )

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

    private fun repository(opened: GroupWallet?) =
        object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun groupWallet(chatRoomId: String, chain: Chain): GroupWallet? = opened
        }

    /** This profile, a depositor: its addresses are the ones offered (docs/member-deposits.md, Phase 5). */
    private val me = "a1".repeat(32)
    private val myBranch = DepositBranch.of(chatRoomId, me)

    private fun walletsOn(electrum: FakeElectrum, depositors: Map<String, Long> = mapOf(me to myBranch)) =
        GroupOnchainWallets(GroupChainClients.single(electrum), scope) { _, _ -> depositors }

    private fun viewModel(opened: GroupWallet?, wallets: GroupOnchainWallets = walletsOn(FakeElectrum())) =
        GroupWalletAddressesViewModel(
            activeUserPublicKey = me,
            chatRoomId = chatRoomId,
            chain = Chain.Regtest,
            initialUIState = GroupWalletAddressesUIState.Loading,
            chatRepository = repository(opened),
            wallets = wallets,
        ).apply { initiate() }

    private fun GroupWalletAddressesViewModel.until(predicate: (GroupWalletAddressesUIState) -> Boolean): GroupWalletAddressesUIState =
        runBlocking {
            withTimeout(10_000) {
                while (!predicate(uiState)) delay(10)
                uiState
            }
        }

    private fun receive(index: Long) = GroupWalletDerivation.derive(account, myBranch, index)
    private fun legacy(index: Long) = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, index)

    @Test
    fun `a profile the group has not opened deposits for is offered no address, and sees what was paid before`() {
        val electrum = FakeElectrum()
        val addresses = viewModel(opened = wallet, wallets = walletsOn(electrum, depositors = emptyMap()))
        runBlocking { electrum.pay(legacy(0).pubkeyScript, Satoshi(5_000)) }
        val state = addresses.until { (it as? GroupWalletAddressesUIState.Loaded)?.legacy?.isNotEmpty() == true } as GroupWalletAddressesUIState.Loaded
        assertNull(state.receive)
        assertEquals(false, state.isDepositor)
        assertEquals(listOf(GroupAddress.Status.USED), state.legacy.map { it.status })
    }

    @Test
    fun `a room with no wallet has no addresses to list`() {
        assertIs<GroupWalletAddressesUIState.Error>(viewModel(opened = null).until { it != GroupWalletAddressesUIState.Loading })
    }

    @Test
    fun `the list follows a payment while the screen is open`() {
        val electrum = FakeElectrum()
        val addresses = viewModel(opened = wallet, wallets = walletsOn(electrum))

        addresses.until { (it as? GroupWalletAddressesUIState.Loaded)?.receive != null }
        runBlocking { electrum.pay(receive(0).pubkeyScript, Satoshi(25_000)) }

        val state = addresses.until { loaded ->
            (loaded as? GroupWalletAddressesUIState.Loaded)?.receive?.any { it.status == GroupAddress.Status.USED } == true
        }
        val current = assertIs<GroupWalletAddressesUIState.Loaded>(state).receive!!.single { it.status == GroupAddress.Status.CURRENT }
        assertEquals(1L, current.derivation.index)
    }

    private fun GroupWalletAddressesViewModel.untilListed(index: Long, status: GroupAddress.Status) = until { loaded ->
        (loaded as? GroupWalletAddressesUIState.Loaded)?.receive?.any { it.derivation.index == index && it.status == status } == true
    }

    @Test
    fun `the sheet closes once when the address it shows is paid`() {
        val electrum = FakeElectrum()
        val addresses = viewModel(opened = wallet, wallets = walletsOn(electrum))
        addresses.untilListed(1L, GroupAddress.Status.NEXT)

        var paid = 0
        addresses.show(1L) { paid++ }
        runBlocking { electrum.pay(receive(1).pubkeyScript, Satoshi(25_000)) }
        addresses.untilListed(1L, GroupAddress.Status.USED)
        runBlocking { withTimeout(10_000) { while (addresses.shown != null) delay(10) } }

        assertNull(addresses.shown)
        assertEquals(1, paid)
    }

    @Test
    fun `the sheet stays open when some other address is paid`() {
        val electrum = FakeElectrum()
        val addresses = viewModel(opened = wallet, wallets = walletsOn(electrum))
        addresses.untilListed(1L, GroupAddress.Status.NEXT)

        var paid = 0
        addresses.show(1L) { paid++ }
        runBlocking { electrum.pay(receive(0).pubkeyScript, Satoshi(25_000)) }
        addresses.untilListed(1L, GroupAddress.Status.CURRENT)

        assertEquals(1L, addresses.shown, "the address it shows became the current one, which is still unused")
        assertEquals(0, paid)
    }
}
