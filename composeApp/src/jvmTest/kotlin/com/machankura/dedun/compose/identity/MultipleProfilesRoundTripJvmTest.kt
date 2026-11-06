package com.machankura.dedun.compose.identity

import androidx.room3.Room
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.Lightning
import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.EmptyWalletId
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.jvm.BusinessManager
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.JvmKeyStore
import fr.acinq.phoenix.security.NostrCredential
import fr.acinq.phoenix.utils.PlatformContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.cancelAndWait
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.extensions.getGlobalPrefs
import com.machankura.dedun.compose.ui.view.model.CreateProfileViewModel
import com.machankura.dedun.compose.ui.view.model.NavigationViewModel
import com.machankura.dedun.compose.ui.view.model.NotaryViewModel
import com.machankura.dedun.compose.ui.view.model.SignInToProfileViewModel
import com.machankura.dedun.compose.ui.view.model.SovereignWalletViewModel
import com.machankura.dedun.compose.ui.view.model.WritingSeedState
import com.machankura.dedun.compose.ui.view.model.bareKeyProfileWriter
import com.machankura.dedun.compose.ui.view.model.seedProfileWriter
import com.machankura.dedun.compose.ui.view.state.CreateProfileUIState
import com.machankura.dedun.compose.ui.view.state.NavigationUIState
import com.machankura.dedun.compose.ui.view.state.StartupChoice
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Several profiles, both ways, and a boot -- Phase 8 of docs/multiple-profiles.md, the
 * seams of Phases 1 through 7 in a row, through the real view models on an in-memory
 * database with a real key store.
 *
 * A is made from a phrase, the way Landing makes the first profile: both files written,
 * listed once with a wallet attached. A is opened with a node behind it -- a
 * PhoenixBusiness that was never started, which is enough to make it one the switch
 * has to stop. B's npub is signed in from inside; the switch stops A's node, B is open
 * and read-only, and the machine routes it from its placeholder. A kind 1 queued for A
 * while B is open waits, and goes out when A is back. C is created from inside as a bare
 * key, with no node. B and C are forgotten; one profile remains, the default is cleared,
 * and a fresh view model over the same directory -- what a cold boot is -- lists A alone,
 * with its credential still the only entry in the file, and opens it without asking.
 */
class MultipleProfilesRoundTripJvmTest {

    private lateinit var storeDir: File
    private lateinit var appDir: File
    private lateinit var phoenixGlobal: PhoenixGlobal

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val nostrRepository = DatabaseNostrRepository(db, scope)
    private val chatRepository = DatabaseChatRepository(db, scope)
    private val marmotRepository = DatabaseMarmotRepository(db, scope)

    private val keyB = PrivateKey(Lightning.randomBytes(32))
    private val stopped = mutableListOf<WalletId>()

    @BeforeTest
    fun setUp() {
        storeDir = Files.createTempDirectory("dedun-profiles-store").toFile()
        appDir = Files.createTempDirectory("dedun-profiles-app").toFile()
        JvmKeyStore.lock()
        JvmKeyStore.unlock("correct horse battery staple".toCharArray(), storeDir)
        // No GlobalPrefs of the test's own -- see AddProfileFromInsideJvmTest.setUp.
        phoenixGlobal = PhoenixGlobal(PlatformContext(applicationDir = appDir))
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndWait()
        db.close()
        JvmKeyStore.lock()
        storeDir.deleteRecursively()
        appDir.deleteRecursively()
    }

    private val dataStoreManager by lazy { DataStoreManager(phoenixGlobal.ctx, chain = NodeParamsManager.chain) }

    private suspend fun listed(sovereign: SovereignWalletViewModel, size: Int): Map<WalletId, StoredIdentity> {
        val done = CompletableDeferred<Unit>()
        sovereign.listIdentities { done.complete(Unit) }
        withTimeout(15_000) { done.await() }
        return sovereign.availableIdentities.first { it.size == size }
    }

    /** What the create screen does with a writer, minus the screen. */
    private suspend fun create(writer: NewProfileWriter, name: String): WalletId {
        val created = CompletableDeferred<WalletId>()
        val viewModel = CreateProfileViewModel(
            initialCreateProfileUIState = CreateProfileUIState.ConfirmInput(name = name, bio = "round trip"),
            nostrRepository = nostrRepository,
            marmotRepository = marmotRepository,
        )
        viewModel.createProfileFormState.nameField.textFieldState.edit { append(name) }
        viewModel.createProfileFormState.biographyField.textFieldState.edit { append("round trip") }
        viewModel.createAccount(writer) { created.complete(it) }
        return withTimeout(15_000) { created.await() }
    }

