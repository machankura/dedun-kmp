package com.machankura.dedun.compose.ui.view.model

import androidx.lifecycle.viewModelScope
import com.machankura.dedun.compose.database.model.MemberWalletSpend
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.ui.view.state.MemberWalletUIState
import com.machankura.dedun.compose.wallet.FakeElectrum
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
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
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The wallet screen following a spend the member sent alone, which has no session for `GroupTransactionWatcher` to follow. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
class MemberWalletViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val me = "a".repeat(64)
    private val walletId = "b".repeat(64)
    private val account = GroupWalletDerivation.account(PrivateKey(ByteVector32("11".repeat(32))).publicKey().toString(), Chain.Regtest)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val electrum = FakeElectrum()

    private val tx = Transaction(
        2, listOf(TxIn(OutPoint(TxId(ByteVector32("cc".repeat(32))), 0), ByteVector.empty, 0xfffffffdL)),
        listOf(TxOut(Satoshi(9_000), ByteVector("0014" + "00".repeat(20)))), 0,
    )
    private val spend = MemberWalletSpend(txid = tx.txid.toString(), walletId = walletId, psbt = "", signedTransaction = Hex.encode(Transaction.write(tx)))

    /** What the screen last told the repository the spend's confirmations were. */
    private val recorded = AtomicReference<Int?>(null)

    private val repository = object : MemberWalletRepository by MemberWalletRepository.NO_OP {
        override suspend fun walletOf(ownerPublicKey: HexKey, walletId: String) = MemberWalletManager.MemberWalletRow(
            walletId = walletId, groupChatRoomId = "room", groupRoom = null, label = null, chain = Chain.Regtest,
            account = account, state = MemberWalletManager.WalletState.OPEN, isMember = true, stored = null,
        )

        override fun observeSpends(walletId: String): Flow<List<MemberWalletSpend>> = flowOf(listOf(spend))

        override suspend fun recordChain(txid: String, confirmations: Int?, broadcastAt: Instant?) {
            recorded.store(confirmations)
        }
    }

    private fun recordedBecomes(confirmations: Int) = runBlocking {
        withTimeout(10_000) { while (recorded.load() != confirmations) delay(10) }
    }

    @Test
    fun `a spend mined after the connection opened is recorded at its real depth`() {
        electrum.connect(height = 1_000)
        electrum.sent(tx, height = 0)
        val viewModel = MemberWalletViewModel(me, walletId, MemberWalletUIState.Loading, repository, GroupOnchainWallets(electrum, scope))
        try {
            viewModel.initiate()
            recordedBecomes(0)

            electrum.sent(tx, height = 1_003)
            runBlocking { electrum.mine(height = 1_010) }
            assertEquals(-2, runBlocking { electrum.getConfirmations(tx) }, "which is why the screen doesn't ask it")
            recordedBecomes(8)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
