package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.Network
import fr.acinq.phoenix.data.WalletId
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletFilesJvmTest {
    private val dir = Files.createTempDirectory("wavelength-files").toFile()
    private val files = WalletFiles.under(dir.path.toPath())
    private val alice = WalletId("a1".repeat(20))
    private val bob = WalletId("b0".repeat(20))

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `each profile and each network has its own directory`() {
        assertNotEquals(files.dataDir(Network.SIGNET, alice), files.dataDir(Network.SIGNET, bob))
        assertNotEquals(files.dataDir(Network.SIGNET, alice), files.dataDir(Network.MAINNET, alice))
    }

    @Test
    fun `consent is per profile`() {
        assertFalse(files.consented(alice))
        files.recordConsent(alice)
        assertTrue(files.consented(alice))
        assertFalse(files.consented(bob))
    }

    @Test
    fun `an identity pin is per profile and per network`() {
        assertNull(files.pinnedIdentity(Network.SIGNET, alice))
        files.pinIdentity(Network.SIGNET, alice, "02ab")
        assertEquals("02ab", files.pinnedIdentity(Network.SIGNET, alice))
        assertNull(files.pinnedIdentity(Network.MAINNET, alice))
        assertNull(files.pinnedIdentity(Network.SIGNET, bob))
    }

    @Test
    fun `proofs of payment are kept per profile and survive a reread`() {
        assertEquals(emptyMap(), files.preimages(Network.SIGNET, alice))
        files.recordPreimage(Network.SIGNET, alice, "h1", "p1")
        files.recordPreimage(Network.SIGNET, alice, "h2", "p2")
        assertEquals(mapOf("h1" to "p1", "h2" to "p2"), WalletFiles.under(dir.path.toPath()).preimages(Network.SIGNET, alice))
        assertEquals(emptyMap(), files.preimages(Network.SIGNET, bob))
    }
}
