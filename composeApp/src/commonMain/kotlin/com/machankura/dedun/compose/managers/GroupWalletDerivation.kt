package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.frost.TweakCache
import fr.acinq.bitcoin.utils.Either

/**
 * The group's on-chain wallet keys, derived from its ChillDKG threshold key.
 *
 * A wallet address here is BIP86's -- a taproot key-path output with no script
 * tree -- at BIP86's own unhardened tail, `/<change>/<index>`, under an account
 * key that the threshold key is publicly tweaked to:
 *
 * ```
 * I   = HMAC-SHA512("bennu/group-wallet/account/v1", serP(K) || chainHash || ser32(account))
 * K_a = K + I[0:32]·G,   chain code I[32:64]       an ordinary depth-0 xpub
 * P   = xonly(CKDpub(CKDpub(K_a, change), index))  BIP86 internal key
 * Q   = P + H_TapTweak(P)·G                        BIP86 output key
 * ```
 *
 * See `docs/group-chain-wallet.md` for why each level is what it is. In short:
 * every hardened level of `m/86'/0'/0'` needs the parent private key, which in a
 * threshold group nobody holds; the ceremony is what isolates one group's key
 * from another's, and the account step does what `coin'` and `account'` do --
 * mainnet and test networks, and account 0 and account 1, are unrelated keys.
 * Below the account the derivation is plain BIP32, which is what lets the group
 * export a descriptor any watch-only wallet can follow.
 *
 * ### Not [SharedKeyDerivation], and never to be confused with it
 *
 * That object derives nostr keys -- the room's own id among them -- with x-only
 * tweaks at every step and a SHA-256 scalar. This one uses plain tweaks and
 * HMAC-SHA512 until the last step. The two never share a key, and the wallet
 * never sits on the room's key: a wallet at the room's key would be findable by
 * anyone who can see the room's id.
 *
 * ### The rule this inherits
 *
 * Every tweak is publicly computable from the threshold key, so a leaked private
 * key for any address recovers the group's threshold key -- the room, every
 * address of every account, and every event the group can sign. Nothing here or
 * anywhere else reconstructs one. The only export is the account xpub.
 *
 * ### A guarded account
 *
 * An account opened with guardians -- the keys a subgroup's birth certificate
 * grants it, see `GuardianDerivation` -- is the same account with one change at
 * the very end: each address commits to a tree of one leaf per guardian, so the
 * last tweak is BIP341's script-path tweak over that tree rather than BIP86's
 * empty one. The internal key, and so the group's own key-path spend, is
 * untouched. `docs/group-wallet-guardians.md` says why.
 */
object GroupWalletDerivation {
    /** BIP44's external chain: addresses handed out to be paid. */
    const val RECEIVE: Long = 0L

    /** BIP44's internal chain: where a spend sends what it does not pay out. */
    const val CHANGE: Long = 1L

    /**
     * The key of the account step's HMAC. An input to every wallet key, so it is
     * frozen the moment a group holds coins under it: changing it moves every
     * address, and the coins at the old ones are found by nobody. A rename is a
     * protocol fork and belongs in a `v2`, never in an edit to this one.
     *
     * That is why it still says `bennu/` in Dedun, as the `mantra/` tags kept their
     * prefix through the rebrands before it. Every `bennu/` tag in the app stays for
     * the same reason, and `DomainTagsPinnedTest` and the pinned vectors beside it
     * fail if one moves. See docs/dedun-rename.md, D2.
     */
    private const val ACCOUNT_TAG = "bennu/group-wallet/account/v1"

    /** The largest unhardened BIP32 index. Hardened ones cannot be walked here. */
    internal const val MAX_INDEX: Long = 0x7FFFFFFFL

