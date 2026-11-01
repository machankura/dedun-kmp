package com.machankura.dedun.compose.managers

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import com.machankura.dedun.compose.nostr.subgroup.tags.GuardianTag
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guardian keys, pinned and walked.
 *
 * The pinned vector freezes the two tags and the order of every input, so a
 * change to either fails here rather than moving every leaf a certificate has
 * already granted. The rest is the one property everything else rests on: the
 * key a descendant derives from its grant and the key an ancestor's cache signs
 * as are the same point, at every depth and both parities.
 */
class GuardianDerivationTest {
    /** A threshold key nobody needs the secret of: the test is about public derivation. */
    private fun thresholdKey(seed: Int): String =
        PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0')))).publicKey().toString()

    private val rootKey = thresholdKey(0x600d)
    private val middleKey = thresholdKey(0xbeef)
    private val childKey = thresholdKey(0xcafe)

    private val rootRoom = SharedKeyDerivation.marmotGroupId(rootKey)
    private val middleRoom = SharedKeyDerivation.marmotGroupId(middleKey)
    private val childRoom = SharedKeyDerivation.marmotGroupId(childKey)

    /** root → middle → child, as the certificates would carry them. */
    private val middleGrant = GuardianDerivation.grant(rootKey, rootRoom, inherited = emptyList(), childChatRoomId = middleRoom)
    private val childGrant = GuardianDerivation.grant(middleKey, middleRoom, inherited = middleGrant, childChatRoomId = childRoom)

    @Test
    fun `the guardian root and the child index are pinned`() {
        // Frozen. A failure here is not a test to update: every certificate
        // already signed grants keys under these, and changing them moves every
        // leaf in every guarded address.
        val root = GuardianDerivation.root(rootKey)
        assertEquals(PINNED_ROOT_KEY, root.publicKey.toString())
        assertEquals(PINNED_ROOT_CHAIN_CODE, root.chainCode.toHex())
        assertEquals(PINNED_CHILD_INDEX, GuardianDerivation.childIndex(middleRoom))
        assertEquals(
            PINNED_LEAF,
            GuardianDerivation.leafKey(childGrant[1], Chain.Mainnet, account = 0, change = 0, index = 7).value.toHex()
        )
    }

    @Test
    fun `a child index is unhardened and case-blind`() {
        val index = GuardianDerivation.childIndex(childRoom)
        assertTrue(index in 0L..0x7FFFFFFFL)
        assertEquals(index, GuardianDerivation.childIndex(childRoom.uppercase()))
        assertFailsWith<IllegalArgumentException> { GuardianDerivation.childIndex("abcd") }
    }

    @Test
    fun `a grant is the parent's root and its own grant, each one step further`() {
        assertEquals(listOf(rootRoom), middleGrant.map { it.chatRoomId })
        assertEquals(listOf(middleRoom, rootRoom), childGrant.map { it.chatRoomId })

        val rootOfRoot = GuardianDerivation.root(rootKey).extendedPublicKey
        val rootOfMiddle = GuardianDerivation.root(middleKey).extendedPublicKey
        val middleIndex = GuardianDerivation.childIndex(middleRoom)
        val childIndex = GuardianDerivation.childIndex(childRoom)

        assertEquals(rootOfMiddle.derivePublicKey(childIndex).publicKey, childGrant[0].publicKey)
        assertEquals(
            rootOfRoot.derivePublicKey(middleIndex).derivePublicKey(childIndex).publicKey,
            childGrant[1].publicKey,
            "the root's key in the grandchild is R_root / i(M) / i(C)"
        )
        assertEquals(GuardianDerivation.DEFAULT_DELAY, childGrant[1].delay)
    }

    @Test
    fun `an ancestor's cache signs as the leaf its descendant derives, at every depth`() {
        val parities = mutableSetOf<Boolean>()

        (1..8).forEach { seed ->
            val parent = thresholdKey(seed)
            val parentRoom = SharedKeyDerivation.marmotGroupId(parent)
            val grandparent = thresholdKey(seed + 100)
            val grandparentRoom = SharedKeyDerivation.marmotGroupId(grandparent)

            val parentGrant = GuardianDerivation.grant(grandparent, grandparentRoom, emptyList(), parentRoom)
            val grant = GuardianDerivation.grant(parent, parentRoom, parentGrant, childRoom)

            listOf(Chain.Mainnet, Chain.Regtest).forEach { chain ->
                (0L..2L).forEach { index ->
                    listOf(parent to parentRoom, grandparent to grandparentRoom).forEachIndexed { position, (key, room) ->
                        val lineage = GuardianDerivation.lineage(grant, childRoom, room)!!
                        val signing = GuardianDerivation.signingCache(key, lineage, chain, account = 0, change = 1, index = index)
                        val derived = GuardianDerivation.leafKey(grant[position], chain, account = 0, change = 1, index = index)
                        assertEquals(derived, signing.leafKey)
                        assertEquals(derived, signing.cache.tweakedPublicKey)
                        parities += grant[position].extendedPublicKey
                            .derivePublicKey(listOf(GuardianDerivation.networkIndex(chain), 0L, 1L, index))
                            .publicKey.isEven()
                    }
                }
            }
        }

        assertEquals(setOf(true, false), parities, "the sweep has to cover both parities of a leaf key")
    }

