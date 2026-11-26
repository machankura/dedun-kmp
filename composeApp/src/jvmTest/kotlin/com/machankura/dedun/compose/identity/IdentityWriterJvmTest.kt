package com.machankura.dedun.compose.identity

import com.vitorpamplona.crypto.random.RandomInstance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.managers.RecoveryCodeManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.managers.computePreferencePath
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.JvmKeyStore
import fr.acinq.phoenix.security.NostrCredential
import fr.acinq.phoenix.utils.MnemonicLanguage
import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCode
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCodeKeys
import com.machankura.dedun.compose.ui.view.model.WritingSeedState
import okio.FileSystem
import okio.SYSTEM

/**
 * The three writers, against a real key store and a real directory.
 *
 * What is pinned is the duplicate rule, its two exceptions, and the inverse. A second
 * import of the same key is refused by public key, the same npub imported twice being
 * the one thing the id check cannot see; a public key already here is *not* a duplicate
 * of the secret that signs as it, and the entry becomes a secret one under the id it
 * had; a bare secret already here is *not* a duplicate of the phrase that derives it,
 * and the seed attaches to it; a phrase writes its key to the credentials file as well
 * as its words to the seed file; and forgetting takes the entry, whichever kind, out of
 * the file and the identity's preference files off the disk, leaving every other entry
 * where it was -- unless a seed derives the key, in which case nothing is touched.
 */
class IdentityWriterJvmTest {

    private lateinit var storeDir: File
    private lateinit var appDir: File
    private lateinit var phoenixGlobal: PhoenixGlobal
    private lateinit var globalPrefs: GlobalPrefs

    private val log = Logger.withTag("IdentityWriterJvmTest")

    // Fresh keys per test, not fixed ones: DataStoreManager caches each id's preferences
    // in a companion object for the life of the process, so a fixed key would be served
    // the UserPrefs an earlier test created in an earlier temp directory.
    private val first = PrivateKey(Lightning.randomBytes(32))
    private val second = PrivateKey(Lightning.randomBytes(32))

    private val scope = CoroutineScope(Job())

    @BeforeTest
    fun setUp() {
        // The seed writer reports on Main, as the create screen's state machine needs it
        // to; a real dispatcher rather than a test one, because it reports after a real
        // one-second delay and a virtual clock would never reach it.
        Dispatchers.setMain(Dispatchers.Default)
        storeDir = Files.createTempDirectory("dedun-identity-writer-store").toFile()
        appDir = Files.createTempDirectory("dedun-identity-writer-app").toFile()
        JvmKeyStore.lock()
        JvmKeyStore.unlock("correct horse battery staple".toCharArray(), storeDir)
        phoenixGlobal = PhoenixGlobal(PlatformContext(applicationDir = appDir))
        globalPrefs = GlobalPrefs(
            PreferenceDataStoreFactory.createWithPath {
                computePreferencePath(phoenixGlobal.ctx, "globalprefs.preferences_pb")
            }
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        JvmKeyStore.lock()
        storeDir.deleteRecursively()
        appDir.deleteRecursively()
    }

    /** Twelve fresh words, and what they derive. */
    private class Phrase {
        val words: List<String> = MnemonicCode.toMnemonics(Lightning.randomBytes(16), MnemonicLanguage.English.wordlist())
        private val keyManager = LocalKeyManager(
            seed = MnemonicCode.toSeed(words, "").byteVector(),
            chain = NodeParamsManager.chain,
            remoteSwapInExtendedPublicKey = NodeParamsManager.remoteSwapInXpub,
        )
        val walletId = WalletId(keyManager.nodeKeys.nodeKey.publicKey)
        val nostrKey = StoredIdentity.nostrPrivateKeyOf(words)
        val nostrPublicKey = nostrKey.nostrPublicKeyHex()
    }

    /** The callback-shaped seed writer, awaited: exactly one of its two callbacks fires. */
    private suspend fun writePhrase(phrase: Phrase): Result<WalletId> {
        val outcome = CompletableDeferred<Result<WalletId>>()
        IdentityWriter.writeMnemonic(
            log = log,
            phoenixGlobal = phoenixGlobal,
            globalPrefs = globalPrefs,
            writingState = WritingSeedState.Init,
            viewModelScope = scope,
            mnemonics = phrase.words,
            onWritingSeedError = { outcome.complete(Result.failure(IllegalStateException(it.toString()))) },
            onWritingSeedStateWriting = {},
            isRestoringWallet = false,
            isTorEnabled = false,
            customElectrumServer = null,
            onSeedWritten = { outcome.complete(Result.success(it)) },
        )
        return withTimeout(15_000) { outcome.await() }
    }

    private fun seeds() = SeedManager.loadAndDecryptOrNull(phoenixGlobal)

    private suspend fun write(key: PrivateKey) = IdentityWriter.writeNostrKey(
        log = log,
        phoenixGlobal = phoenixGlobal,
        globalPrefs = globalPrefs,
        privateKey = key,
        isTorEnabled = false,
        customElectrumServer = null,
    )

    private suspend fun writePublic(key: PrivateKey) = IdentityWriter.writeNostrPublicKey(
        log = log,
        phoenixGlobal = phoenixGlobal,
        globalPrefs = globalPrefs,
        nostrPublicKey = key.nostrPublicKeyHex(),
        isTorEnabled = false,
        customElectrumServer = null,
    )

    private fun credentials() = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)

