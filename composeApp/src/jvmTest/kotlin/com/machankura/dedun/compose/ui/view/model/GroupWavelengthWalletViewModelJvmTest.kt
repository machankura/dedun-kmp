package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.ME
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.OTHER
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.key
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.wallet
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthLedger
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms
import fr.acinq.bitcoin.Chain
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which of its states the Wavelength wallet screen puts a room in, on the way from nothing to an elected operator. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupWavelengthWalletViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Wavelength(
        val onchain: Boolean = true,
        val unused: IcebergKeySession? = null,
        val running: IcebergKeySession? = null,
        val thresholds: List<Int> = listOf(2),
        val wallet: GroupWavelengthWalletManager.Wallet? = null,
        val refusal: GroupWavelengthWalletManager.Refusal? = null,
        val operator: GroupWavelengthOperatorManager.Operator? = null,
        val pinned: GroupWavelengthTerms.Terms? = GroupWavelengthTerms.of(Chain.Signet),
        val live: GroupWavelengthTerms.Terms? = null,
        val opening: String? = null,
        val coins: List<GroupArkVtxo> = emptyList(),
    ) : GroupWavelengthRepository by GroupWavelengthRepository.NO_OP {
        override suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain) = onchain
        override suspend fun ceremonyThresholds(chatRoomId: String) = thresholds
        override fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>> = flowOf(listOfNotNull(running))
        override suspend fun unusedKey(chatRoomId: String) = unused
        override suspend fun openingSession(chatRoomId: String, keyId: String) = opening
        override suspend fun wallets(chatRoomId: String, chain: Chain) = listOfNotNull(wallet)
        override suspend fun refusal(chatRoomId: String, chain: Chain) = refusal
        override suspend fun operator(chatRoomId: String, chain: Chain, wallet: Long) = operator
        override fun terms(chain: Chain) = pinned
        override suspend fun liveTerms(chain: Chain) = live
        override fun observeCoins(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkVtxo>> = flowOf(coins)
    }

    private val chat = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun getChatRoomByIdentifier(identifier: String): LocalChatRoom? = GroupWavelengthUiFixtures.localChatRoom
    }

    private fun settled(repository: GroupWavelengthRepository, me: String = ME, identity: Identity? = null, canRunHere: Boolean = true): GroupWavelengthWalletUIState {
        val viewModel = GroupWavelengthWalletViewModel(me, GroupWavelengthUiFixtures.chatRoomId, Chain.Signet, GroupWavelengthWalletUIState.Loading, MutableStateFlow(identity), chat, repository, canRunHere)
        viewModel.initiate()
        return runBlocking {
            withTimeout(5_000) {
                while (viewModel.uiState == GroupWavelengthWalletUIState.Loading) delay(10)
                delay(50) // The coins' first answer.
                viewModel.uiState
            }
        }
    }

    @Test
    fun `without an onchain wallet there is nothing to leave to`() {
        assertIs<GroupWavelengthWalletUIState.NeedsOnchainWallet>(settled(Wavelength(onchain = false, unused = key, wallet = wallet())))
    }

    @Test
    fun `with no key the group is asked to make one, or told it is too small`() {
        assertTrue(assertIs<GroupWavelengthWalletUIState.NeedsKey>(settled(Wavelength())).canMakeKey)
        assertTrue(!assertIs<GroupWavelengthWalletUIState.NeedsKey>(settled(Wavelength(thresholds = emptyList()))).canMakeKey)
        val running = key.copy(stage = IcebergRitualStage.COLLECTING_REVEALS, share = null)
        assertEquals(running, assertIs<GroupWavelengthWalletUIState.NeedsKey>(settled(Wavelength(running = running))).ceremony)
    }

    @Test
    fun `a device with no key to send the seed with can't propose`() {
        val state = assertIs<GroupWavelengthWalletUIState.NotOpened>(settled(Wavelength(unused = key)))
        assertEquals(GroupWavelengthWalletUIState.Blocked.NO_KEY, state.blocked)
    }

    @Test
    fun `a proposal already being signed is led to, rather than a second one started`() {
        assertEquals("session", assertIs<GroupWavelengthWalletUIState.NotOpened>(settled(Wavelength(unused = key, opening = "session"))).openingSession)
    }

    @Test
    fun `a seed still on its way isn't a refusal, and a refusal says why`() {
        assertIs<GroupWavelengthWalletUIState.WaitingForSeed>(settled(Wavelength(unused = key, refusal = GroupWavelengthWalletManager.Refusal.WAITING_FOR_SEED)))
        val refused = assertIs<GroupWavelengthWalletUIState.Refused>(settled(Wavelength(unused = key, refusal = GroupWavelengthWalletManager.Refusal.KEYS_DISAGREE)))
        assertEquals(GroupWavelengthWalletManager.Refusal.KEYS_DISAGREE, refused.why)
    }

    @Test
    fun `an open wallet says who runs it, whether that is this device, and whether this device signs for it at all`() {
        val nobody = assertIs<GroupWavelengthWalletUIState.Opened>(settled(Wavelength(wallet = wallet())))
        assertTrue(nobody.isParticipant && !nobody.isOperator)
        assertNull(nobody.operator)
        assertTrue(assertIs<GroupWavelengthWalletUIState.Opened>(settled(Wavelength(wallet = wallet(), operator = GroupWavelengthUiFixtures.operator(ME)))).isOperator)
        assertTrue(!assertIs<GroupWavelengthWalletUIState.Opened>(settled(Wavelength(wallet = wallet(), operator = GroupWavelengthUiFixtures.operator(OTHER)))).isOperator)
        assertTrue(!assertIs<GroupWavelengthWalletUIState.Opened>(settled(Wavelength(wallet = wallet()), me = "e".repeat(64))).isParticipant)
        assertTrue(!assertIs<GroupWavelengthWalletUIState.Opened>(settled(Wavelength(wallet = wallet()), canRunHere = false)).canRunHere)
    }

    @Test
    fun `the balance is this device's own record, whatever anyone says`() {
        val coins = listOf(
            GroupArkVtxo("w", "a:0", 10_000, settled = true, expiresAtHeight = 500),
            GroupArkVtxo("w", "b:0", 2_000, settled = true, reservedBy = "r", expiresAtHeight = 400),
            GroupArkVtxo("w", "c:0", 300),
            GroupArkVtxo("w", "d:0", 7_777, settled = true, spent = true, expiresAtHeight = 1),
        )
        val ledger = assertIs<GroupWavelengthWalletUIState.Opened>(settled(Wavelength(wallet = wallet(), coins = coins))).ledger
        assertEquals(12_000, ledger.settledSat)
        assertEquals(2_000, ledger.heldSat)
        assertEquals(300, ledger.pendingSat)
        assertEquals(400, ledger.earliestExpiryHeight)
    }

    @Test
    fun `a new key beside a wallet can open another, and the old one says what's still in it`() {
        val old = wallet(number = 0)
        val new = wallet(number = 1)
        val repository = object : GroupWavelengthRepository by Wavelength(unused = key, pinned = null) {
            override suspend fun wallets(chatRoomId: String, chain: Chain) = listOf(old, new)
            override suspend fun departed(wallet: GroupWavelengthWalletManager.Wallet) = listOf("f".repeat(64))
            override fun observeCoins(chatRoomId: String, chain: Chain, wallet: Long): Flow<List<GroupArkVtxo>> =
                flowOf(if (wallet == 0L) listOf(GroupArkVtxo("w", "a:0", 5_000, settled = true)) else emptyList())
            override fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>> = flowOf(emptyList())
        }
        val state = assertIs<GroupWavelengthWalletUIState.Opened>(settled(repository))
        assertEquals(1L, state.wallet.statement.wallet, "the newest is the wallet")
        assertEquals(listOf("f".repeat(64)), state.departed)
        assertEquals(5_000, state.earlier.single().settledSat)
        assertTrue(!state.canOpenNew, "no way to propose from a device with no key")
    }

    @Test
    fun `a coin with no count of this device's goes by what the daemon says, and one with a count never does`() {
        val counted = GroupArkVtxo("w", "a:0", 1, settled = true, expiresAtHeight = 500)
        val claimed = GroupArkVtxo("w", "b:0", 1, settled = true)
        assertEquals(500, GroupWavelengthLedger.earliestExpiry(listOf(counted), mapOf("a:0" to 1L)), "the operator can't talk this device out of its own count")
        assertEquals(300, GroupWavelengthLedger.earliestExpiry(listOf(counted, claimed), mapOf("b:0" to 300L)))
        assertEquals(500, GroupWavelengthLedger.earliestExpiry(listOf(counted, claimed), emptyMap()))
        assertEquals(null, GroupWavelengthLedger.earliestExpiry(listOf(claimed), emptyMap()))
    }
}
