package com.machankura.dedun.compose.identity

import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.EncryptedNostrCredentials
import fr.acinq.phoenix.security.EncryptedSeed
import fr.acinq.phoenix.security.JvmKeyStore
import fr.acinq.phoenix.security.NostrCredential
import fr.acinq.phoenix.utils.MnemonicLanguage
import fr.acinq.phoenix.utils.PlatformContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The repair that runs at listing, against a device from before the credentials file
 * held a seed's key: `seed.dat` written directly, as every wallet on every device was
 * until docs/multiple-profiles.md, and no credential for any of them.
 *
 * What is pinned is that the repair writes exactly what is missing, once; that a
 * device already repaired is not written to again -- the file's bytes are compared,
 * since a rewrite would carry a fresh iv and could not be told apart by its contents;
 * that a public entry for a seed's key is upgraded rather than left beside a secret;
 * and that a write that fails is reported with the map that was read, not thrown.
 */
class SeedCredentialsJvmTest {

    private lateinit var storeDir: File
    private lateinit var appDir: File
    private lateinit var phoenixGlobal: PhoenixGlobal

    private val log = Logger.withTag("SeedCredentialsJvmTest")

    private class Seed(val words: List<String>) {
        val keyManager = LocalKeyManager(
            seed = MnemonicCode.toSeed(words, "").byteVector(),
            chain = NodeParamsManager.chain,
            remoteSwapInExtendedPublicKey = NodeParamsManager.remoteSwapInXpub,
        )
        val walletId = WalletId(keyManager.nodeKeys.nodeKey.publicKey)
        val nostrKey = StoredIdentity.nostrPrivateKeyOf(words)
        val nostrPublicKey = nostrKey.nostrPublicKeyHex()
    }

    private fun freshSeed() = Seed(MnemonicCode.toMnemonics(Lightning.randomBytes(16), MnemonicLanguage.English.wordlist()))

    private val first = freshSeed()
    private val second = freshSeed()

    @BeforeTest
    fun setUp() {
        storeDir = Files.createTempDirectory("bennu-seed-credentials-store").toFile()
        appDir = Files.createTempDirectory("bennu-seed-credentials-app").toFile()
        JvmKeyStore.lock()
        JvmKeyStore.unlock("correct horse battery staple".toCharArray(), storeDir)
        phoenixGlobal = PhoenixGlobal(PlatformContext(applicationDir = appDir))
    }

    @AfterTest
    fun tearDown() {
        JvmKeyStore.lock()
        storeDir.deleteRecursively()
        appDir.deleteRecursively()
    }

    /** A device from before: the seeds on disk, and nothing said about their keys. */
    private fun writeSeedsOnly(vararg seeds: Seed) {
        SeedManager.writeSeedToDisk(
            phoenixGlobal,
            EncryptedSeed.V2.encrypt(seeds.associate { it.walletId to it.words }),
            overwrite = true,
        )
    }

    private fun wallets() = SeedManager.loadAndDecryptOrNull(phoenixGlobal) ?: error("seed store unreadable")
    private fun credentials() = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal) ?: error("credentials unreadable")
    private fun credentialsFileBytes(): ByteArray? =
        File(SeedManager.getDatadir(phoenixGlobal.ctx).toString(), "nostr-credentials.dat").takeIf { it.exists() }?.readBytes()

    @Test
    fun `every seed without a credential gets one, in one write`() {
        writeSeedsOnly(first, second)
        assertNull(credentialsFileBytes(), "the device from before had no credentials file")

        val result = SeedCredentials.reconcile(log, phoenixGlobal, wallets(), credentials())

        val written = assertIs<SeedCredentials.Result.Written>(result)
        assertEquals(2, written.count)
        val expected = mapOf(
            first.nostrPublicKey to NostrCredential.Secret(first.nostrKey),
            second.nostrPublicKey to NostrCredential.Secret(second.nostrKey),
        )
        assertEquals(expected, written.credentials, "the map the listing is handed")
        assertEquals(expected, credentials(), "and the file")
    }

    @Test
    fun `a device already repaired is not written to again`() {
        writeSeedsOnly(first)
        SeedCredentials.reconcile(log, phoenixGlobal, wallets(), credentials())
        val bytesAfterRepair = credentialsFileBytes()

        val result = SeedCredentials.reconcile(log, phoenixGlobal, wallets(), credentials())

        val notNeeded = assertIs<SeedCredentials.Result.NotNeeded>(result)
        assertEquals(mapOf(first.nostrPublicKey to NostrCredential.Secret(first.nostrKey)), notNeeded.credentials)
        assertTrue(bytesAfterRepair.contentEquals(credentialsFileBytes()), "a rewrite would carry a fresh iv")
    }

    @Test
    fun `a device with no seeds needs nothing`() {
        val result = SeedCredentials.reconcile(log, phoenixGlobal, emptyMap(), emptyMap())

        assertIs<SeedCredentials.Result.NotNeeded>(result)
        assertNull(credentialsFileBytes())
    }

    /** The read-only upgrade the seed writer performs, done for a device where it was left undone. */
    @Test
    fun `a public entry for a seed's key becomes a secret`() {
        writeSeedsOnly(first)
        NostrCredentialManager.writeToDisk(
            phoenixGlobal,
            EncryptedNostrCredentials.encrypt(mapOf(first.nostrPublicKey to NostrCredential.Public)),
        )

        val result = SeedCredentials.reconcile(log, phoenixGlobal, wallets(), credentials())

        val written = assertIs<SeedCredentials.Result.Written>(result)
        assertEquals(1, written.count)
        assertEquals(mapOf(first.nostrPublicKey to NostrCredential.Secret(first.nostrKey)), credentials())
    }

    /** Other people's entries are left exactly where they were. */
    @Test
    fun `bare keys already in the file are untouched`() {
        writeSeedsOnly(first)
        val bare = StoredIdentity.nostrSecret(fr.acinq.bitcoin.PrivateKey(Lightning.randomBytes(32)))
        NostrCredentialManager.writeToDisk(
            phoenixGlobal,
            EncryptedNostrCredentials.encrypt(mapOf(bare.nostrPublicKey to NostrCredential.Secret(bare.privateKey))),
        )

        val written = assertIs<SeedCredentials.Result.Written>(SeedCredentials.reconcile(log, phoenixGlobal, wallets(), credentials()))

        assertEquals(1, written.count)
        assertEquals(
            mapOf(
                bare.nostrPublicKey to NostrCredential.Secret(bare.privateKey),
                first.nostrPublicKey to NostrCredential.Secret(first.nostrKey),
            ),
            credentials(),
        )
    }

    /**
     * The write fails -- here because the key store is locked, which is how it fails on
     * a device whose keystore entry is gone -- and the listing must go on with what it
     * read. A failed write must never hide a wallet.
     */
    @Test
    fun `a write that fails is reported with the map that was read`() {
        writeSeedsOnly(first)
        val wallets = wallets()
        val read = credentials()
        JvmKeyStore.lock()

        val result = SeedCredentials.reconcile(log, phoenixGlobal, wallets, read)

        val failed = assertIs<SeedCredentials.Result.Failed>(result)
        assertEquals(read, failed.credentials)
        assertNull(credentialsFileBytes(), "nothing reached the disk")
        // And the listing built on it still holds the wallet, by derivation.
        val listed = assertIs<StoredIdentity.Mnemonic>(StoredIdentity.merge(wallets, failed.credentials)[first.walletId])
        assertEquals(first.nostrKey, listed.privateKey)
    }
}