    private fun userPrefsFile(stored: StoredIdentity) =
        computePreferencePath(phoenixGlobal.ctx, "userprefs_${stored.id.nodeIdHash}.preferences_pb")

    @Test
    fun `a key is written once and refused the second time, by public key`() = runBlocking<Unit> {
        val stored = StoredIdentity.nostrSecret(first)

        val written = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(write(first))
        assertEquals(stored.id, written.id)
        assertEquals(mapOf(stored.nostrPublicKey to NostrCredential.Secret(first)), credentials())
        assertTrue(FileSystem.SYSTEM.exists(userPrefsFile(stored)), "the preferences file the recovery screens read")

        assertEquals(stored.id, assertIs<IdentityWriter.WriteNostrCredentialResult.AlreadyExists>(write(first)).id)
    }

    @Test
    fun `a public key is written once and refused the second time`() = runBlocking<Unit> {
        val stored = StoredIdentity.nostrPublic(first.nostrPublicKeyHex())

        val written = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(writePublic(first))
        assertEquals(stored.id, written.id)
        assertEquals(mapOf(stored.nostrPublicKey to NostrCredential.Public), credentials())
        assertTrue(FileSystem.SYSTEM.exists(userPrefsFile(stored)))

        assertIs<IdentityWriter.WriteNostrCredentialResult.AlreadyExists>(writePublic(first))
    }

    /**
     * The one exception to the duplicate rule, and the reason the two kinds share a
     * file: the device does not hold this secret, so refusing it would be false. One
     * write, same id, and the preferences the read-only identity had are the ones the
     * signing identity keeps.
     */
    @Test
    fun `the nsec of a key held read-only is accepted, under the same id, and the entry becomes a secret`() = runBlocking<Unit> {
        val readOnly = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(writePublic(first))
        writePublic(second)

        val upgraded = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(write(first))

        assertEquals(readOnly.id, upgraded.id)
        assertEquals(
            mapOf(
                first.nostrPublicKeyHex() to NostrCredential.Secret(first),
                second.nostrPublicKeyHex() to NostrCredential.Public,
            ),
            credentials(),
        )
        assertTrue(FileSystem.SYSTEM.exists(userPrefsFile(StoredIdentity.nostrSecret(first))))
    }

    /** The other direction is a duplicate: the device already holds more than an npub for the key. */
    @Test
    fun `the npub of a key held as a secret is refused`() = runBlocking<Unit> {
        write(first)

        assertIs<IdentityWriter.WriteNostrCredentialResult.AlreadyExists>(writePublic(first))
        assertEquals(mapOf(first.nostrPublicKeyHex() to NostrCredential.Secret(first)), credentials())
    }

