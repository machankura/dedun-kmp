package com.machankura.dedun.compose.identity

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.data.UserWallet
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.security.NostrCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The merged list the startup screen reads, and the one derivation it rests on.
 *
 * The vector is NIP-06's own: twelve words, the key at `m/44'/1237'/0'/0/0`, and the
 * x-only public key. It pins that a wallet's `nostrPublicKey` is the same key an nsec
 * import of that wallet's nostr secret would produce -- which is what attaches a seed
 * to its credential, and what the duplicate check in `IdentityWriter` compares.
 */
class StoredIdentityJvmTest {

    private val words = "leader monkey parrot ring guide accident before fence cannon height naive bean".split(" ")
    private val nip06PrivateKey = PrivateKey(ByteVector32("7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"))
    private val nip06PublicKey = "17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917"

    private val wallet = UserWallet(
        walletId = WalletId("ab".repeat(20)),
        nodeId = "02" + "cd".repeat(32),
        words = words,
    )
    private val bareKey = PrivateKey(ByteVector32("02".repeat(32)))
    private val readOnlyKey = PrivateKey(ByteVector32("03".repeat(32))).publicKey().xOnly().value.toHex()

    @Test
    fun `a wallet's nostr key is the NIP-06 derivation of its words`() {
        assertEquals(nip06PublicKey, StoredIdentity.nostrPublicKeyOf(words))
        assertEquals(nip06PublicKey, StoredIdentity.nostrSecret(nip06PrivateKey).nostrPublicKey)
    }

    @Test
    fun `both stores land in one map, each under its own id`() {
        val merged = StoredIdentity.merge(
            wallets = mapOf(wallet.walletId to wallet),
            credentials = mapOf(
                nip06PublicKey to NostrCredential.Secret(nip06PrivateKey),
                bareKey.publicKey().xOnly().value.toHex() to NostrCredential.Secret(bareKey),
                readOnlyKey to NostrCredential.Public,
            ),
        )

        assertEquals(3, merged.size)
        val mnemonic = assertIs<StoredIdentity.Mnemonic>(merged[wallet.walletId])
        assertEquals(nip06PublicKey, mnemonic.nostrPublicKey)
        assertEquals(nip06PrivateKey, mnemonic.privateKey, "the credential's key, not a derivation")
        assertEquals(IdentityKind.Mnemonic, mnemonic.kind)

        val secret = assertIs<StoredIdentity.NostrSecret>(merged[bareKey.publicKey().xOnly().toWalletId()])
        assertEquals(IdentityKind.NostrSecret, secret.kind)
        assertEquals(40, secret.id.nodeIdHash.length, "same shape as a wallet's id")
        assertNotEquals(wallet.walletId, secret.id)

        val public = assertIs<StoredIdentity.NostrPublic>(merged[readOnlyKey.toXonlyPublicKey().toWalletId()])
        assertEquals(IdentityKind.NostrPublic, public.kind)
        assertEquals(readOnlyKey, public.nostrPublicKey)
    }

    /** The id a read-only identity gets is the one its secret would get, so an upgrade keeps it. */
    @Test
    fun `a public key and its secret get the same id`() {
        assertEquals(
            StoredIdentity.nostrSecret(bareKey).id,
            StoredIdentity.nostrPublic(bareKey.publicKey().xOnly().value.toHex()).id,
        )
    }

    /**
     * The normal state of a wallet since docs/multiple-profiles.md: its key is a secret
     * in the credentials file, and the seed is attached to that entry. Listed once, under
     * the wallet's id, with the credential's key -- not twice under two ids, which is what
     * this map held before the credentials file was the list of profiles.
     */
    @Test
    fun `a secret whose key a seed derives is listed once, as the wallet, with the credential's key`() {
        val merged = StoredIdentity.merge(
            wallets = mapOf(wallet.walletId to wallet),
            credentials = mapOf(nip06PublicKey to NostrCredential.Secret(nip06PrivateKey)),
        )

        assertEquals(1, merged.size)
        val mnemonic = assertIs<StoredIdentity.Mnemonic>(merged[wallet.walletId])
        assertEquals(nip06PrivateKey, mnemonic.privateKey)
        assertEquals(wallet, mnemonic.userWallet)
    }

    /**
     * The state a device from before the repair is in, and the one a failed repair
     * leaves: the seed is listed anyway, with its key derived from the words, so that a
     * missing entry never hides a wallet.
     */
    @Test
    fun `a seed with no credential is listed with its key derived`() {
        val merged = StoredIdentity.merge(
            wallets = mapOf(wallet.walletId to wallet),
            credentials = emptyMap(),
        )

        assertEquals(1, merged.size)
        val mnemonic = assertIs<StoredIdentity.Mnemonic>(merged[wallet.walletId])
        assertEquals(nip06PrivateKey, mnemonic.privateKey)
        assertEquals(nip06PublicKey, mnemonic.nostrPublicKey)
    }

    /**
     * A public entry for a key a seed derives is the state the seed writer's two-file
     * upgrade leaves if it dies between its writes, and the one the repair upgrades at
     * the next listing. Until then the wallet is listed by derivation, and the public
     * entry is not listed at all: one npub, once.
     */
    @Test
    fun `a public key a seed already derives is listed as the wallet only`() {
        val merged = StoredIdentity.merge(
            wallets = mapOf(wallet.walletId to wallet),
            credentials = mapOf(nip06PublicKey to NostrCredential.Public),
        )

        assertEquals(1, merged.size)
        val mnemonic = assertIs<StoredIdentity.Mnemonic>(merged[wallet.walletId])
        assertEquals(nip06PrivateKey, mnemonic.privateKey)
    }

    @Test
    fun `a key filed under a public key it does not derive is dropped`() {
        val merged = StoredIdentity.merge(
            wallets = emptyMap(),
            credentials = mapOf(nip06PublicKey to NostrCredential.Secret(bareKey)),
        )

        assertEquals(emptyMap(), merged)
    }

    @Test
    fun `the nsec form and the hex form name the same key`() {
        assertEquals(nip06PublicKey.toXonlyPublicKey().value.toHex(), nip06PublicKey)
    }
}