    /**
     * One account of one group on one network: the extended public key every
     * address of it is derived under, and the cache that signs as its key.
     *
     * [extendedPublicKey] is safe to hand out -- it reveals the account's
     * addresses and balance, and nothing about the threshold key or the room.
     */
    class Account internal constructor(
        val chain: Chain,
        val account: Long,
        val extendedPublicKey: DeterministicWallet.ExtendedPublicKey,
        /** The threshold key tweaked to [extendedPublicKey]'s key: what the account's sessions start from. */
        internal val cache: TweakCache,
        /**
         * The ancestors that can spend from every address of this account, nearest
         * first -- empty for an account nobody guards, which is every account a
         * group that is nobody's subgroup opens.
         */
        val guardians: List<GuardianDerivation.Guardian> = emptyList(),
    ) {
        /** Whether its addresses carry guardian leaves. */
        val isGuarded: Boolean get() = guardians.isNotEmpty()

        /** The account key's own fingerprint, as a descriptor's key origin and a PSBT's derivation name it. */
        val fingerprint: Long get() = extendedPublicKey.fingerprint()

        val fingerprintHex: String get() = fingerprint.toString(16).padStart(8, '0')

        /** `xpub` on mainnet, `tpub` on every test network -- BIP86 has no SLIP-132 prefix of its own. */
        val xpub: String
            get() = extendedPublicKey.encode(
                if (chain.isMainnet()) DeterministicWallet.xpub else DeterministicWallet.tpub
            )

        /**
         * The receive branch as a ranged `tr()` descriptor over the account xpub, with the
         * account's fingerprint as its key origin and a checksum -- what a watch-only
         * wallet imports.
         */
        val receiveDescriptor: String get() = descriptor(RECEIVE)

        /** The change branch, as [receiveDescriptor] gives the receive branch. */
        val changeDescriptor: String get() = descriptor(CHANGE)

        private fun descriptor(change: Long): String {
            val tree = if (isGuarded) "," + GuardianDerivation.descriptorTree(guardians, chain, account, change) else ""
            val body = "tr([$fingerprintHex]$xpub/$change/*$tree)"
            return "$body#${Descriptor.checksum(body)}"
        }

        /** The same account with [guardians] in every address. */
        fun guardedBy(guardians: List<GuardianDerivation.Guardian>): Account =
            Account(chain, account, extendedPublicKey, cache, guardians)

        override fun equals(other: Any?): Boolean =
            other is Account && chain == other.chain && account == other.account &&
                extendedPublicKey == other.extendedPublicKey && guardians == other.guardians

        override fun hashCode(): Int = extendedPublicKey.hashCode() * 31 + guardians.hashCode()
    }

    /**
     * One address of an account, and the cache a signing session for it needs.
     *
     * [cache] is not an optimisation, for the same reason [SharedKeyDerivation.Derived]'s
     * is not: a session created with any other cache aggregates to a signature
     * that is perfectly valid for some other key, and the coins are at [outputKey].
     */
    data class Derived(
        val change: Long,
        val index: Long,
        /** BIP86's internal key `P`. */
        val internalKey: XonlyPublicKey,
        /** BIP86's output key `Q`: the key in the output script, and the key a spend's signature verifies against. */
        val outputKey: XonlyPublicKey,
        val cache: TweakCache,
        /** The guardian leaves this address commits to, or null for BIP86's empty tree. */
        val tree: ScriptTree? = null,
    ) {
        /** The tweak from [internalKey] to [outputKey]: over [tree] when there is one, BIP86's otherwise. */
        val taprootTweak: Crypto.TaprootTweak
            get() = tree?.let { Crypto.TaprootTweak.ScriptPathTweak(it.hash()) } ?: Crypto.TaprootTweak.KeyPathTweak

        /** `OP_1 <Q>`. */
        val pubkeyScript: ByteVector
            get() = ByteVector(Script.write(Script.pay2tr(internalKey, taprootTweak)))

        fun address(chain: Chain): String = when (tree) {
            null -> Bitcoin.computeBIP86Address(internalKey, chain.chainHash)
            else -> Bitcoin.addressFromPublicKeyScript(chain.chainHash, pubkeyScript.toByteArray()).right
                ?: throw IllegalStateException("a taproot output always has an address")
        }

        /** The path below the account, as a PSBT's `tap_bip32_derivation` carries it. */
        val keyPath: KeyPath get() = KeyPath(listOf(change, index))
    }

    /**
     * The account step: the threshold key tweaked by a tagged HMAC over itself,
     * the network and the account number.
     *
     * @throws IllegalArgumentException if the threshold key is not a valid point,
     * or the HMAC lands on an invalid scalar -- around 2^-127, and BIP32 throws at
     * the same place.
     */
    fun account(
        thresholdPublicKey: HexKey,
        chain: Chain,
        account: Long = 0L,
        guardians: List<GuardianDerivation.Guardian> = emptyList(),
    ): Account {
        require(account in 0L..MAX_INDEX) { "account $account is not an unhardened index" }

        val root = PublicKey(ByteVector(thresholdPublicKey.hexToByteArray()))
        require(root.isValid()) { "the threshold key is not a valid point" }

        val (tweak, chainCode) = accountStep(root, chain, account)

        val accountKey = root + PrivateKey(tweak).publicKey()
        val cache = plainTweak(TweakCache.create(root), tweak, "the account step")
        // The cache tracks the full point; the point arithmetic above is the
        // reference it has to agree with, or every session for this account signs
        // for a key nobody's coins are at.
        require(cache.tweakedPublicKey == accountKey.xOnly()) { "the account cache does not reach the account key" }

        return Account(
            chain = chain,
            account = account,
            extendedPublicKey = DeterministicWallet.ExtendedPublicKey(
                publickeybytes = accountKey.value,
                chaincode = chainCode,
                depth = 0,
                path = KeyPath.empty,
                parent = 0L,
            ),
            cache = cache,
            guardians = guardians,
        )
    }