    @Test
    fun `forgetting one entry leaves the others, whichever kind, and takes the preferences with it`() = runBlocking<Unit> {
        write(first)
        writePublic(second)
        val stored = StoredIdentity.nostrSecret(first)

        val result = IdentityWriter.forgetNostrCredential(log, phoenixGlobal, stored.id, stored.nostrPublicKey)

        assertIs<IdentityWriter.ForgetNostrCredentialResult.Forgotten>(result)
        assertEquals(mapOf(second.nostrPublicKeyHex() to NostrCredential.Public), credentials())
        assertFalse(FileSystem.SYSTEM.exists(userPrefsFile(stored)))
        assertTrue(FileSystem.SYSTEM.exists(userPrefsFile(StoredIdentity.nostrPublic(second.nostrPublicKeyHex()))))

        val readOnly = StoredIdentity.nostrPublic(second.nostrPublicKeyHex())
        assertIs<IdentityWriter.ForgetNostrCredentialResult.Forgotten>(
            IdentityWriter.forgetNostrCredential(log, phoenixGlobal, readOnly.id, readOnly.nostrPublicKey)
        )
        assertEquals(emptyMap(), credentials())
        assertFalse(FileSystem.SYSTEM.exists(userPrefsFile(readOnly)))
    }

    /**
     * Not in the file at all. Since the repair that can only be a seed's key the repair
     * could not write, and the answer is the same as for one it did: a wallet is attached.
     */
    @Test
    fun `a key that is not in the file is not forgotten`() = runBlocking<Unit> {
        write(first)
        val other = StoredIdentity.nostrSecret(second)

        assertIs<IdentityWriter.ForgetNostrCredentialResult.WalletAttached>(
            IdentityWriter.forgetNostrCredential(log, phoenixGlobal, other.id, other.nostrPublicKey)
        )
        assertEquals(1, credentials()?.size)
    }

    /**
     * The credential a seed always implied. A phrase writes two files: its key as a secret
     * in the credentials file, and its words in the seed file. The profile's id is the
     * wallet's, and the nsec of that key is then a duplicate -- the device holds more than
     * the key for it.
     */
    @Test
    fun `a phrase writes its key to the credentials file as well as its words to the seed file`() = runBlocking<Unit> {
        val phrase = Phrase()

        val id = writePhrase(phrase).getOrThrow()

        assertEquals(phrase.walletId, id)
        assertEquals(mapOf(phrase.nostrPublicKey to NostrCredential.Secret(phrase.nostrKey)), credentials())
        assertEquals(setOf(phrase.walletId), seeds()?.keys)
        assertTrue(FileSystem.SYSTEM.exists(computePreferencePath(phoenixGlobal.ctx, "userprefs_${id.nodeIdHash}.preferences_pb")))

        // Refused with the wallet's id, not the bare key's: the id the profile is listed under.
        assertEquals(phrase.walletId, assertIs<IdentityWriter.WriteNostrCredentialResult.AlreadyExists>(write(phrase.nostrKey)).id)
        assertEquals(phrase.walletId, assertIs<IdentityWriter.WriteNostrCredentialResult.AlreadyExists>(writePublic(phrase.nostrKey)).id)
        assertIs<IdentityWriter.ForgetNostrCredentialResult.WalletAttached>(
            IdentityWriter.forgetNostrCredential(log, phoenixGlobal, id, phrase.nostrPublicKey)
        )
        assertEquals(1, credentials()?.size, "the forget changed nothing")
    }

