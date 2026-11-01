package com.machankura.dedun.compose.identity

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector
import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs

/**
 * What the device holds beside an identity's key, which decides what else it can have.
 *
 * The distinction is not cosmetic: the nostr key is a BIP32 leaf of the seed at
 * `m/44'/1237'/0'/0/0`, and that derivation runs one way. Twelve words yield the
 * key and a wallet; a bare key yields nothing further, so an identity made from
 * one can never grow a node behind it; and a bare public key yields nothing at all,
 * not even a signature. See docs/nsec-sign-in.md, docs/npub-sign-in.md and
 * docs/multiple-profiles.md.
 */
enum class IdentityKind {
    /**
     * A key the device also holds a seed for. The key is a credential like any other
     * -- it is read from the credentials file, not derived from the node -- and the seed
     * is the wallet attached to it. The name records the attachment, not the source.
     */
    Mnemonic,

    /** A bare nostr secret. Nothing else can be derived from it. */
    NostrSecret,

    /** A bare nostr public key. Nothing can be signed, opened or derived from it. */
    NostrPublic,
}

/**
 * The signing identity the app is running as -- or, for [IdentityKind.NostrPublic],
 * the identity it is looking at.
 *
 * This is what every consumer of "the active user" reads, in place of reaching
 * through the wallet -- `business.walletManager.keyManager.value.nostrPrivateKey()`
 * -- for a key that only some identities derive that way. Everything downstream of
 * the key is already independent of the seed: the notary and the sync pumps build
 * a quartz `KeyPair` from its bytes, the ChillDKG host key is a hash of it, and the
 * Marmot bundle password is `hash160` of it. None of them can tell where it came
 * from, and after this type none of them need to.
 *
 * [nostrPublicKey] is a field rather than a derivation because a read-only identity
 * has nothing to derive it from. The two checks in `init` keep the field and the key
 * from disagreeing, which a data class would otherwise happily allow; [signing] and
 * [readOnly] are the two ways to get one built without tripping them.
 *
 * @param id keys the per-identity preference files and the wallet metadata. For a
 *   [IdentityKind.Mnemonic] identity it is the hash160 of the node id, as it always
 *   was; for the two bare kinds it is the hash160 of the x-only public key
 *   ([toWalletId]) -- the *same* id whether the device holds the secret or only the
 *   public key, which is what lets a read-only identity become a signing one and keep
 *   its preferences. Same shape as a wallet's, so nothing downstream can tell the
 *   kinds apart by the id -- the kind is here, where it can be asked.
 * @param nostrPrivateKey null only for [IdentityKind.NostrPublic].
 * @param business the running node. Non-null only for [IdentityKind.Mnemonic].
 */
data class Identity(
    val id: WalletId,
    val kind: IdentityKind,
    /**
     * X-only, hex -- the form every nostr call site wants.
     *
     * Not `publicKey().toHex()`: that is the 33-byte compressed encoding, sixty-six
     * hex characters, and it is what the library's own `nostrPublicKey()` returns.
     * Nostr keys are the 32-byte x coordinate.
     */
    val nostrPublicKey: HexKey,
    val nostrPrivateKey: PrivateKey?,
    val userPrefs: UserPrefs,
    val internalPrefs: InternalPrefs,
    val business: PhoenixBusiness?,
) {
    /**
     * What the notary, the pumps and every write entrance ask.
     *
     * A property of the identity rather than `nostrPrivateKey != null` repeated at each
     * site, so that a signer that holds no local key -- a remote one, some day -- has
     * one place to answer *true*.
     */
    val canSign: Boolean get() = nostrPrivateKey != null

    init {
        require((nostrPrivateKey == null) == (kind == IdentityKind.NostrPublic)) {
            "identity kind $kind and its key disagree about whether there is one"
        }
        require(nostrPrivateKey == null || nostrPrivateKey.nostrPublicKeyHex() == nostrPublicKey) {
            "identity key does not derive the public key it was given"
        }
    }

    /** The key must never reach a log. `data class` would print it. */
    override fun toString(): String = "Identity(id=$id, kind=$kind, key=<redacted>)"

    companion object {
        /** An identity that holds its secret: the public key is derived, never supplied. */
        fun signing(
            id: WalletId,
            kind: IdentityKind,
            nostrPrivateKey: PrivateKey,
            userPrefs: UserPrefs,
            internalPrefs: InternalPrefs,
            business: PhoenixBusiness?,
        ): Identity {
            require(kind != IdentityKind.NostrPublic) { "a read-only identity has no secret to sign with" }
            return Identity(
                id = id,
                kind = kind,
                nostrPublicKey = nostrPrivateKey.nostrPublicKeyHex(),
                nostrPrivateKey = nostrPrivateKey,
                userPrefs = userPrefs,
                internalPrefs = internalPrefs,
                business = business,
            )
        }

        /** An identity the device holds only the public key of. No secret, no node. */
        fun readOnly(
            id: WalletId,
            nostrPublicKey: HexKey,
            userPrefs: UserPrefs,
            internalPrefs: InternalPrefs,
        ): Identity = Identity(
            id = id,
            kind = IdentityKind.NostrPublic,
            nostrPublicKey = nostrPublicKey,
            nostrPrivateKey = null,
            userPrefs = userPrefs,
            internalPrefs = internalPrefs,
            business = null,
        )
    }
}

/**
 * The one place a quartz [KeyPair] is built from an identity.
 *
 * With a secret, quartz recomputes the public key from it and the second argument is a
 * cross-check. Without one it is quartz's read-only constructor, which its own source
 * comments as "this is a read-only account". What it must never be is
 * `KeyPair(privKey = null)` with nothing else: that is quartz's *make me a new key*, and
 * a read-only pair forwarded into it would try to open the user's gift wraps with a key
 * nobody has, fail, and look in the logs like a decryption failure. This helper exists so
 * that the trap has one place to be avoided.
 */
fun Identity.toKeyPair(): KeyPair = KeyPair(
    privKey = nostrPrivateKey?.value?.toByteArray(),
    pubKey = nostrPublicKey.hexToByteArray(),
)

/**
 * The [WalletId] of an identity that has no node id to hash.
 *
 * `WalletId(nodeId: PublicKey)` is the hash160 of the compressed node key; this is
 * the hash160 of the x-only nostr key. The two are over different keys -- the node
 * key sits at `m/50'/0'`, the nostr key at `m/44'/1237'/...` -- so a mnemonic wallet
 * and its own nostr identity would never share an id even if both were registered.
 * Which is exactly why a duplicate has to be caught by public key instead.
 */
fun XonlyPublicKey.toWalletId(): WalletId =
    WalletId(Crypto.hash160(value).byteVector().toHex())