    /** What startup does for each kind, minus the node: a wallet-attached profile gets a business it can stop. */
    private fun activated(stored: StoredIdentity): Identity = when (stored) {
        is StoredIdentity.Mnemonic -> Identity.signing(
            id = stored.id, kind = IdentityKind.Mnemonic, nostrPrivateKey = stored.privateKey,
            userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
            internalPrefs = dataStoreManager.loadInternalPrefsForWallet(stored.id),
            business = PhoenixBusiness(phoenixGlobal),
        )
        is StoredIdentity.NostrSecret -> Identity.signing(
            id = stored.id, kind = IdentityKind.NostrSecret, nostrPrivateKey = stored.privateKey,
            userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
            internalPrefs = dataStoreManager.loadInternalPrefsForWallet(stored.id),
            business = null,
        )
        is StoredIdentity.NostrPublic -> Identity.readOnly(
            id = stored.id, nostrPublicKey = stored.nostrPublicKey,
            userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
            internalPrefs = dataStoreManager.loadInternalPrefsForWallet(stored.id),
        )
    }

    /** Whether a navigation state is about [publicKey]'s account, whichever step it is at. */
    private suspend fun NavigationUIState.isFor(publicKey: String): Boolean = when (this) {
        is NavigationUIState.UnsignedProfile -> unsignedNostrEvent.pubKey == publicKey
        is NavigationUIState.UnqueuedProfileSynchronization -> unsignedNostrEvent.pubKey == publicKey
        is NavigationUIState.UnsyncedProfile -> unsignedNostrEvent.pubKey == publicKey
        is NavigationUIState.UnannouncedProfile -> db.nostrEventDao().getNostrEventById(broadcastNostrEventRequest.nostrEventId)?.pubKey == publicKey
        is NavigationUIState.ProfileLoaded -> this.publicKey == publicKey
        else -> false
    }

    private suspend fun NavigationViewModel.awaitRoutedTo(publicKey: String): NavigationUIState = withTimeout(20_000) {
        navigationUIState.first { it.isFor(publicKey) }
    }

    private suspend fun queueNote(publicKey: String, content: String) {
        val now = Clock.System.now()
        db.unsignedNostrEventDao().upsert(
            UnsignedNostrEvent(pubKey = publicKey, kind = 1, tags = emptyArray(), content = content, createdAt = now, updatedAt = now, savedAt = now)
        )
    }

    private suspend fun notesQueuedFor(publicKey: String) =
        nostrRepository.observeUnsignedNostrEvents(publicKey).first().filter { it.kind == 1 }

    @Test
    fun `several profiles, both ways, and a boot`() = runBlocking<Unit> {
        val sovereign = SovereignWalletViewModel(phoenixGlobal, stopBusiness = { stopped += it })
        val navigation = NavigationViewModel(
            activeIdentityStateFlow = sovereign.activeIdentity,
            initialNavigationUIState = NavigationUIState.Loading(""),
            nostrRepository = nostrRepository,
            scope = scope,
        )
        NotaryViewModel(
            activeIdentityStateFlow = sovereign.activeIdentity,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            marmotRepository = marmotRepository,
            scope = scope,
        )
        val signIn = SignInToProfileViewModel(
            nostrRepository = nostrRepository,
            writeNostrKey = { key -> sovereign.writeNostrKey(key) },
            writeNostrPublicKey = { pubkey -> sovereign.writeNostrPublicKey(pubkey) },
            writeRecoveryPhrase = { _, _, onError -> onError(WritingSeedState.Error.CannotLoadSeedMap) },
        )

        // A, from a phrase: both files written, listed once with a wallet attached.
        val idA = create(sovereign.seedProfileWriter(), "Ada")
        val storedA = assertIs<StoredIdentity.Mnemonic>(listed(sovereign, 1)[idA], "a profile with a wallet attached, under the wallet's id")
        val credentials = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)
        assertEquals(mapOf(storedA.nostrPublicKey to NostrCredential.Secret(storedA.privateKey)), credentials, "the seed's key is a credential")
        sovereign.setActiveIdentity(activated(storedA))
        navigation.awaitRoutedTo(storedA.nostrPublicKey)