    /** The same seed twice is the one thing the phrase writer still refuses. */
    @Test
    fun `the same phrase twice is refused`() = runBlocking<Unit> {
        val phrase = Phrase()
        writePhrase(phrase).getOrThrow()

        val second = writePhrase(phrase)

        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message?.contains("SeedAlreadyExists") == true, "$second")
        assertEquals(1, seeds()?.size)
    }

    /**
     * The second exception to the duplicate rule, and the first attachment: the phrase of
     * a key held as a bare secret used to be refused, and is the profile acquiring the
     * wallet that derives it. The entry stays a secret for the same key; the id becomes
     * the wallet's, and the bare key's preference files go with the old id.
     */
    @Test
    fun `the phrase of a key held as a bare secret attaches its wallet`() = runBlocking<Unit> {
        val phrase = Phrase()
        val bare = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(write(phrase.nostrKey))
        val bareUserPrefs = userPrefsFile(StoredIdentity.nostrSecret(phrase.nostrKey))
        assertTrue(FileSystem.SYSTEM.exists(bareUserPrefs))

        val id = writePhrase(phrase).getOrThrow()

        assertEquals(phrase.walletId, id)
        assertNotEquals(bare.id, id, "the profile's id is its wallet's now")
        assertEquals(mapOf(phrase.nostrPublicKey to NostrCredential.Secret(phrase.nostrKey)), credentials())
        assertEquals(setOf(phrase.walletId), seeds()?.keys)
        assertFalse(FileSystem.SYSTEM.exists(bareUserPrefs), "the bare key's preferences went with its id")
        assertTrue(FileSystem.SYSTEM.exists(computePreferencePath(phoenixGlobal.ctx, "userprefs_${id.nodeIdHash}.preferences_pb")))
    }

    /** The read-only upgrade, landing on a wallet: the public entry becomes a secret, and the seed attaches. */
    @Test
    fun `the phrase of a key held read-only attaches its wallet and the entry becomes a secret`() = runBlocking<Unit> {
        val phrase = Phrase()
        writePublic(phrase.nostrKey)
        writePublic(second)

        val id = writePhrase(phrase).getOrThrow()

        assertEquals(phrase.walletId, id)
        assertEquals(
            mapOf(
                phrase.nostrPublicKey to NostrCredential.Secret(phrase.nostrKey),
                second.nostrPublicKeyHex() to NostrCredential.Public,
            ),
            credentials(),
        )
    }

    // --- recovery codes (docs/recovery-code-v3.md, Phase 4) ---

    /** A fresh code, and what its 24 words derive. */
    private class Coded {
        val code = RecoveryCode.generate()
        val words = RecoveryCodeKeys.words(code)
        private val keyManager = LocalKeyManager(
            seed = MnemonicCode.toSeed(words, "").byteVector(),
            chain = NodeParamsManager.chain,
            remoteSwapInExtendedPublicKey = NodeParamsManager.remoteSwapInXpub,
        )
        val walletId = WalletId(keyManager.nodeKeys.nodeKey.publicKey)
        val nostrKey = StoredIdentity.nostrPrivateKeyOf(words)
        val nostrPublicKey = nostrKey.nostrPublicKeyHex()
    }

    private suspend fun writeCode(coded: Coded) = IdentityWriter.writeRecoveryCode(
        log = log,
        phoenixGlobal = phoenixGlobal,
        globalPrefs = globalPrefs,
        code = coded.code,
        isTorEnabled = false,
        customElectrumServer = null,
    )

    private fun codes() = RecoveryCodeManager.loadAndDecryptOrNull(phoenixGlobal)

    @Test
    fun `a code writes its key, its 24 words and itself`() = runBlocking<Unit> {
        val coded = Coded()

        val result = assertIs<IdentityWriter.WriteRecoveryCodeResult.Written>(writeCode(coded))

        assertEquals(coded.walletId, result.id)
        assertTrue(result.isNew)
        assertEquals(mapOf(coded.nostrPublicKey to NostrCredential.Secret(coded.nostrKey)), credentials())
        assertEquals(coded.words, seeds()?.get(coded.walletId)?.words)
        assertEquals(mapOf(coded.walletId to coded.code.canonical), codes())
        assertTrue(FileSystem.SYSTEM.exists(computePreferencePath(phoenixGlobal.ctx, "userprefs_${result.id.nodeIdHash}.preferences_pb")))
    }

    @Test
    fun `the same code twice is refused`() = runBlocking<Unit> {
        val coded = Coded()
        writeCode(coded)

        assertEquals(coded.walletId, assertIs<IdentityWriter.WriteRecoveryCodeResult.AlreadyExists>(writeCode(coded)).id)
        assertEquals(1, codes()?.size)
    }

    /**
     * The words are a phrase, and the other writers already know what a phrase is: the
     * code's words are a seed that is already here, its key is a wallet's, and forgetting
     * it would only see it derived again.
     */
    @Test
    fun `a code profile is a phrase profile to the other writers`() = runBlocking<Unit> {
        val coded = Coded()
        writeCode(coded)

        val words = coded.words
        val outcome = CompletableDeferred<Result<WalletId>>()
        IdentityWriter.writeMnemonic(
            log = log, phoenixGlobal = phoenixGlobal, globalPrefs = globalPrefs,
            writingState = WritingSeedState.Init, viewModelScope = scope, mnemonics = words,
            onWritingSeedError = { outcome.complete(Result.failure(IllegalStateException(it.toString()))) },
            onWritingSeedStateWriting = {}, isRestoringWallet = true, isTorEnabled = false, customElectrumServer = null,
            onSeedWritten = { outcome.complete(Result.success(it)) },
        )
        val second = withTimeout(15_000) { outcome.await() }
        assertTrue(second.exceptionOrNull()?.message?.contains("SeedAlreadyExists") == true, "$second")

        assertEquals(coded.walletId, assertIs<IdentityWriter.WriteNostrCredentialResult.AlreadyExists>(write(coded.nostrKey)).id)
        assertIs<IdentityWriter.ForgetNostrCredentialResult.WalletAttached>(
            IdentityWriter.forgetNostrCredential(log, phoenixGlobal, coded.walletId, coded.nostrPublicKey)
        )
    }

    /** Signed in with the code's 24 words first, then with the code: one profile, the code attached. */
    @Test
    fun `a code attaches to the phrase profile its words already made`() = runBlocking<Unit> {
        val coded = Coded()
        val outcome = CompletableDeferred<Result<WalletId>>()
        IdentityWriter.writeMnemonic(
            log = log, phoenixGlobal = phoenixGlobal, globalPrefs = globalPrefs,
            writingState = WritingSeedState.Init, viewModelScope = scope, mnemonics = coded.words,
            onWritingSeedError = { outcome.complete(Result.failure(IllegalStateException(it.toString()))) },
            onWritingSeedStateWriting = {}, isRestoringWallet = true, isTorEnabled = false, customElectrumServer = null,
            onSeedWritten = { outcome.complete(Result.success(it)) },
        )
        assertEquals(coded.walletId, withTimeout(15_000) { outcome.await() }.getOrThrow())
        val prefs = computePreferencePath(phoenixGlobal.ctx, "userprefs_${coded.walletId.nodeIdHash}.preferences_pb")
        assertTrue(FileSystem.SYSTEM.exists(prefs))

        val result = assertIs<IdentityWriter.WriteRecoveryCodeResult.Written>(writeCode(coded))

        assertEquals(coded.walletId, result.id)
        assertFalse(result.isNew)
        assertEquals(setOf(coded.walletId), seeds()?.keys)
        assertEquals(mapOf(coded.walletId to coded.code.canonical), codes())
        assertTrue(FileSystem.SYSTEM.exists(prefs), "the phrase profile's preferences are kept")
    }

    @Test
    fun `the code of a key held as a bare secret attaches to it`() = runBlocking<Unit> {
        val coded = Coded()
        write(coded.nostrKey)
        val bareUserPrefs = userPrefsFile(StoredIdentity.nostrSecret(coded.nostrKey))
        assertTrue(FileSystem.SYSTEM.exists(bareUserPrefs))

        val result = assertIs<IdentityWriter.WriteRecoveryCodeResult.Written>(writeCode(coded))

        assertEquals(coded.walletId, result.id)
        assertEquals(mapOf(coded.nostrPublicKey to NostrCredential.Secret(coded.nostrKey)), credentials())
        assertFalse(FileSystem.SYSTEM.exists(bareUserPrefs), "the bare key's preferences went with its id")
    }

    @Test
    fun `the code of a key held read-only attaches and the entry becomes a secret`() = runBlocking<Unit> {
        val coded = Coded()
        writePublic(coded.nostrKey)

        assertIs<IdentityWriter.WriteRecoveryCodeResult.Written>(writeCode(coded))

        assertEquals(mapOf(coded.nostrPublicKey to NostrCredential.Secret(coded.nostrKey)), credentials())
    }

    /**
     * What an older build sees: no codes file. The profile is the phrase in seed.dat, with
     * the same npub and the same id.
     */
    @Test
    fun `without the codes file a code profile lists as its phrase`() = runBlocking<Unit> {
        val coded = Coded()
        writeCode(coded)

        val withCodes = StoredIdentity.merge(
            seeds()!!, credentials()!!, StoredIdentity.parseCodes(codes()!!),
        )
        val withoutCodes = StoredIdentity.merge(seeds()!!, credentials()!!)

        assertIs<StoredIdentity.RecoveryCode>(withCodes[coded.walletId])
        val phrase = assertIs<StoredIdentity.Mnemonic>(withoutCodes[coded.walletId])
        assertEquals(coded.nostrPublicKey, phrase.nostrPublicKey)
    }
}