    @Test
    fun `the lineage runs from the ancestor's child down to the descendant`() {
        val a1 = "11".repeat(32)
        val a2 = "22".repeat(32)
        val a3 = "33".repeat(32)
        val c = "cc".repeat(32)
        val grant = listOf(a1, a2, a3).map { GuardianDerivation.Guardian(it, middleGrant[0].publicKey, middleGrant[0].chainCode) }

        assertEquals(listOf(c), GuardianDerivation.lineage(grant, c, a1))
        assertEquals(listOf(a2, a1, c), GuardianDerivation.lineage(grant, c, a3))
        assertNull(GuardianDerivation.lineage(grant, c, "44".repeat(32)))
    }

    @Test
    fun `no two places share a leaf`() {
        val siblingRoom = SharedKeyDerivation.marmotGroupId(thresholdKey(0xd00d))
        val sibling = GuardianDerivation.grant(middleKey, middleRoom, middleGrant, siblingRoom)

        fun leaf(guardian: GuardianDerivation.Guardian, chain: Chain = Chain.Mainnet, account: Long = 0) =
            GuardianDerivation.leafKey(guardian, chain, account, change = 0, index = 0)

        val leaves = listOf(
            leaf(childGrant[0]),
            leaf(childGrant[1]),
            leaf(sibling[0]),
            leaf(sibling[1]),
            leaf(middleGrant[0]),
            leaf(childGrant[1], Chain.Regtest),
            leaf(childGrant[1], account = 1),
        )
        assertEquals(leaves.size, leaves.toSet().size, "siblings, a parent and its child, networks and accounts all differ")

        val room = SharedKeyDerivation.derive(rootKey).publicKey
        assertTrue(leaves.none { it == room }, "no leaf is a room key")
    }

    @Test
    fun `a leaf is and_v(v_pk, older 12) and 36 bytes`() {
        val key = GuardianDerivation.leafKey(childGrant[0], Chain.Mainnet, 0, 0, 0)
        val script = Script.write(GuardianDerivation.leafScript(key, GuardianDerivation.DEFAULT_DELAY))

        assertEquals(36, script.size)
        assertEquals("20" + key.value.toHex() + "ad5cb2", Hex.encode(script))
        // Past sixteen the lock is a number push rather than an opcode.
        assertEquals("02e803", Hex.encode(Script.write(GuardianDerivation.leafScript(key, 1000).subList(2, 3))))
        assertFailsWith<IllegalArgumentException> { GuardianDerivation.leafScript(key, 0) }
    }

    /**
     * Where a script number changes length, byte for byte. A lock pushed any
     * other way is not minimal, and a leaf with a non-minimal push is a coin
     * nobody can spend through it. docs/guardian-delay.md.
     */
    @Test
    fun `a delay is pushed minimally at every edge of its encoding`() {
        val key = GuardianDerivation.leafKey(childGrant[0], Chain.Mainnet, 0, 0, 0)
        val encodings = mapOf(
            6L to ("56" to 36),
            16L to ("60" to 36),
            17L to ("0111" to 37),
            127L to ("017f" to 37),
            128L to ("028000" to 38),
            32_767L to ("02ff7f" to 38),
            32_768L to ("03008000" to 39),
            65_535L to ("03ffff00" to 39),
        )
        encodings.forEach { (delay, expected) ->
            val (lock, size) = expected
            val script = Script.write(GuardianDerivation.leafScript(key, delay))
            assertEquals("20" + key.value.toHex() + "ad" + lock + "b2", Hex.encode(script), "a delay of $delay")
            assertEquals(size, script.size, "a delay of $delay")
        }
    }

