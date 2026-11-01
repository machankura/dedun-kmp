package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OP_CHECKSEQUENCEVERIFY
import fr.acinq.bitcoin.OP_CHECKSIGVERIFY
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptElt
import fr.acinq.bitcoin.ScriptEltMapping
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.frost.TweakCache

/**
 * The keys an ancestor group holds in a subgroup's wallet, and the leaves they
 * sit in.
 *
 * Every group has one guardian root, a tagged HMAC away from its threshold key,
 * which it never hands out. What a parent writes into a child's birth
 * certificate is that root one public BIP32 step further, at an index taken
 * from the child's room id -- and, behind it, every key the parent's own
 * ancestors gave the parent, each one step further at the same index:
 *
 * ```
 * I        = HMAC-SHA512("bennu/group-wallet/guardian/v1", serP(K_A))
 * R_A      = K_A + I[0:32]·G,  chain code I[32:64]        A's guardian root
 * i(C)     = taggedHash("bennu/group-wallet/guardian/child/v1", C)[0:4] & 0x7fffffff
 * grant(C) = [R_A/i(C)] ++ [g/i(C) for g in grant(A)]     nearest first
 * L        = xonly(g / network / account / change / index) a leaf key in one address
 * ```
 *
 * See `docs/group-wallet-guardians.md` for why each step is what it is. In
 * short: a fresh key per child is what lets the parent *agree* to guard it, a
 * root nobody publishes is what keeps a guardian spend unlinkable to a Marmot
 * room, and re-deriving every inherited key per child is what keeps a parent's
 * wallet and its child's from sharing a leaf.
 *
 * ### The same rule as the wallet's
 *
 * Every step is a public tweak of the threshold key, so a leaked leaf private
 * key recovers the group's threshold key. Nothing reconstructs one: a leaf is
 * signed for by a FROST session over [signingCache].
 */
object GuardianDerivation {
    /**
     * The fewest blocks a coin can sit confirmed before an ancestor can take it:
     * about an hour, so the child's own spends always go first -- a guardian
     * cannot race the child for a payment that has only just arrived.
     * `docs/guardian-delay.md`.
     */
    const val MIN_DELAY: Long = 6L

    /**
     * The most: BIP68's block-based relative lock is the low 16 bits of
     * `nSequence`, so 65,535 blocks, about 15 months. Staying within it also
     * keeps bit 22 clear, so the lock always counts blocks, and keeps every
     * guardian input far below `GroupSpend.MAX_SEQUENCE`, so a guardian spend
     * can still be bumped.
     */
    const val MAX_DELAY: Long = 0xFFFFL

    /** Every delay a leaf may carry. A guardian tag outside it does not read. */
    val DELAY_RANGE: LongRange = MIN_DELAY..MAX_DELAY

    /**
     * The delay a parent is offered first for its own leaf, and the one every
     * certificate carried before parents chose: two hours. Each guardian's own
     * delay is written into its tag, and each ancestor's travels down the tree
     * unchanged -- [Guardian.grantedTo] copies it -- so this is a default, not
     * the delay. `docs/guardian-delay.md`.
     */
    const val DEFAULT_DELAY: Long = 12L

    /**
     * The key of the guardian root's HMAC. An input to every guardian key, so it
     * is frozen the moment a certificate carries one: a rename moves every leaf.
     */
    private const val ROOT_TAG = "bennu/group-wallet/guardian/v1"

    /** The tag of the hash a child's index is taken from. Frozen for the same reason. */
    private const val CHILD_TAG = "bennu/group-wallet/guardian/child/v1"

    /**
     * One ancestor's guardian key as granted to one group: whose it is, and the
     * extended public key that group's addresses derive that ancestor's leaves
     * from.
     *
     * Held as the key and the chain code rather than as an
     * [DeterministicWallet.ExtendedPublicKey], whose depth, parent fingerprint and
     * path would make two grants of the same key compare unequal depending on how
     * each was reached. Only the 65 bytes take part in a derivation.
     */
    data class Guardian(
        /** The ancestor's room id, which its leaf is named by. */
        val chatRoomId: String,
        val publicKey: PublicKey,
        val chainCode: ByteVector32,
        val delay: Long = DEFAULT_DELAY,
    ) {
        /** As a depth-0 extended key, the form every derivation below starts from. */
        val extendedPublicKey: DeterministicWallet.ExtendedPublicKey
            get() = DeterministicWallet.ExtendedPublicKey(
                publickeybytes = publicKey.value,
                chaincode = chainCode,
                depth = 0,
                path = KeyPath.empty,
                parent = 0L,
            )

        /** `xpub` on mainnet and `tpub` on every test network, as the wallet's own xpub is. */
        fun encoded(chain: Chain): String =
            extendedPublicKey.encode(if (chain.isMainnet()) DeterministicWallet.xpub else DeterministicWallet.tpub)

        /** The same ancestor, one public step further, for [childChatRoomId] -- and at the same delay. */
        fun grantedTo(childChatRoomId: String): Guardian {
            val child = extendedPublicKey.derivePublicKey(childIndex(childChatRoomId))
            return copy(publicKey = child.publicKey, chainCode = child.chaincode)
        }
    }

