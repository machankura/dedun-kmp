package com.machankura.dedun.compose.wallet.wavelength

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.lightning.Lightning
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.security.KeyStoreNames
import fr.acinq.phoenix.security.keyStoreDecryption
import fr.acinq.phoenix.security.keyStoreEncryption
import fr.acinq.phoenix.utils.AtomicFileWrite
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Words for a wallet whose profile has none of its own -- an nsec --
 * `docs/wavelength-wallet.md`, D13 and Phase 13. Its own words and not a key derived from the nsec,
 * because an nsec gets pasted into other nostr clients, and leaking one shouldn't empty a wallet.
 *
 * Not `seed.dat`: `StoredIdentity.merge` attaches a seed to the credential whose key it
 * derives, and lists a seed no credential matches as a profile of its own. Words made for
 * an nsec derive no nostr key that profile has, so in `seed.dat` they would appear as a
 * second profile. Here they are keyed by the profile's `WalletId`, encrypted under the same
 * keystore key as `seed.dat` and the credentials file, and beside them in `node-data`;
 * `merge` never reads this file, which is the point.
 */
class WalletSeedStore(
    private val dir: Path,
    private val encrypt: (ByteArray) -> Pair<ByteArray, ByteArray>,
    private val decrypt: (iv: ByteArray, ciphertext: ByteArray) -> ByteArray,
) {
    // AtomicFileWrite writes through the system file system, so this reads through it too.
    private val fileSystem = FileSystem.SYSTEM

    @Serializable
    private data class Envelope(val version: Int, val iv: String, val data: String)

    private val json = Json { ignoreUnknownKeys = true }

    /** The words kept for [id], or null if none were made. */
    fun words(id: WalletId): List<String>? = read()[id.nodeIdHash]?.split(' ')

    fun has(id: WalletId): Boolean = read().containsKey(id.nodeIdHash)

    /**
     * The words for [id], made now if there are none: twelve, as a seed profile's are made
     * (`NewProfileWriters`). Written before they are returned, so a wallet is never opened
     * with words that are not on disk.
     */
    fun create(id: WalletId): List<String> {
        words(id)?.let { return it }
        val words = MnemonicCode.toMnemonics(Lightning.randomBytes(16))
        write(read() + (id.nodeIdHash to words.joinToString(" ")))
        return words
    }

    /** Forgets [id]'s words. Only ever after the wallet was seen to be empty: see `ForgetIdentity`. */
    fun remove(id: WalletId) {
        val seeds = read()
        if (id.nodeIdHash in seeds) write(seeds - id.nodeIdHash)
    }

    private fun read(): Map<String, String> {
        val file = dir / FILE
        if (!fileSystem.exists(file)) return emptyMap()
        val envelope = json.decodeFromString<Envelope>(fileSystem.read(file) { readUtf8() })
        val plain = decrypt(ByteVector.fromHex(envelope.iv).toByteArray(), ByteVector.fromHex(envelope.data).toByteArray())
        return json.decodeFromString<Map<String, String>>(plain.decodeToString())
    }

    private fun write(seeds: Map<String, String>) {
        val (iv, ciphertext) = encrypt(json.encodeToString(seeds).encodeToByteArray())
        val bytes = json.encodeToString(Envelope(1, ByteVector(iv).toHex(), ByteVector(ciphertext).toHex())).encodeToByteArray()
        AtomicFileWrite.writeVerified(
            dir = dir,
            fileName = FILE,
            temporaryFileName = TEMPORARY_FILE,
            bytes = bytes,
            check = { readBack -> readBack.contentEquals(bytes) },
            onMismatch = { IllegalStateException("the wallet seeds file did not read back as written") },
        )
    }

    companion object {
        const val FILE = "wavelength-seeds.dat"
        private const val TEMPORARY_FILE = "temporary-wavelength-seeds.dat"

        /** The app's store: in `node-data`, beside `seed.dat`, under the keystore's no-auth key. */
        fun forApp(nodeDataDir: Path): WalletSeedStore = WalletSeedStore(
            dir = nodeDataDir,
            encrypt = { plain -> keyStoreEncryption(KeyStoreNames.KEY_NO_AUTH, plain) },
            decrypt = { iv, ciphertext -> keyStoreDecryption(KeyStoreNames.KEY_NO_AUTH, iv, ciphertext) },
        )
    }
}
