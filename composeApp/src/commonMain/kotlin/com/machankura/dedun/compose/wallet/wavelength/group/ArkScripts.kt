package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.secp256k1.Hex

/**
 * The daemon's script shapes, rebuilt from the keys so a co-signer never takes one on trust --
 * `lib/arkscript` in the fork, and the vectors in `group-wavelength-policy-fixtures.json` hold this to it.
 *
 * Bytes rather than script elements: `txscript.ScriptBuilder` pushes a small number as `OP_n`, and these have to come
 * out exactly as it writes them.
 */
internal object ArkScripts {
    /** Every Ark output's internal key, provably unspendable, so only the leaves below can move it. */
    val NUMS: XonlyPublicKey = PublicKey.fromHex("02372f225b3caee8213096de3229ee4335306b07c3c169438461b5d4749884ec65").xOnly()

    private const val OP_CHECKSEQUENCEVERIFY = 0xb2.toByte()
    private const val OP_DROP = 0x75.toByte()
    private const val OP_CHECKSIG = 0xac.toByte()
    private const val OP_CHECKSIGVERIFY = 0xad.toByte()

    private fun push(key: PublicKey): ByteArray = byteArrayOf(32) + key.xOnly().value.toByteArray()

    /** `txscript.ScriptBuilder.AddInt64`: `OP_n` up to 16, otherwise a minimal little-endian push. */
    private fun pushNumber(number: Long): ByteArray = when (number) {
        0L -> byteArrayOf(0)
        in 1L..16L -> byteArrayOf((0x50 + number).toByte())
        else -> Script.encodeNumber(number).toByteArray().let { byteArrayOf(it.size.toByte()) + it }
    }

    /** `<owner> OP_CHECKSIGVERIFY <operator> OP_CHECKSIG`: the leaf a VTXO moves by with the operator. */
    fun collabLeaf(owner: PublicKey, operator: PublicKey): ByteVector =
        ByteVector(push(owner) + OP_CHECKSIGVERIFY + push(operator) + OP_CHECKSIG)

    /** `<key> OP_CHECKSIG <delay> OP_CSV OP_DROP`: the leaf it moves by alone, after a delay. */
    fun timeoutLeaf(key: PublicKey, delay: Long): ByteVector =
        ByteVector(push(key) + OP_CHECKSIG + pushNumber(delay) + OP_CHECKSEQUENCEVERIFY + OP_DROP)

    fun leafHash(script: ByteVector): ByteVector32 = ScriptTree.Leaf(script, Script.TAPROOT_LEAF_TAPSCRIPT).hash()

    private fun pkScriptOf(leaves: List<ByteVector>): ByteVector {
        check(leaves.size == 2) { "these shapes all have two leaves" }
        val (first, second) = leaves.sortedWith { a, b -> compareBytes(a.toByteArray(), b.toByteArray()) }
        val tree = ScriptTree.Branch(ScriptTree.Leaf(first, Script.TAPROOT_LEAF_TAPSCRIPT), ScriptTree.Leaf(second, Script.TAPROOT_LEAF_TAPSCRIPT))
        return ByteVector(Script.write(Script.pay2tr(NUMS, tree as ScriptTree)))
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val difference = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return a.size - b.size
    }

    /** `StandardVTXOTemplate`, compiled: what a VTXO or a boarding output of [owner]'s pays to. */
    fun standardScript(owner: PublicKey, operator: PublicKey, exitDelay: Long): ByteVector =
        pkScriptOf(listOf(collabLeaf(owner, operator), timeoutLeaf(owner, exitDelay)))

    /** `CheckpointTapScript`: the operator after a delay, or [ownerLeaf]. */
    fun checkpointScript(operator: PublicKey, delay: Long, ownerLeaf: ByteVector): ByteVector =
        checkpointPkScript(timeoutLeaf(operator, delay), ownerLeaf)

    private fun checkpointPkScript(unroll: ByteVector, owner: ByteVector): ByteVector {
        // `input.TapscriptFullTree` keeps the caller's order, and AssembleTaprootScriptTree builds the same branch
        // either way round because a branch sorts its children.
        val tree = ScriptTree.Branch(ScriptTree.Leaf(unroll, Script.TAPROOT_LEAF_TAPSCRIPT), ScriptTree.Leaf(owner, Script.TAPROOT_LEAF_TAPSCRIPT))
        return ByteVector(Script.write(Script.pay2tr(NUMS, tree as ScriptTree)))
    }

    fun hex(bytes: ByteArray): String = Hex.encode(bytes)
}