    @Test
    fun `a delay outside 6 to 65535 is refused everywhere it could enter`() {
        val key = GuardianDerivation.leafKey(childGrant[0], Chain.Mainnet, 0, 0, 0)
        listOf(5L, 65_536L).forEach { delay ->
            assertFailsWith<IllegalArgumentException>("leafScript at $delay") { GuardianDerivation.leafScript(key, delay) }
            assertFailsWith<IllegalArgumentException>("grant at $delay") {
                GuardianDerivation.grant(middleKey, middleRoom, middleGrant, childRoom, delay = delay)
            }
            val tag = GuardianTag.assemble(childGrant[0].copy(delay = delay))
            assertNull(GuardianTag.parse(tag), "a tag at $delay reads")
        }
        assertEquals(childGrant[0].copy(delay = 6), GuardianTag.parse(GuardianTag.assemble(childGrant[0].copy(delay = 6))))
        assertEquals(childGrant[0].copy(delay = 65_535), GuardianTag.parse(GuardianTag.assemble(childGrant[0].copy(delay = 65_535))))
    }

    @Test
    fun `a parent chooses its own leaf's delay, and an inherited key keeps the one its ancestor chose`() {
        val rootChose = GuardianDerivation.grant(rootKey, rootRoom, emptyList(), middleRoom, delay = 26_280)
        val middleChose = GuardianDerivation.grant(middleKey, middleRoom, rootChose, childRoom, delay = 144)

        assertEquals(listOf(144L, 26_280L), middleChose.map { it.delay })
        // Only the delay moved: the keys are the default grant's.
        assertEquals(childGrant.map { it.publicKey }, middleChose.map { it.publicKey })
        assertEquals(GuardianDerivation.DEFAULT_DELAY, GuardianDerivation.grant(rootKey, rootRoom, emptyList(), middleRoom).single().delay)
    }

    @Test
    fun `the longest delay is a descriptor bitcoin-kmp can checksum`() {
        val longest = GuardianDerivation.grant(middleKey, middleRoom, middleGrant, childRoom, delay = GuardianDerivation.MAX_DELAY)
        val tree = GuardianDerivation.descriptorTree(longest, Chain.Mainnet, account = 0, change = 0)

        assertTrue("older(65535)" in tree, tree)
        assertTrue("older(12)" in tree, "the root's inherited leaf keeps its own")
        assertEquals(8, Descriptor.checksum("tr(${longest[0].encoded(Chain.Mainnet)}/0/*,$tree)").length)
    }

    @Test
    fun `the tree is a caterpillar, nearest first`() {
        val leaves = (0..2).map { ScriptTree.Leaf(ByteVector(byteArrayOf(it.toByte())), Script.TAPROOT_LEAF_TAPSCRIPT) }

        assertEquals(leaves[0], GuardianDerivation.tree(leaves.take(1)))
        assertEquals(ScriptTree.Branch(leaves[0], ScriptTree.Branch(leaves[1], leaves[2])), GuardianDerivation.tree(leaves))

        // The control block grows by one hash per level: the parent's is the shortest.
        val internalKey = GuardianDerivation.leafKey(childGrant[0], Chain.Mainnet, 0, 0, 0)
        val tree = GuardianDerivation.tree(leaves)
        assertEquals(65, Script.ControlBlock.build(internalKey, tree, leaves[0]).size())
        assertEquals(97, Script.ControlBlock.build(internalKey, tree, leaves[2]).size())
    }

    @Test
    fun `the descriptor tree is the same caterpillar, ranged`() {
        val tree = GuardianDerivation.descriptorTree(childGrant, Chain.Mainnet, account = 0, change = 0)
        val parent = childGrant[0].encoded(Chain.Mainnet)
        val root = childGrant[1].encoded(Chain.Mainnet)

        assertEquals("{and_v(v:pk($parent/0/0/0/*),older(12)),and_v(v:pk($root/0/0/0/*),older(12))}", tree)
        assertTrue(childGrant[0].encoded(Chain.Signet).startsWith("tpub"))
        // A descriptor with this tree in it is one bitcoin-kmp can checksum.
        assertEquals(8, Descriptor.checksum("tr(${parent}/0/*,$tree)").length)
        assertNotEquals(tree, GuardianDerivation.descriptorTree(childGrant, Chain.Mainnet, account = 0, change = 1))
    }

    /**
     * Checked against an independent implementation -- HMAC-SHA512, SHA-256 and
     * point arithmetic written out in plain Python -- before they were pinned, so
     * they are BIP32 and the doc's formulas rather than this code's own output.
     */
    private companion object {
        const val PINNED_ROOT_KEY = "02437ef22d50eb95d81d3344b49d4872b23881fbd46f4c658fabfe5f7753db84e9"
        const val PINNED_ROOT_CHAIN_CODE = "089593979b6055ff3b34dd2bc378160405108766f34c4804a37cf909a1246530"
        const val PINNED_CHILD_INDEX = 977297025L
        const val PINNED_LEAF = "ae5078c25c84b630c7ac199cdf86fb3f32b36c677f63415d3405222623359927"
    }
}