    /** A group's guardian root, and the cache that signs as it. */
    class Root internal constructor(
        val publicKey: PublicKey,
        val chainCode: ByteVector32,
        internal val cache: TweakCache,
    ) {
        val extendedPublicKey: DeterministicWallet.ExtendedPublicKey
            get() = DeterministicWallet.ExtendedPublicKey(publicKey.value, chainCode, 0, KeyPath.empty, 0L)
    }

    /** One guardian's leaf in one address, and -- on the guardian's side -- the cache that signs as it. */
    data class Signing(
        val leafKey: XonlyPublicKey,
        val cache: TweakCache,
    )

    /**
     * The guardian root of the group holding [thresholdPublicKey].
     *
     * @throws IllegalArgumentException if the key is not a point, or the HMAC
     * lands on an invalid scalar -- around 2^-127, and BIP32 throws at the same place.
     */
    fun root(thresholdPublicKey: HexKey): Root {
        val threshold = PublicKey(ByteVector(thresholdPublicKey.hexToByteArray()))
        require(threshold.isValid()) { "the threshold key is not a valid point" }

        val i = Crypto.hmac512(ROOT_TAG.encodeToByteArray(), threshold.value.toByteArray())
        val tweak = i.copyOfRange(0, 32)
        require(Crypto.isPrivKeyValid(tweak)) { "the guardian root landed on an invalid scalar" }

        val rootKey = threshold + PrivateKey(tweak).publicKey()
        val cache = GroupWalletDerivation.plainTweak(TweakCache.create(threshold), ByteVector32(tweak), "the guardian root")
        require(cache.tweakedPublicKey == rootKey.xOnly()) { "the guardian root's cache does not reach its key" }

        return Root(publicKey = rootKey, chainCode = ByteVector32(i.copyOfRange(32, 64)), cache = cache)
    }

    /**
     * The unhardened index a child's keys are derived at: the first four bytes
     * of a tagged hash of its room id, top bit cleared.
     */
    fun childIndex(childChatRoomId: String): Long {
        val roomId = childChatRoomId.lowercase().hexToByteArray()
        require(roomId.size == 32) { "a room id is 32 bytes, not ${roomId.size}" }
        return Pack.int32BE(Crypto.taggedHash(roomId, CHILD_TAG).toByteArray(), 0).toLong() and GroupWalletDerivation.MAX_INDEX
    }

    /**
     * The network's index under a guardian key. Not BIP44's coin type: the
     * networks this app knows, numbered once, so a guardian key granted for no
     * network in particular gives each one unrelated leaves.
     */
    fun networkIndex(chain: Chain): Long = when (chain) {
        Chain.Mainnet -> 0L
        Chain.Testnet3 -> 1L
        Chain.Testnet4 -> 2L
        Chain.Signet -> 3L
        Chain.Regtest -> 4L
    }

    /**
     * What the group holding [thresholdPublicKey], whose room is [ownChatRoomId],
     * writes into [childChatRoomId]'s certificate: its own root one step further,
     * then each of [inherited] -- the keys its own certificate carries, nearest
     * first -- one step further, in the same order.
     *
     * The same function a signer's device runs to check a proposed certificate:
     * a certificate whose guardians are not exactly this list is not one the
     * group agreed to.
     *
     * [delay] is the parent's own leaf's, which it chooses for this child. An
     * inherited key keeps the delay its own ancestor chose: a group cannot
     * shorten or lengthen another group's terms in its child's wallet.
     */
    fun grant(
        thresholdPublicKey: HexKey,
        ownChatRoomId: String,
        inherited: List<Guardian>,
        childChatRoomId: String,
        delay: Long = DEFAULT_DELAY,
    ): List<Guardian> {
        require(delay in DELAY_RANGE) { "a guardian's delay is $MIN_DELAY to $MAX_DELAY blocks, not $delay" }
        val own = root(thresholdPublicKey).extendedPublicKey.derivePublicKey(childIndex(childChatRoomId))
        return listOf(Guardian(ownChatRoomId, own.publicKey, own.chaincode, delay)) +
            inherited.map { it.grantedTo(childChatRoomId) }
    }

    /** [guardian]'s key one level above the leaves of [account]'s `change` branch. */
    private fun branchKey(guardian: Guardian, chain: Chain, account: Long, change: Long): DeterministicWallet.ExtendedPublicKey =
        guardian.extendedPublicKey.derivePublicKey(listOf(networkIndex(chain), account, change))

    /** [guardian]'s leaf key in the address at `change/index` of [account] on [chain]. */
    fun leafKey(guardian: Guardian, chain: Chain, account: Long, change: Long, index: Long): XonlyPublicKey =
        branchKey(guardian, chain, account, change).derivePublicKey(index).publicKey.xOnly()

