package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.byteVector

/**
 * The keys that authenticate a group's Wavelength wallet, from a seed every participant holds --
 * `docs/group-wavelength-wallet.md`, D6.
 *
 * Identity signs every mailbox authentication and indexer query, and receive auth signs invoices with ECDSA,
 * so a quorum can't hold them. They appear in no output script once vHTLCs have moved off the identity, so what
 * this seed gives a member is the means to impersonate the wallet to Ark's operator and the swap server: griefing,
 * and any credit, which D10 keeps at zero.
 *
 * Nothing here is ever logged. [toString] says so, since a data class would print the seed.
 */
class GroupArkAuth private constructor(private val master: DeterministicWallet.ExtendedPrivateKey, private val chain: Chain) {
    /** The manifest's `primary_fingerprint`, as eight hex characters: the root these keys come from. */
    val fingerprint: String = master.fingerprint().toString(16).padStart(8, '0')

    private fun familyKey(family: Long): DeterministicWallet.ExtendedPrivateKey =
        master.derivePrivateKey(KeyPath(listOf(DeterministicWallet.hardened(1017), DeterministicWallet.hardened(GroupArkDerivation.coinType(chain)), DeterministicWallet.hardened(family))))

    /** The identity family's slot in the manifest, at its own path under the primary root, so it is not an override. */
    fun identityXpub(): DeterministicWallet.ExtendedPublicKey = familyKey(IDENTITY).extendedPublicKey

    /** The wallet's identity, index 0 of its family: what the wallet statement binds this seed by. */
    fun identity(): PublicKey = identityXpub().derivePublicKey(0L).derivePublicKey(0L).publicKey

    /** The private key behind a [family] key, which this device's signer answers with at once and never sends to the group. */
    fun privateKey(family: Long, index: Long) = familyKey(family).derivePrivateKey(0L).derivePrivateKey(index).privateKey

    /** `walletdesc.FormatAuthKeys`' JSON: `{"21":…,"43":…}`, secret, and handed only to the daemon. */
    fun authKeysJson(): String {
        val prefix = if (chain.isMainnet()) DeterministicWallet.xprv else DeterministicWallet.tprv
        val coin = GroupArkDerivation.coinType(chain)
        val entries = LOCAL_FAMILIES.joinToString(",") { family ->
            val entry = BIP380.withChecksum("[$fingerprint/1017h/${coin}h/${family}h]${familyKey(family).encode(prefix)}/0/*")
            "\"$family\":\"$entry\""
        }
        return "{$entries}"
    }

    override fun toString(): String = "<group_ark_auth>"

    companion object {
        const val IDENTITY: Long = 6L
        const val RECEIVE_AUTH: Long = 21L
        const val ROUND_JOIN_AUTH: Long = 43L

        /** The daemon's `Auth` class: the two families a minimal bundle or manifest carries as private keys. */
        private val LOCAL_FAMILIES = listOf(RECEIVE_AUTH, ROUND_JOIN_AUTH)

        fun of(seed: ByteVector32, chain: Chain): GroupArkAuth =
            GroupArkAuth(DeterministicWallet.generate(seed.toByteArray().byteVector()), chain)
    }
}
