package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.phoenix.data.WalletId
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Words for an nsec profile's Ark wallet, kept apart from `seed.dat` -- `docs/bark-wallet.md`, D13. */
class ArkSeedStoreJvmTest {
    private val dir = Files.createTempDirectory("ark-seeds").toFile()
    private val alice = WalletId("a1".repeat(20))
    private val bob = WalletId("b0".repeat(20))

    /** A stand-in for the keystore: a random IV, and every byte XORed with it, so the file is never plain text. */
    private val key = Random.nextBytes(32)
    private fun store() = ArkSeedStore(
        dir = dir.path.toPath(),
        encrypt = { plain -> val iv = Random.nextBytes(12); iv to xor(plain, iv) },
        decrypt = { iv, ciphertext -> xor(ciphertext, iv) },
    )
    private fun xor(bytes: ByteArray, iv: ByteArray) = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key[i % key.size].toInt() xor iv[i % iv.size].toInt()).toByte() }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `turning a wallet on makes twelve valid words once, and keeps them`() {
        val store = store()
        assertNull(store.words(alice))
        assertFalse(store.has(alice))

        val made = store.create(alice)
        assertEquals(12, made.size)
        MnemonicCode.validate(made)
        assertEquals(made, store.create(alice))
        assertEquals(made, store().words(alice), "a new store over the same file reads the same words")
        assertTrue(store.has(alice))
    }

    @Test
    fun `each profile has its own words, and the file never holds them in the clear`() {
        val store = store()
        val forAlice = store.create(alice)
        val forBob = store.create(bob)
        assertNotEquals(forAlice, forBob)
        assertEquals(forAlice, store.words(alice))

        val file = dir.resolve(ArkSeedStore.FILE).readText()
        assertFalse(forAlice.first() in file && forAlice[1] in file && forAlice[2] in file)
    }
}