    /**
     * `<key> OP_CHECKSIGVERIFY <delay> OP_CHECKSEQUENCEVERIFY` -- miniscript's
     * `and_v(v:pk(key),older(delay))`. A delay of 1-16 is the single opcode for
     * it, as miniscript writes it; anything else is a minimal number push.
     */
    fun leafScript(key: XonlyPublicKey, delay: Long): List<ScriptElt> {
        require(delay in DELAY_RANGE) { "a guardian's delay is $MIN_DELAY to $MAX_DELAY blocks, not $delay" }
        val lock = if (delay <= 16) ScriptEltMapping.code2elt.getValue(0x50 + delay.toInt())
            else OP_PUSHDATA(Script.encodeNumber(delay))
        return listOf(OP_PUSHDATA(key), OP_CHECKSIGVERIFY, lock, OP_CHECKSEQUENCEVERIFY)
    }

    /** One leaf per guardian in the address at `change/index`, nearest first. */
    fun leaves(guardians: List<Guardian>, chain: Chain, account: Long, change: Long, index: Long): List<ScriptTree.Leaf> =
        guardians.map { ScriptTree.Leaf(leafScript(leafKey(it, chain, account, change, index), it.delay)) }

    /**
     * The leaves as a caterpillar, nearest first: the first leaf at depth one,
     * the rest one level further down each. Every guardian can act at the same
     * moment, so the order decides only whose control block is shortest -- and
     * the parent, who knows the child best, gets the cheapest spend.
     */
    fun tree(leaves: List<ScriptTree.Leaf>): ScriptTree {
        require(leaves.isNotEmpty()) { "a tree needs a leaf" }
        return leaves.dropLast(1).foldRight(leaves.last() as ScriptTree) { leaf, rest -> ScriptTree.Branch(leaf, rest) }
    }

    /** The tree in the address at `change/index`, or null for an unguarded one. */
    fun treeAt(guardians: List<Guardian>, chain: Chain, account: Long, change: Long, index: Long): ScriptTree? =
        guardians.takeIf { it.isNotEmpty() }?.let { tree(leaves(it, chain, account, change, index)) }

    /**
     * The tree half of a `tr()` descriptor over a whole branch: `{a,{b,c}}`, the
     * same caterpillar [tree] builds, each leaf ranged over the branch's indices.
     */
    fun descriptorTree(guardians: List<Guardian>, chain: Chain, account: Long, change: Long): String {
        require(guardians.isNotEmpty()) { "a tree needs a leaf" }
        val leaves = guardians.map { guardian ->
            val path = "${networkIndex(chain)}/$account/$change/*"
            "and_v(v:pk(${guardian.encoded(chain)}/$path),older(${guardian.delay}))"
        }
        return leaves.dropLast(1).foldRight(leaves.last()) { leaf, rest -> "{$leaf,$rest}" }
    }

    /**
     * The room ids an ancestor walks from its own root down to [childChatRoomId],
     * through the line of descent [guardians] -- a certificate's guardian list,
     * nearest first -- names. Null when [ancestorChatRoomId] is not among them.
     *
     * For `guardians = [A1, A2, A3]` and `ancestor = A3`, that is `[A2, A1, C]`:
     * the generations below A3, its own child first.
     */
    fun lineage(guardians: List<Guardian>, childChatRoomId: String, ancestorChatRoomId: String): List<String>? {
        val position = guardians.indexOfFirst { it.chatRoomId.equals(ancestorChatRoomId, ignoreCase = true) }
        if (position < 0) return null
        return guardians.take(position).map { it.chatRoomId }.reversed() + childChatRoomId
    }

    /**
     * The cache the group holding [thresholdPublicKey] signs a leaf with: its
     * guardian root, then one plain step per generation down [lineage], then
     * the network, the account and the address. Every step is a plain tweak, so
     * the cache tracks exactly the point the descendant derives from its grant.
     *
     * No TapTweak follows: a script-path signature is by the leaf key itself.
     */
    fun signingCache(
        thresholdPublicKey: HexKey,
        lineage: List<String>,
        chain: Chain,
        account: Long,
        change: Long,
        index: Long,
    ): Signing {
        val root = root(thresholdPublicKey)
        val path = lineage.map(::childIndex) + listOf(networkIndex(chain), account, change, index)

        var key = root.extendedPublicKey
        var cache = root.cache
        path.forEach { step ->
            cache = GroupWalletDerivation.plainTweak(cache, GroupWalletDerivation.childTweak(key, step), "guardian step $step")
            key = key.derivePublicKey(step)
        }

        val leafKey = key.publicKey.xOnly()
        require(cache.tweakedPublicKey == leafKey) { "the guardian cache does not reach its leaf key" }
        return Signing(leafKey = leafKey, cache = cache)
    }
}