    /**
     * The account step's tweak and chain code for [root]: `I[0:32]` and
     * `I[32:64]` of the tagged HMAC over the key, the network and the account.
     *
     * Split out for the one holder of a key path who has its private key -- a
     * member wallet, `MemberWalletDerivation` -- which adds the same tweak to
     * the scalar that [account] adds to the point.
     */
    internal fun accountStep(root: PublicKey, chain: Chain, account: Long): Pair<ByteVector32, ByteVector32> {
        val i = Crypto.hmac512(
            ACCOUNT_TAG.encodeToByteArray(),
            root.value.toByteArray() + chain.chainHash.value.toByteArray() + Pack.writeInt32BE(account.toInt())
        )
        val tweak = i.copyOfRange(0, 32)
        require(Crypto.isPrivKeyValid(tweak)) { "the account step for account $account landed on an invalid scalar" }
        return ByteVector32(tweak) to ByteVector32(i.copyOfRange(32, 64))
    }

    /**
     * The address at `/<change>/<index>` under [account].
     *
     * Computed twice -- as points through [DeterministicWallet.ExtendedPublicKey.derivePublicKey],
     * and as tweaks on the account's cache -- and refused if the two disagree.
     * They are `require`s rather than tests because they are what stands between
     * a parity mistake in the cache and a wallet whose signatures do not verify.
     */
    fun derive(account: Account, change: Long, index: Long): Derived =
        deriveBelow(
            account.extendedPublicKey,
            account.cache,
            change,
            index,
            tree = GuardianDerivation.treeAt(account.guardians, account.chain, account.account, change, index),
        )

    /**
     * The tail on its own: BIP32 public derivation and the BIP341 tweak -- over
     * [tree] when there is one, BIP86's key-path tweak otherwise -- from any
     * account-level key and the cache that signs as it. Split out so the tail can
     * be tested against BIP86's published vectors, which start from a seed's
     * account key rather than from a threshold key.
     */
    internal fun deriveBelow(
        accountKey: DeterministicWallet.ExtendedPublicKey,
        accountCache: TweakCache,
        change: Long,
        index: Long,
        tree: ScriptTree? = null,
    ): Derived {
        require(change == RECEIVE || change == CHANGE) { "change must be $RECEIVE or $CHANGE, not $change" }
        require(index in 0L..MAX_INDEX) { "index $index is not an unhardened index" }
        require(accountCache.tweakedPublicKey == accountKey.publicKey.xOnly()) { "the cache does not sign as this account key" }

        val branchKey = accountKey.derivePublicKey(change)
        val childKey = branchKey.derivePublicKey(index)

        var cache = plainTweak(accountCache, childTweak(accountKey, change), "branch $change")
        cache = plainTweak(cache, childTweak(branchKey, index), "index $index")

        val internalKey = childKey.publicKey.xOnly()
        require(cache.tweakedPublicKey == internalKey) { "the cache does not reach the internal key at $change/$index" }

        val taprootTweak = tree?.let { Crypto.TaprootTweak.ScriptPathTweak(it.hash()) } ?: Crypto.TaprootTweak.KeyPathTweak
        cache = when (val tweaked = cache.tweak(internalKey.tweak(taprootTweak), isXonly = true)) {
            is Either.Left -> throw IllegalArgumentException("Could not apply the taproot tweak at $change/$index", tweaked.value)
            is Either.Right -> tweaked.value.first
        }

        val outputKey = internalKey.outputKey(taprootTweak).first
        require(cache.tweakedPublicKey == outputKey) { "the cache does not reach the output key at $change/$index" }

        return Derived(change = change, index = index, internalKey = internalKey, outputKey = outputKey, cache = cache, tree = tree)
    }

    /** BIP32's `I_L` for one unhardened step: the scalar CKDpub adds, which a FROST cache takes as a plain tweak. */
    internal fun childTweak(parent: DeterministicWallet.ExtendedPublicKey, index: Long): ByteVector32 =
        ByteVector32(
            Crypto.hmac512(
                parent.chaincode.toByteArray(),
                parent.publickeybytes.toByteArray() + Pack.writeInt32BE(index.toInt())
            ).copyOfRange(0, 32)
        )

    internal fun plainTweak(cache: TweakCache, tweak: ByteVector32, step: String): TweakCache =
        when (val tweaked = cache.tweak(tweak, isXonly = false)) {
            is Either.Left -> throw IllegalArgumentException("Could not apply $step to the group's key", tweaked.value)
            is Either.Right -> tweaked.value.first
        }
}
