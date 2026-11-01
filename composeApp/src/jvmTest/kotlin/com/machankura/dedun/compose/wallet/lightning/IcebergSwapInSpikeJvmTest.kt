package com.machankura.dedun.compose.wallet.lightning

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OP_CHECKSEQUENCEVERIFY
import fr.acinq.bitcoin.OP_CHECKSIGVERIFY
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.lightning.crypto.IcebergSigner
import fr.acinq.lightning.transactions.SwapInProtocol
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 0, spike 1 of `docs/group-lightning-swap-in.md`: can an Iceberg group be the user side of a
 * Phoenix swap-in address?
 *
 * A swap-in output differs from a channel's funding output in three ways, and each is a way for the
 * group's share to be well-formed and still not combine: the keys are aggregated `[user, server]` in
 * that order rather than `Scripts.sort`ed, the taproot tweak commits to the refund leaf
 * (`ScriptPathTweak`) rather than `KeyPathTweak`, and the sighash covers every spent output. So this
 * builds the swap-in output lightning-kmp builds, signs its key path with a 2-of-4 group and a stock
 * server, and asks bitcoin's own script interpreter whether the spend is valid -- with two negative
 * controls, because a check that cannot fail proves nothing.
 */
class IcebergSwapInSpikeJvmTest {
    private fun key(hex: String) = PrivateKey(ByteVector32(Hex.decode(hex.padStart(64, '0'))))

    private val group = IcebergSigner.keygen(n = 4, t = 2, seed = ByteVector32(Hex.decode("5a".repeat(32))))
    private val server = key("5e7e7")
    private val refund = key("7ef0d") // stands in for the group's FROST refund key; only its public half matters here
    private val refundDelay = 144
    private val protocol = SwapInProtocol(group.groupPublicKey, server.publicKey(), refund.publicKey(), refundDelay)

    // The coin on the swap-in address, and a transaction spending it with a second, ordinary input beside it,
    // so that a sighash over one spent output instead of all of them would show.
    private val coinTx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(Hex.decode("01".repeat(32)))), 0), 0)), listOf(TxOut(Satoshi(50_000), protocol.pubkeyScript)), 0)
    private val other = key("0e1")
    private val otherTx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(Hex.decode("02".repeat(32)))), 0), 0)), listOf(TxOut(Satoshi(20_000), Script.pay2wpkh(other.publicKey()))), 0)
    private val spentOutputs = listOf(coinTx.txOut[0], otherTx.txOut[0])
    private val unsigned = Transaction(
        2,
        listOf(TxIn(OutPoint(coinTx, 0), 0xfffffffdL), TxIn(OutPoint(otherTx, 0), 0xfffffffdL)),
        listOf(TxOut(Satoshi(69_000), Script.pay2wpkh(key("de57").publicKey()))),
        0,
    )

    /** The refund leaf as `SwapInProtocol` builds it, rebuilt here so the tweak is checked against the address, not copied from it. */
    private val scriptTree = ScriptTree.Leaf(listOf(OP_PUSHDATA(refund.publicKey().xOnly()), OP_CHECKSIGVERIFY, OP_PUSHDATA(Script.encodeNumber(refundDelay)), OP_CHECKSEQUENCEVERIFY))

    private data class Signed(val tx: Transaction, val groupNonce: IndividualNonce)

    /** Signs the swap-in input with the group under [tweak] and [keyOrder], the server as lightning-kmp would, and the other input plainly. */
    private fun sign(tweak: Crypto.TaprootTweak, keyOrder: List<fr.acinq.bitcoin.PublicKey>): Signed {
        val sessionId = ByteVector32(Hex.decode("1abe1".padStart(64, '0')))
        val roundOne = IcebergSigner.roundOne(group, contributors = listOf(1, 2, 3), sessionId = sessionId)
        val keys = listOf(group.groupPublicKey, server.publicKey())
        val (serverSecret, serverNonce) = Musig2.generateNonce(ByteVector32(Hex.decode("5e".repeat(32))), fr.acinq.bitcoin.utils.Either.Left(server), keys, null, null)

        val message = Transaction.hashForSigningTaprootKeyPath(unsigned, 0, spentOutputs, SigHash.SIGHASH_DEFAULT)
        val aggregate = Musig2.aggregateKeys(keyOrder)
        val keyAggCache = IcebergSigner.keyAggCacheFor(keyOrder, aggregate.tweak(tweak)).right!!
        assertTrue(Iceberg.keyAggregationCheck(keyAggCache, keyOrder, group.groupPublicKey), "the cache aggregates the group's key")
        val cosignerNonce = IcebergSigner.cosignerAggregatedNonce(listOf(serverNonce)).right!!
        val groupSig = IcebergSigner.roundTwo(group, signers = listOf(1, 3), sessionId, roundOne, keyAggCache, message, cosignerNonce).right!!.partialSig

        val serverSig = protocol.signSwapInputServer(unsigned, 0, spentOutputs, server, serverSecret, roundOne.publicNonce, serverNonce).right!!
        val witness = protocol.witness(unsigned, 0, spentOutputs, roundOne.publicNonce, serverNonce, groupSig, serverSig).right!!
        val otherSig = unsigned.signInput(1, Script.pay2pkh(other.publicKey()), SigHash.SIGHASH_ALL, otherTx.txOut[0].amount, fr.acinq.bitcoin.SigVersion.SIGVERSION_WITNESS_V0, other)
        val signed = unsigned.updateWitness(0, witness).updateWitness(1, Script.witnessPay2wpkh(other.publicKey(), fr.acinq.bitcoin.ByteVector(otherSig)))
        return Signed(signed, roundOne.publicNonce)
    }

    private fun spends(tx: Transaction): Boolean =
        runCatching { Transaction.correctlySpends(tx, listOf(coinTx, otherTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }.isSuccess

    @Test
    fun `the swap-in address commits to the group's key and the refund leaf, as rebuilt here`() {
        val outputKey = Musig2.aggregateKeys(listOf(group.groupPublicKey, server.publicKey())).outputKey(Crypto.TaprootTweak.ScriptPathTweak(scriptTree.hash())).first
        // A P2TR script is OP_1, a 32-byte push, and the output key.
        assertEquals(outputKey.value.toByteArray().toList(), protocol.serializedPubkeyScript.toByteArray().drop(2))
    }

    @Test
    fun `a 2-of-4 group signs a swap-in input's key path with a stock server, and bitcoin accepts it`() {
        val signed = sign(Crypto.TaprootTweak.ScriptPathTweak(scriptTree.hash()), listOf(group.groupPublicKey, server.publicKey()))
        assertTrue(spends(signed.tx), "the swap-in input is spent by the group's and the server's signatures")
    }

    @Test
    fun `under the channel's key-path tweak the same shares do not spend it`() {
        val signed = sign(Crypto.TaprootTweak.KeyPathTweak, listOf(group.groupPublicKey, server.publicKey()))
        assertFalse(spends(signed.tx), "a share under the wrong tweak is well-formed and spends nothing")
    }

    @Test
    fun `with the keys in the other order, the same shares do not spend it either`() {
        // Scripts.sort, the channel's order, puts these two either way round depending on the keys; the reverse
        // of [user, server] is always the wrong one, so it is the control that cannot pass by luck.
        val signed = sign(Crypto.TaprootTweak.ScriptPathTweak(scriptTree.hash()), listOf(server.publicKey(), group.groupPublicKey))
        assertFalse(spends(signed.tx), "a share under the other key order spends nothing")
    }
}
