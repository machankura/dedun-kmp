package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.electrum.WalletState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A guarded wallet reading its own history for spends its guardians made --
 * the child's only notice of one, since nobody in its room signed it.
 *
 * The witnesses here are the right shape and carry no valid signature: what is
 * under test is which leaf a spend used, which the chain records whether or not
 * anybody checks the signature again. `GuardianSigningJvmTest` spends for real.
 */
class GuardianSpendNoticeJvmTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    private fun key(seed: Int): String = PrivateKey(ByteVector32(ByteArray(32) { seed.toByte() })).publicKey().toString()

    private val chain = Chain.Regtest
    private val parentRoom = SharedKeyDerivation.marmotGroupId(key(1))
    private val childKey = key(2)
    private val childRoom = SharedKeyDerivation.marmotGroupId(childKey)
    private val grant = GuardianDerivation.grant(key(1), parentRoom, emptyList(), childRoom)
    private val account = GroupWalletDerivation.account(childKey, chain, guardians = grant)
    private val coin = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
    private val elsewhere = ByteVector(Script.write(Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { 9 })).publicKey())))

    /** The wallet after its one coin has been spent: the address used, nothing left on it. */
    private val spentState = GroupWalletState(
        account,
        WalletState(mapOf(coin.address(chain) to WalletState.AddressState(WalletState.AddressMeta.Derived(0), alreadyUsed = true, utxos = emptyList()))),
        WalletState(emptyMap()),
        tipHeight = 200,
    )

    private fun spending(funding: Transaction, witness: ScriptWitness) = Transaction(
        2, listOf(TxIn(OutPoint(funding, 0), ByteVector.empty, GuardianDerivation.DEFAULT_DELAY, witness)),
        listOf(TxOut(Satoshi(49_000), elsewhere)), 0,
    )

    @Test
    fun `a spend through the parent's leaf is read off the address's history`(): Unit = runBlocking {
        val electrum = FakeElectrum()
        electrum.pay(coin.pubkeyScript, Satoshi(50_000))
        val funding = electrum.fundingOf(coin.pubkeyScript)
        val leaf = GuardianDerivation.leaves(grant, chain, 0, coin.change, coin.index)[0]
        val taken = spending(funding, Script.witnessScriptPathPay2tr(coin.internalKey, leaf, ScriptWitness(listOf(ByteVector(ByteArray(64)))), coin.tree!!))
        electrum.spendWith(coin.pubkeyScript, taken)

        val wallets = GroupOnchainWallets(electrum, scope)
        assertEquals(
            listOf(GuardianManager.Taken(taken.txid, grant[0], Satoshi(50_000))),
            wallets.guardianSpends(spentState),
        )
    }

    @Test
    fun `the group's own key-path spend names no guardian`(): Unit = runBlocking {
        val electrum = FakeElectrum()
        electrum.pay(coin.pubkeyScript, Satoshi(50_000))
        val own = spending(electrum.fundingOf(coin.pubkeyScript), Script.witnessKeyPathPay2tr(fr.acinq.bitcoin.ByteVector64.Zeroes))
        electrum.spendWith(coin.pubkeyScript, own)

        assertEquals(emptyList(), GroupOnchainWallets(electrum, scope).guardianSpends(spentState))
    }
}
