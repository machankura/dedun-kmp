package com.machankura.dedun.compose.identity

import androidx.room3.Room
import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.Lightning
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.jvm.BusinessManager
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.JvmKeyStore
import fr.acinq.phoenix.utils.PlatformContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.ui.view.model.CreateProfileViewModel
import com.machankura.dedun.compose.ui.view.model.SignInToProfileViewModel
import com.machankura.dedun.compose.ui.view.model.SovereignWalletViewModel
import com.machankura.dedun.compose.ui.view.model.bareKeyProfileWriter
import com.machankura.dedun.compose.ui.view.state.CreateProfileUIState
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
 * A profile added from inside -- Phase 5 of docs/multiple-profiles.md -- through the
 * real view models on an in-memory database.
 *
 * A is open. B's nsec is committed through the sign-in view model, as the switcher's
 * "sign in with a key" row leads to; the tail switches; B is open; and back. Both
 * accounts are intact, each with its own kind 0 and neither with the other's. Then a
 * profile created through the create view model with the bare-key writer, as the
 * switcher's "create a new profile" row leads to: it lists as a bare key, its account
 * holds the six bootstrap rows, and no node ran -- the assertion the nsec round trip
 * already makes, made of a profile the app made rather than one it was handed.
 */
class AddProfileFromInsideJvmTest {

    private lateinit var storeDir: File
    private lateinit var appDir: File
    private lateinit var phoenixGlobal: PhoenixGlobal

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val nostrRepository = DatabaseNostrRepository(db, scope)
    private val marmotRepository = DatabaseMarmotRepository(db, scope)

    private val log = Logger.withTag("AddProfileFromInsideJvmTest")
    private val keyA = PrivateKey(Lightning.randomBytes(32))
    private val keyB = PrivateKey(Lightning.randomBytes(32))

    @BeforeTest
    fun setUp() {
        storeDir = Files.createTempDirectory("bennu-add-inside-store").toFile()
        appDir = Files.createTempDirectory("bennu-add-inside-app").toFile()
        JvmKeyStore.lock()
        JvmKeyStore.unlock("correct horse battery staple".toCharArray(), storeDir)
        // No GlobalPrefs of the test's own: DataStoreManager caches one per process, and
        // when this class is the first in the JVM to populate it, a second instance over
        // the same file is exactly what DataStore refuses. Everything here goes through
        // the view model, and so through the cache.
        phoenixGlobal = PhoenixGlobal(PlatformContext(applicationDir = appDir))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
        JvmKeyStore.lock()
        storeDir.deleteRecursively()
        appDir.deleteRecursively()
    }

    private fun sovereign() = SovereignWalletViewModel(phoenixGlobal, stopBusiness = {})

    private fun signInViewModel(sovereign: SovereignWalletViewModel) = SignInToProfileViewModel(
        nostrRepository = nostrRepository,
        writeNostrKey = { key -> sovereign.writeNostrKey(key) },
        writeNostrPublicKey = { pubkey -> sovereign.writeNostrPublicKey(pubkey) },
        writeRecoveryPhrase = { _, _, onError -> onError(com.machankura.dedun.compose.ui.view.model.WritingSeedState.Error.CannotLoadSeedMap) },
    )

    private fun activated(stored: StoredIdentity.NostrSecret): Identity {
        val dataStoreManager = DataStoreManager(phoenixGlobal.ctx, chain = NodeParamsManager.chain)
        return Identity.signing(
            id = stored.id,
            kind = IdentityKind.NostrSecret,
            nostrPrivateKey = stored.privateKey,
            userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
            internalPrefs = dataStoreManager.loadInternalPrefsForWallet(stored.id),
            business = null,
        )
    }

    private suspend fun listed(sovereign: SovereignWalletViewModel, size: Int): Map<WalletId, StoredIdentity> {
        val done = CompletableDeferred<Unit>()
        sovereign.listIdentities { done.complete(Unit) }
        withTimeout(15_000) { done.await() }
        return sovereign.availableIdentities.first { it.size == size }
    }

    private suspend fun accountsOf(key: PrivateKey) =
        nostrRepository.getLocalAccounts().filter { it.unsignedNostrEvent?.pubKey == key.nostrPublicKeyHex() }

