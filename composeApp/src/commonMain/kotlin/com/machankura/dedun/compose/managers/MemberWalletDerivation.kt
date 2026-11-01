package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.wallet.GroupSpend
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey

/**
 * The keys of a wallet one member holds and a group guards: a guarded wallet
 * whose key path is the member's own key rather than a group's threshold key.
 *
 * Everything public is the group wallet's derivation with the member's key in
 * the threshold key's place, and the guardian derivation with the wallet's id in
 * a child room's place:
 *
 * ```
 * k        random, generated on the member's device and never sent
 * P_W      = k·G                                                     the wallet key
 * W        = taggedHash("bennu/member-wallet/id/v1", serP(P_W))      the wallet's id
 * account  = GroupWalletDerivation.account(P_W, chain, 0, grant)
 * grant    = GuardianDerivation.grant(K_G, G, guardians(G), W)
 * ```
 *
 * So a member's wallet under G is guarded exactly as a guarded subgroup of G
 * would be. What is new is only the private side: the member holds `k`, so
 * every tweak the account and the address add to the point is added to the
 * scalar too, and a spend is one Schnorr signature. See
 * `docs/member-guarded-wallets.md`.
 *
 * ### Why `W` is a hash and not the key
 *
 * A room id here is a public key, and `GroupKeyStateEvent.isSignedByRoom` asks
 * whether that key signed something. A `W` nobody holds a key for cannot be
 * mistaken for a room by anything that checks signatures.
 *
 * ### The rule this inherits
 *
 * Every step below `P_W` is an unhardened public tweak, and every guardian holds
 * the account xpub. A guardian who learns the private key of one address can
 * therefore recover `k`. That is why `k` is random and good for one wallet only:
 * never the nostr key, never a key from the profile's seed.
 */
object MemberWalletDerivation {
    /** The tag of a wallet id's hash. Frozen the moment a certificate carries one: a rename moves every leaf. */
    private const val ID_TAG = "bennu/member-wallet/id/v1"

    /**
     * The account a member wallet always opens at. One wallet is one key, so a
     * second account would be a second wallet under a key that already has one.
     */
    const val ACCOUNT: Long = 0L

    /** A fresh wallet key, from the platform's secure random. */
    fun generate(): PrivateKey {
        while (true) {
            val candidate = PrivateKey(ByteVector32(RandomInstance.bytes(32)))
            if (candidate.isValid()) return candidate
        }
    }

    /** `W`: the tagged hash of the wallet key's compressed form, hex. */
    fun walletId(walletKey: PublicKey): String =
        Crypto.taggedHash(walletKey.value.toByteArray(), ID_TAG).toByteArray().toHexKey()

    fun walletId(walletKey: HexKey): String = walletId(PublicKey(walletKey.hexToByteArray()))

    /**
     * The account [walletKey] opens on [chain] under [guardians]:
     * `GroupWalletDerivation.account` over the member's key.
     */
    fun account(walletKey: PublicKey, chain: Chain, guardians: List<GuardianDerivation.Guardian>): GroupWalletDerivation.Account =
        GroupWalletDerivation.account(walletKey.toString(), chain, ACCOUNT, guardians)

    /**
     * The account key as a private extended key: [walletPrivateKey] plus the
     * scalar `GroupWalletDerivation.account` adds to the point, with the same
     * chain code. Plain addition, so there is no parity to get wrong until the
     * taproot tweak at the very end.
     */
    fun accountPrivateKey(walletPrivateKey: PrivateKey, chain: Chain): DeterministicWallet.ExtendedPrivateKey {
        val (tweak, chainCode) = GroupWalletDerivation.accountStep(walletPrivateKey.publicKey(), chain, ACCOUNT)
        return DeterministicWallet.ExtendedPrivateKey(
            secretkeybytes = (walletPrivateKey + PrivateKey(tweak)).value,
            chaincode = chainCode,
            depth = 0,
            path = KeyPath.empty,
            parent = 0L,
        )
    }

    /** The private key of the internal key at `change/index`: CKDpriv twice below [accountPrivateKey]. */
    fun privateKeyAt(walletPrivateKey: PrivateKey, chain: Chain, change: Long, index: Long): PrivateKey {
        require(change == GroupWalletDerivation.RECEIVE || change == GroupWalletDerivation.CHANGE) { "change must be 0 or 1, not $change" }
        require(index in 0L..GroupWalletDerivation.MAX_INDEX) { "index $index is not an unhardened index" }
        return accountPrivateKey(walletPrivateKey, chain).derivePrivateKey(listOf(change, index)).privateKey
    }

    /**
     * The receive and change descriptors of [account] with the account's private
     * key in place of its xpub -- what any wallet that imports taproot
     * descriptors can sweep the coins with. The member's backup of last resort,
     * shown only behind a confirmation: whoever holds these holds the coins.
     */
    fun privateDescriptors(walletPrivateKey: PrivateKey, account: GroupWalletDerivation.Account): Pair<String, String> {
        val extended = accountPrivateKey(walletPrivateKey, account.chain)
        require(extended.extendedPublicKey == account.extendedPublicKey) { "this key does not open this account" }
        val xprv = extended.encode(if (account.chain.isMainnet()) DeterministicWallet.xprv else DeterministicWallet.tprv)

        fun swap(descriptor: String): String {
            val body = descriptor.substringBefore('#').replaceFirst(account.xpub, xprv)
            return "$body#${Descriptor.checksum(body)}"
        }
        return swap(account.receiveDescriptor) to swap(account.changeDescriptor)
    }

    /**
     * The signature [input] of a checked spend needs from the wallet's own key:
     * the address's private key under the address's taproot tweak, over the
     * input's key-path sighash.
     *
     * Refuses a guardian's input -- the member never signs through a leaf -- and
     * an input whose address is not one this key reaches, so a signature made
     * here always verifies against the key the coin is at.
     */
    fun sign(walletPrivateKey: PrivateKey, chain: Chain, input: GroupSpend.SpentInput): ByteVector64 {
        require(input.leaf == null) { "a member signs on the key path, never through a guardian's leaf" }
        val derivation = input.derivation
        val privateKey = privateKeyAt(walletPrivateKey, chain, derivation.change, derivation.index)
        require(privateKey.xOnlyPublicKey() == derivation.internalKey) { "this key does not reach the address at ${derivation.change}/${derivation.index}" }

        val signature = Crypto.signSchnorr(input.sighash, privateKey, derivation.taprootTweak)
        require(Crypto.verifySignatureSchnorr(input.sighash, signature, input.signingKey)) { "the signature does not verify against the coin's key" }
        return signature
    }
}