        // B, an npub, signed in from inside: the switch stops A's node; B is open, read-only, and routed.
        val signedInB = assertIs<SignInToProfileViewModel.Outcome.SignedIn>(
            signIn.commit(SignInCredential.NostrPublicKey(keyB.nostrPublicKeyHex()))
        )
        val storedB = assertIs<StoredIdentity.NostrPublic>(listed(sovereign, 2)[signedInB.id])
        sovereign.switchToIdentity(signedInB.id)
        withTimeout(5_000) { while (stopped.isEmpty()) delay(20) }
        assertEquals(listOf(idA), stopped, "leaving A stopped A's node")
        sovereign.setActiveIdentity(activated(storedB))
        assertIs<NavigationUIState.UnqueuedProfileSynchronization>(navigation.awaitRoutedTo(storedB.nostrPublicKey))
        assertTrue(BusinessManager.businessFlow.value.isEmpty(), "nothing was ever started for a public key")

        // What A queues while B is open waits for A.
        queueNote(storedA.nostrPublicKey, "while B is open")
        delay(3_000)
        assertEquals(1, notesQueuedFor(storedA.nostrPublicKey).size, "not B's to sign")

        // Back to A: routed, and the note goes out. B had no node, so nothing more was stopped.
        sovereign.switchToIdentity(idA)
        sovereign.setActiveIdentity(activated(storedA))
        navigation.awaitRoutedTo(storedA.nostrPublicKey)
        withTimeout(15_000) { nostrRepository.observeUnsignedNostrEvents(storedA.nostrPublicKey).first { rows -> rows.none { it.kind == 1 } } }
        assertEquals(listOf(idA), stopped)

        // C, created from inside: a bare key, no node, and the switch stops A's node again.
        val idC = create(sovereign.bareKeyProfileWriter(), "Cy")
        val storedC = assertIs<StoredIdentity.NostrSecret>(listed(sovereign, 3)[idC], "a key, not a second wallet")
        sovereign.switchToIdentity(idC)
        withTimeout(5_000) { while (stopped.size < 2) delay(20) }
        assertEquals(listOf(idA, idA), stopped)
        sovereign.setActiveIdentity(activated(storedC))
        navigation.awaitRoutedTo(storedC.nostrPublicKey)
        assertTrue(BusinessManager.businessFlow.value.isEmpty(), "no node for a bare key")

        // Forget C, the one that is open, then B: the forget tail, minus the navigation.
        for (leaving in listOf(activated(storedC), activated(storedB))) {
            val outcome = ForgetIdentity.forget(
                identity = leaving,
                nostrRepository = nostrRepository,
                forgetNostrCredential = { sovereign.forgetNostrCredential(it) },
                hideIdentityMetadata = { sovereign.hideIdentityMetadata(it) },
            )
            assertEquals(ForgetIdentity.Outcome.Forgotten, outcome)
        }
        sovereign.forgetDefaultIdentity()
        sovereign.resetToSelector()
        assertNull(sovereign.activeIdentity.value)
        assertEquals(setOf(idA), listed(sovereign, 1).keys)
        assertEquals(
            mapOf(storedA.nostrPublicKey to NostrCredential.Secret(storedA.privateKey)),
            NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal),
            "A's credential is the only entry left",
        )
        assertEquals(1, nostrRepository.getLocalAccounts().size, "B's and C's account rows went with them")
        val prefs = getGlobalPrefs(phoenixGlobal)
        withTimeout(5_000) { prefs.getDefaultWallet.first { it == EmptyWalletId } }

        // And A cannot be forgotten: a wallet is attached.
        assertEquals(
            ForgetIdentity.Outcome.WalletAttached,
            ForgetIdentity.forget(activated(storedA), nostrRepository, { sovereign.forgetNostrCredential(it) }, { sovereign.hideIdentityMetadata(it) }),
        )

        // A cold boot: a fresh view model over the same directory. The repair finds nothing
        // to do, A is listed alone, and startup opens it without asking.
        val booted = SovereignWalletViewModel(phoenixGlobal, stopBusiness = { stopped += it })
        val identities = withTimeout(15_000) { booted.availableIdentities.first { it.isNotEmpty() } }
        assertEquals(setOf(idA), identities.keys)
        assertEquals(storedA.privateKey, assertIs<StoredIdentity.Mnemonic>(identities[idA]).privateKey, "listed from the credential, not derived")
        val opens = StartupChoice.resolve(
            force = null,
            desired = null,
            startImmediately = true,
            identities = identities,
            default = prefs.getDefaultWallet.first() as? WalletId,
        )
        assertEquals(idA, opens?.id)
    }
}