    @Test
    fun `a key signed in from inside is switched to, and the profile it was signed in from stays`() = runBlocking<Unit> {
        // A is open, the way the app gets there.
        val sovereign = sovereign()
        val signedInA = assertIs<SignInToProfileViewModel.Outcome.SignedIn>(
            signInViewModel(sovereign).commit(SignInCredential.NostrSecret(keyA, keyA.nostrPublicKeyHex()))
        )
        val storedA = assertIs<StoredIdentity.NostrSecret>(listed(sovereign, 1)[signedInA.id])
        sovereign.setActiveIdentity(activated(storedA))

        // B, from the switcher's row: the sign-in screen's commit, then its tail.
        val signedInB = assertIs<SignInToProfileViewModel.Outcome.SignedIn>(
            signInViewModel(sovereign).commit(SignInCredential.NostrSecret(keyB, keyB.nostrPublicKeyHex()))
        )
        val identities = listed(sovereign, 2)
        val storedB = assertIs<StoredIdentity.NostrSecret>(identities[signedInB.id])
        assertIs<StoredIdentity.NostrSecret>(identities[signedInA.id], "the profile signed in from is still listed")
        sovereign.switchToIdentity(signedInB.id)
        assertNull(sovereign.activeIdentity.value)
        sovereign.setActiveIdentity(activated(storedB))
        assertEquals(keyB.nostrPublicKeyHex(), sovereign.activeIdentity.value?.nostrPublicKey)

        // And back.
        sovereign.switchToIdentity(signedInA.id)
        sovereign.setActiveIdentity(activated(storedA))
        assertEquals(keyA.nostrPublicKeyHex(), sovereign.activeIdentity.value?.nostrPublicKey)

        // Both accounts intact, each with its own kind 0 and neither with the other's.
        assertEquals(1, accountsOf(keyA).size)
        assertEquals(1, accountsOf(keyB).size)
        assertEquals(2, nostrRepository.getLocalAccounts().size)

        // Pasting A's key again from inside is refused with the id A is listed under.
        val again = assertIs<SignInToProfileViewModel.Outcome.Failed>(
            signInViewModel(sovereign).commit(SignInCredential.NostrSecret(keyA, keyA.nostrPublicKeyHex()))
        )
        assertEquals(CredentialProblem.AlreadyOnThisDevice, again.problem)
        assertEquals(signedInA.id, again.alreadyHere, "the refusal names the profile to switch to")
    }

    @Test
    fun `a profile created from inside is a bare key with its six bootstrap rows, and no node ran`() = runBlocking<Unit> {
        val sovereign = sovereign()
        val created = CompletableDeferred<WalletId>()
        val viewModel = CreateProfileViewModel(
            initialCreateProfileUIState = CreateProfileUIState.ConfirmInput(name = "Bea", bio = "Second"),
            nostrRepository = nostrRepository,
            marmotRepository = marmotRepository,
        )
        viewModel.createProfileFormState.nameField.textFieldState.edit { append("Bea") }
        viewModel.createProfileFormState.biographyField.textFieldState.edit { append("Second") }

        viewModel.createAccount(sovereign.bareKeyProfileWriter()) { created.complete(it) }
        val id = withTimeout(15_000) { created.await() }

        val stored = assertIs<StoredIdentity.NostrSecret>(listed(sovereign, 1)[id], "a key, not a wallet")
        val account = nostrRepository.getLocalAccounts().single { it.unsignedNostrEvent?.pubKey == stored.nostrPublicKey }
        assertNull(account.unsignedNostrEvent?.signedAt, "the kind 0 waits for the notary, which starts when the profile is opened")
        val queued = nostrRepository.observeUnsignedNostrEvents(stored.nostrPublicKey).first()
        assertEquals(6, queued.size, "the bootstrap: the kind 0, the contact list, and the relay lists")
        assertEquals(0, queued.first().kind, "the kind 0 first")
        assertTrue(BusinessManager.businessFlow.value.isEmpty(), "no PhoenixBusiness was started for a bare key")
        assertEquals(CreateProfileUIState.ConfirmInput(name = "Bea", bio = "Second"), viewModel.createProfileUIState.value, "the screen leaves through the tail, not an error")
    }
}
