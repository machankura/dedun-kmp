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
object ArkScripts {
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

    /**
     * The payment hash of a vHTLC's claim leaf for [receiver], or null when [leaf] isn't one:
     * `OP_SIZE 32 OP_EQUALVERIFY OP_SHA256 <hash> OP_EQUALVERIFY <receiver> OP_CHECKSIGVERIFY <server> OP_CHECKSIG`, as `NewVHTLCPolicy` writes it.
     */
    fun claimHash(leaf: ByteVector, receiver: PublicKey): ByteVector32? {
        val script = leaf.toByteArray()
        val shape = byteArrayOf(0x82.toByte(), 1, 32, 0x88.toByte(), 0xa8.toByte(), 32)
        if (script.size != shape.size + 32 + 1 + 33 + 1 + 33 + 1) return null
        if (!script.copyOfRange(0, shape.size).contentEquals(shape)) return null
        var at = shape.size + 32
        if (script[at] != 0x88.toByte()) return null
        at += 1
        if (!script.copyOfRange(at, at + 33).contentEquals(push(receiver))) return null
        at += 33
        if (script[at] != OP_CHECKSIGVERIFY || script[at + 1] != 32.toByte() || script.last() != OP_CHECKSIG) return null
        return ByteVector32(script.copyOfRange(shape.size, shape.size + 32))
    }

    /** A vHTLC, as `arkscript.VHTLCOpts` has it. */
    data class Vhtlc(
        val sender: PublicKey,
        val receiver: PublicKey,
        val server: PublicKey,
        val paymentHash: ByteVector32,
        val refundLocktime: Long,
        val claimDelay: Long,
        val refundDelay: Long,
        val refundWithoutReceiverDelay: Long,
    )

    private const val OP_CHECKLOCKTIMEVERIFY = 0xb1.toByte()

    private fun chain(vararg keys: PublicKey): ByteArray =
        keys.dropLast(1).fold(ByteArray(0)) { script, key -> script + push(key) + OP_CHECKSIGVERIFY } + push(keys.last()) + OP_CHECKSIG

    private fun hashlock(hash: ByteVector32): ByteArray =
        byteArrayOf(0x82.toByte(), 1, 32, 0x88.toByte(), 0xa8.toByte(), 32) + hash.toByteArray() + 0x88.toByte()

    private fun timelock(locktime: Long): ByteArray = pushNumber(locktime) + OP_CHECKLOCKTIMEVERIFY + OP_DROP

    private fun delayed(delay: Long): ByteArray = pushNumber(delay) + OP_CHECKSEQUENCEVERIFY + OP_DROP

    /** The cooperative refund, with everybody. */
    fun refundLeaf(v: Vhtlc): ByteVector = ByteVector(chain(v.sender, v.receiver, v.server))

    /** The cooperative refund once the locktime has passed, without the receiver. */
    fun refundWithoutReceiverLeaf(v: Vhtlc): ByteVector = ByteVector(timelock(v.refundLocktime) + chain(v.sender, v.server))

    /** All six leaves `NewVHTLCPolicy` makes. */
    fun vhtlcLeaves(v: Vhtlc): List<ByteVector> = listOf(
        ByteVector(hashlock(v.paymentHash) + chain(v.receiver, v.server)),
        refundLeaf(v),
        refundWithoutReceiverLeaf(v),
        ByteVector(hashlock(v.paymentHash) + chain(v.receiver) + delayed(v.claimDelay)),
        ByteVector(chain(v.sender, v.receiver) + delayed(v.refundDelay)),
        ByteVector(timelock(v.refundLocktime) + chain(v.sender) + delayed(v.refundWithoutReceiverDelay)),
    )

    /** `BuildTree`: leaves in byte order, halved and halved again, under the unspendable key. Nothing else can spend it. */
    fun vhtlcScript(v: Vhtlc): ByteVector {
        fun treeOf(leaves: List<ByteVector>): ScriptTree =
            if (leaves.size == 1) ScriptTree.Leaf(leaves[0], Script.TAPROOT_LEAF_TAPSCRIPT)
            else ScriptTree.Branch(treeOf(leaves.subList(0, leaves.size / 2)), treeOf(leaves.subList(leaves.size / 2, leaves.size)))
        val sorted = vhtlcLeaves(v).sortedWith { a, b -> compareBytes(a.toByteArray(), b.toByteArray()) }
        return ByteVector(Script.write(Script.pay2tr(NUMS, treeOf(sorted))))
    }

    /**
     * The locktime and delay of a vHTLC's unilateral refund without the receiver for [sender], or null when [leaf] isn't one:
     * `<locktime> CLTV DROP <sender> CHECKSIG <delay> CSV DROP`, which the sender spends alone once both have passed, after the vHTLC has
     * been unrolled onto the chain. It is how the daemon's own recovery gets a payment's money back when the swap server won't.
     */
    fun unilateralRefundOf(leaf: ByteVector, sender: PublicKey): Pair<Long, Long>? = runCatching {
        val script = leaf.toByteArray()
        val (locktime, afterLocktime) = numberAt(script, 0) ?: return null
        val keyAt = afterLocktime + 2
        val (delay, _) = numberAt(script, keyAt + 34) ?: return null
        (locktime to delay).takeIf { ByteVector(timelock(locktime) + chain(sender) + delayed(delay)) == leaf }
    }.getOrNull()

    /** The number pushed at [at] in [script] as [pushNumber] writes one, and where what follows starts, or null for anything else. */
    private fun numberAt(script: ByteArray, at: Int): Pair<Long, Int>? {
        val op = script.getOrNull(at)?.toInt()?.and(0xff) ?: return null
        return when (op) {
            0 -> 0L to at + 1
            in 0x51..0x60 -> (op - 0x50).toLong() to at + 1
            in 1..5 -> if (at + 1 + op > script.size) null else Script.decodeNumber(script.copyOfRange(at + 1, at + 1 + op), checkMinimalEncoding = true, maximumSize = 5) to at + 1 + op
            else -> null
        }
    }

    /** Whether [leaf] is a cooperative refund leaf of a vHTLC that [sender] paid. */
    fun isRefundLeaf(leaf: ByteVector, sender: PublicKey): Boolean {
        val script = leaf.toByteArray()
        val senderPush = push(sender) + OP_CHECKSIGVERIFY
        val threeKeys = script.size == 102 && script.copyOfRange(0, 34).contentEquals(senderPush) &&
            script[34] == 32.toByte() && script[67] == OP_CHECKSIGVERIFY && script[68] == 32.toByte() && script[101] == OP_CHECKSIG
        if (threeKeys) return true
        // <locktime> CLTV DROP <sender> CHECKSIGVERIFY <server> CHECKSIG, whose locktime is a minimal push of up to five bytes.
        val push = script.firstOrNull()?.toInt() ?: return false
        if (push !in 1..5 || script.size != 1 + push + 2 + 34 + 34) return false
        val at = 1 + push
        return script[at] == OP_CHECKLOCKTIMEVERIFY && script[at + 1] == OP_DROP &&
            script.copyOfRange(at + 2, at + 36).contentEquals(senderPush) &&
            script[at + 36] == 32.toByte() && script.last() == OP_CHECKSIG
    }

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
