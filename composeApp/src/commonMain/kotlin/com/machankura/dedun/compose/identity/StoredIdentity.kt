package com.machankura.dedun.compose.identity

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.phoenix.data.UserWallet
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.nostrPrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.NostrCredential

/**
 * A profile this device holds something for, as listed before any of them is started.
 *
 * The credentials file is the list of profiles: a `Secret` entry for every key the
 * device can sign as, a `Public` one for every key it can only look at. `seed.dat` is
 * the list of wallets, and a wallet is *attached* to the profile whose key it derives
 * -- matched by public key, since a seed's key is written to the credentials file when
 * the seed is (`IdentityWriter.writeMnemonic`) and repaired into it for every seed
 * already here (`SeedCredentials.reconcile`). See docs/multiple-profiles.md, Phase 1.
 * The nostr public key is on every kind because it is the one thing they have in
 * common and the one thing that attaches a wallet to a profile or refuses a duplicate.
 *
 * On what this holds: `SovereignWalletViewModel.availableWallets` has always carried
 * decrypted words for the view model's life, because starting the node needs them.
 * [NostrSecret] and [Mnemonic] carry their key for the same reason and are no worse;
 * decrypting at activation instead would be an improvement for every kind and belongs
 * to all of them.
 */
sealed interface StoredIdentity {
    val id: WalletId
    val kind: IdentityKind
    val nostrPublicKey: HexKey

    /**
     * A key the device also holds a seed for. The key is the credential's, so that
     * activating this profile does not depend on the node to learn who it is; the seed
     * is the wallet attached to it, and its id is the wallet's -- `hash160(nodeId)`, as
     * it has always been -- because the library keys the node's preferences and
     * metadata by that id and nothing has to move.
     */
    data class Mnemonic(
        val userWallet: UserWallet,
        val privateKey: PrivateKey,
    ) : StoredIdentity {
        override val id: WalletId get() = userWallet.walletId
        override val kind: IdentityKind get() = IdentityKind.Mnemonic
        override val nostrPublicKey: HexKey = privateKey.nostrPublicKeyHex()
        override fun toString(): String = "StoredIdentity.Mnemonic(id=$id, npub=$nostrPublicKey, key=<redacted>)"
    }

    data class NostrSecret(
        override val id: WalletId,
        override val nostrPublicKey: HexKey,
        val privateKey: PrivateKey,
    ) : StoredIdentity {
        override val kind: IdentityKind get() = IdentityKind.NostrSecret
        override fun toString(): String = "StoredIdentity.NostrSecret(id=$id, npub=$nostrPublicKey, key=<redacted>)"
    }

    /**
     * A public key the device holds nothing else for. Its id is the one its secret would
     * have, so that pasting the nsec later keeps the preferences and metadata.
     */
    data class NostrPublic(
        override val id: WalletId,
        override val nostrPublicKey: HexKey,
    ) : StoredIdentity {
        override val kind: IdentityKind get() = IdentityKind.NostrPublic
    }

    companion object {
        /**
         * The nostr key a wallet's words derive, at the NIP-06 path. `SeedManager.loadAndDecrypt`
         * has already built exactly this key manager to learn the node id, so this is the
         * second derivation of it, not a new cost. It is what attaches a seed to its
         * credential, and what the credential is repaired from when a seed has none.
         */
        fun nostrPrivateKeyOf(words: List<String>): PrivateKey = LocalKeyManager(
            seed = MnemonicCode.toSeed(words, "").byteVector(),
            chain = NodeParamsManager.chain,
            remoteSwapInExtendedPublicKey = NodeParamsManager.remoteSwapInXpub,
        ).nostrPrivateKey()

        /** The x-only public key of [nostrPrivateKeyOf]. */
        fun nostrPublicKeyOf(words: List<String>): HexKey = nostrPrivateKeyOf(words).nostrPublicKeyHex()

        /** A wallet whose credential is missing: the key is derived from the words, as it always was. */
        fun mnemonic(userWallet: UserWallet): Mnemonic =
            Mnemonic(userWallet, nostrPrivateKeyOf(userWallet.words))

        fun nostrSecret(privateKey: PrivateKey): NostrSecret {
            val xOnly = privateKey.publicKey().xOnly()
            return NostrSecret(
                id = xOnly.toWalletId(),
                nostrPublicKey = xOnly.value.toHex(),
                privateKey = privateKey,
            )
        }

        fun nostrPublic(publicKeyHex: HexKey): NostrPublic = NostrPublic(
            id = publicKeyHex.toXonlyPublicKey().toWalletId(),
            nostrPublicKey = publicKeyHex,
        )

        /**
         * One map from the two stores: the credentials are the list, and the seeds are
         * attached to it. [credentials] is keyed by x-only public key hex, as
         * `nostr-credentials.dat` is; a secret's id is derived from its own key, so a
         * stored secret whose map key disagrees with its public key is dropped here too,
         * though `EncryptedNostrCredentials` refuses such a file before it gets this far.
         *
         * A `Secret` whose key a seed derives is that seed's profile, listed once, as a
         * [Mnemonic] under the wallet's id and carrying the credential's key. A `Public`
         * for such a key is skipped with a log line: `IdentityWriter.writeMnemonic` replaces
         * it with a secret before it writes the seed, and `SeedCredentials.reconcile` does
         * the same for one left behind, so it is a state the next listing no longer sees.
         *
         * A seed with no credential at all -- the repair failed to write, or a crash fell
         * between the seed writer's two files -- is listed as it always was, with its key
         * derived from the words. A failed write must never hide a wallet.
         */
        fun merge(
            wallets: Map<WalletId, UserWallet>,
            credentials: Map<HexKey, NostrCredential>,
        ): Map<WalletId, StoredIdentity> {
            val walletsByPublicKey = wallets.values.associateBy { nostrPublicKeyOf(it.words) }
            val merged = LinkedHashMap<WalletId, StoredIdentity>()
            credentials.forEach { (publicKeyHex, credential) ->
                when (credential) {
                    is NostrCredential.Secret -> {
                        val wallet = walletsByPublicKey[publicKeyHex]
                        val stored = if (wallet != null) Mnemonic(wallet, credential.privateKey) else nostrSecret(credential.privateKey)
                        if (stored.nostrPublicKey == publicKeyHex) merged[stored.id] = stored
                    }
                    is NostrCredential.Public -> {
                        if (publicKeyHex in walletsByPublicKey) {
                            log.w { "public credential for a key a seed derives; the next repair upgrades it" }
                        } else {
                            val stored = runCatching { nostrPublic(publicKeyHex) }.getOrNull()
                            if (stored != null) merged[stored.id] = stored
                        }
                    }
                }
            }
            walletsByPublicKey.values.forEach { wallet ->
                if (wallet.walletId !in merged) {
                    log.w { "wallet=${wallet.walletId} has no credential for its nostr key; deriving it" }
                    merged[wallet.walletId] = mnemonic(wallet)
                }
            }
            return merged
        }

        private val log = Logger.withTag("StoredIdentity")
    }
}

/** The x-only key as bitcoin-kmp types it, from the hex nostr carries it as. */
fun HexKey.toXonlyPublicKey(): XonlyPublicKey = XonlyPublicKey(ByteVector32(this))
