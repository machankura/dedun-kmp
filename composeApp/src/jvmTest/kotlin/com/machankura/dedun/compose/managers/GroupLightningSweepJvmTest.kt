package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.machankura.dedun.compose.wallet.lightning.WalletWire
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.channel.GroupHeldOutputs
import fr.acinq.lightning.crypto.ChannelKeys
import fr.acinq.lightning.crypto.CommitmentPublicKeys
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sweeping what a closed channel left the group into its onchain wallet (`docs/group-lightning-wallet.md`,
 * Phase 13). The outputs are built with lightning-kmp's own scripts, as a published commitment carries
 * them; the group signs them with FROST, every member checking the sweep first; and the script
 * interpreter judges the result.
 */
class GroupLightningSweepJvmTest {
    private val fixture = GroupLightningFixture()
    private val lsp = "02" + KeyPair().pubKey.toHexKey()

    @AfterTest
    fun close() = fixture.close()

    private suspend fun opened(): Pair<List<GroupLightningFixture.Device>, GroupLightningWalletManager.Wallet> {
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp).id)
        return all to GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
    }

    private fun key() = PrivateKey(randomBytes32()).publicKey()
    private fun keys(remotePayment: fr.acinq.bitcoin.PublicKey = key(), localDelayed: fr.acinq.bitcoin.PublicKey = key()) =
        CommitmentPublicKeys(localDelayed, remotePayment, key(), key(), key())

    /** Our main output in the counterparty's commitment, under the payment basepoint. */
    private fun toRemote(wallet: GroupLightningWalletManager.Wallet, amount: Long): GroupHeldOutputs.Output {
        val basepoint = wallet.basepoints.payment.publicKey
        val commitment = keys(remotePayment = basepoint)
        val leaf = Scripts.Taproot.toRemoteScriptTree(commitment)
        val outPoint = OutPoint(TxId(randomBytes32()), 1)
        return GroupHeldOutputs.Output(GroupHeldOutputs.Kind.TO_REMOTE, outPoint, TxOut(Satoshi(amount), Script.write(Scripts.Taproot.toRemote(commitment))), basepoint, null, basepoint, Scripts.Taproot.NUMS_POINT.xOnly(), leaf, leaf, 1)
    }

    /** Our main output in our own commitment, under the delayed basepoint and a per-commitment point. */
    private fun toLocal(wallet: GroupLightningWalletManager.Wallet, amount: Long, delay: Int = 144): GroupHeldOutputs.Output {
        val basepoint = wallet.basepoints.delayedPayment.publicKey
        val point = key()
        val leafKey = ChannelKeys.remotePerCommitmentPublicKey(basepoint, point)
        val commitment = keys(localDelayed = leafKey)
        val tree = Scripts.Taproot.toLocalScriptTree(commitment, CltvExpiryDelta(delay))
        val outPoint = OutPoint(TxId(randomBytes32()), 0)
        val tweak = Crypto.sha256(point.value + basepoint.value).byteVector32()
        return GroupHeldOutputs.Output(GroupHeldOutputs.Kind.TO_LOCAL, outPoint, TxOut(Satoshi(amount), Script.write(Scripts.Taproot.toLocal(commitment, CltvExpiryDelta(delay)))), basepoint, tweak, leafKey, Scripts.Taproot.NUMS_POINT.xOnly(), tree.localDelayed, tree.scriptTree, delay.toLong())
    }

    @Test
    fun `the group sweeps both kinds of output into its onchain wallet, and the transaction is valid`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val outputs = listOf(toRemote(wallet, 300_000), toLocal(wallet, 500_000))
        val inputs = outputs.map(GroupSweep::inputOf)
        val sweep = assertNotNull(GroupSweep.build(wallet.statement.wallet, inputs, fixture.account, receiveIndex = 0, feeratePerVbyte = 2))
        val session = FrostSigningManager.proposeSweep(all[3].db, all[3].room, all[3].publicKey, sweep, fixture.chain)
        fixture.signSession(all, all[3], session.id)

        all.forEach { d ->
            assertEquals(FrostSigningStage.COMPLETE, d.db.frostSigningSessionDao().getSessionById(session.id)?.stage, "device ${d.index}")
        }
        val signed = Transaction.read(assertNotNull(all[0].db.groupTransactionDao().getBySessionId(session.id)?.signedTransaction))
        Transaction.correctlySpends(signed, outputs.associate { it.outPoint to it.txOut }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertEquals(GroupWalletDerivation.derive(fixture.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript, signed.txOut.single().publicKeyScript)
        assertEquals(listOf(1L, 144L), signed.txIn.map { it.sequence }, "each input waits its leaf's delay")
        val checked = assertNotNull(FrostSigningManager.checkedSweep(all[1].db, session.id))
        assertEquals(800_000 - checked.fee.sat, checked.amount.sat)
        assertTrue(checked.feeratePerVbyte in 1..3, "${checked.feeratePerVbyte} sats per vbyte")

        // A proposed sweep's outputs are not offered again.
        val report = GroupLightningReports.Report(WalletWire.Snapshot("w", 1, wallet.statement.nodeId, 0, emptyList(), emptyList(), heldOutputs = inputs + GroupSweep.inputOf(toRemote(wallet, 10_000))), emptyList())
        assertEquals(1, GroupLightningSweeps.unswept(all[2].db, fixture.roomId, report).size)
    }

    @Test
    fun `a sweep that pays anyone else, or signs under a key that is not the group's, is refused`() = runBlocking<Unit> {
        val (_, wallet) = opened()
        val input = GroupSweep.inputOf(toLocal(wallet, 500_000))
        val good = assertNotNull(GroupSweep.build(wallet.statement.wallet, listOf(input), fixture.account, 0, 2))
        assertIs<GroupSweep.Result.Ok>(GroupSweep.check(good, wallet.basepoints, fixture.account))

        fun refused(sweep: GroupSweep.Sweep, why: String) = assertIs<GroupSweep.Result.Refused>(GroupSweep.check(sweep, wallet.basepoints, fixture.account), why)
        val tx = Transaction.read(good.tx)
        val elsewhere = tx.copy(txOut = listOf(tx.txOut.single().copy(publicKeyScript = ByteVector(Script.write(Script.pay2wpkh(key()))))))
        refused(good.copy(tx = Hex.encode(Transaction.write(elsewhere))), "paying an address that is not the wallet's")
        refused(good.copy(receiveIndices = listOf(1)), "claiming another receive index")
        refused(good.copy(inputs = listOf(input.copy(tweak = null))), "a delayed output with no tweak")
        refused(good.copy(inputs = listOf(input.copy(tweak = ByteVector32(randomBytes32()).toHex()))), "a tweak whose key is not the leaf's")
        refused(good.copy(inputs = listOf(input.copy(basepoint = GroupSweep.PAYMENT))), "the wrong basepoint for the leaf")
        refused(good.copy(inputs = listOf(input.copy(csvDelay = 1))), "a delay the transaction does not wait")
        val greedy = tx.copy(txOut = listOf(tx.txOut.single().copy(amount = Satoshi(100_000))))
        refused(good.copy(tx = Hex.encode(Transaction.write(greedy))), "a fee far over the limit")
    }

    @Test
    fun `the leaf a member rebuilds is the leaf lightning-kmp writes`() = runBlocking<Unit> {
        val (_, wallet) = opened()
        val remote = toRemote(wallet, 1_000)
        assertEquals(remote.leaf.script, GroupSweep.leafScript(wallet.basepoints.payment.publicKey.xOnly(), 1))
        val local = toLocal(wallet, 1_000, delay = 720)
        assertEquals(local.leaf.script, GroupSweep.leafScript(local.leafKey.xOnly(), 720))
    }
}
