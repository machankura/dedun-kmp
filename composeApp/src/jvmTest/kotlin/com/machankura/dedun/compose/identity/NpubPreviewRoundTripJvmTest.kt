package com.machankura.dedun.compose.identity

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.Lightning
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.jvm.BusinessManager
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.managers.computePreferencePath
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.JvmKeyStore
import fr.acinq.phoenix.security.NostrCredential
import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.cancelAndWait
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.ui.view.model.NavigationViewModel
import com.machankura.dedun.compose.ui.view.model.NotaryViewModel
import com.machankura.dedun.compose.ui.view.model.SignInToProfileViewModel
import com.machankura.dedun.compose.ui.view.state.NavigationUIState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.machankura.dedun.compose.nostr.MarmotProfile
import kotlin.time.Instant

/**
 * The preview, end to end, and then the upgrade -- Phase 7 of docs/npub-sign-in.md.
 *
 * Unlike `NsecRestoreRoundTripJvmTest`, the repository here is real: an in-memory
 * database under the same DAOs the app runs on, because the assertion this exists for
 * is about what is *not* in it. Sign in with an npub the way the sign-in screen does,
 * list identities the way startup does, activate the read-only one, hand
 * NavigationViewModel the device: it lands on UnqueuedProfileSynchronization. Index the
 * kind 0 the relays would have answered with, through the read-only key pair: it lands
 * on ProfileLoaded. And with a real NotaryViewModel running against the same database
 * for longer than its key package delay: **nothing was signed** -- the only unsigned row
 * for the pubkey is the placeholder, still at genesis; no key package bundle; no
 * broadcast request; no node.
 *
 * Then the nsec of the same key is written the way the sign-in screen writes it: the
 * same id, one entry that is now a secret, and one account -- the second sign-in
 * planted nothing.
 */
class NpubPreviewRoundTripJvmTest {

    private lateinit var storeDir: File
    private lateinit var appDir: File
    private lateinit var phoenixGlobal: PhoenixGlobal
    private lateinit var globalPrefs: GlobalPrefs

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val nostrRepository = DatabaseNostrRepository(db, scope)
    private val chatRepository = DatabaseChatRepository(db, scope)
    private val marmotRepository = DatabaseMarmotRepository(db, scope)

    private val log = Logger.withTag("NpubPreviewRoundTripJvmTest")
    private val privateKey = PrivateKey(Lightning.randomBytes(32))
    private val publicKey = privateKey.nostrPublicKeyHex()
    private val at = Instant.fromEpochSeconds(1_700_000_000)

    @BeforeTest
    fun setUp() {
        storeDir = Files.createTempDirectory("dedun-preview-store").toFile()
        appDir = Files.createTempDirectory("dedun-preview-app").toFile()
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
        scope.cancelAndWait()
        db.close()
        JvmKeyStore.lock()
        storeDir.deleteRecursively()
        appDir.deleteRecursively()
    }

    private fun signInViewModel() = SignInToProfileViewModel(
        nostrRepository = nostrRepository,
        writeNostrKey = { key -> IdentityWriter.writeNostrKey(log, phoenixGlobal, globalPrefs, key, isTorEnabled = false, customElectrumServer = null) },
        writeNostrPublicKey = { pubkey -> IdentityWriter.writeNostrPublicKey(log, phoenixGlobal, globalPrefs, pubkey, isTorEnabled = false, customElectrumServer = null) },
        writeRecoveryPhrase = { _, _, onError -> onError(com.machankura.dedun.compose.ui.view.model.WritingSeedState.Error.CannotLoadSeedMap) },
    )

    private fun listed() = StoredIdentity.merge(
        wallets = SeedManager.loadAndDecryptOrNull(phoenixGlobal) ?: error("seed store unreadable"),
        credentials = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal) ?: error("credentials unreadable"),
    )

    /** The kind 0 the relays would answer the sign-in sync with. */
    private fun kind0() = NostrEvent(
        id = "e".repeat(64),
        pubKey = publicKey,
        kind = MetadataEvent.KIND,
        tags = emptyArray(),
        content = """{"name":"Reader"}""",
        sig = "0".repeat(128),
        createdAt = at,
    )

    @Test
    fun `an npub signed in, listed, activated, routed and watched by the notary, with nothing signed`() = runBlocking<Unit> {
        // 1. The sign-in screen's commit, minus the screen: the credential, then the account.
        val outcome = signInViewModel().commit(SignInCredential.NostrPublicKey(publicKey))
        val signedIn = assertIs<SignInToProfileViewModel.Outcome.SignedIn>(outcome)

        // 2. What startup lists.
        val stored = assertIs<StoredIdentity.NostrPublic>(listed()[signedIn.id], "the public key is listed under the id the writer returned")
        assertEquals(publicKey, stored.nostrPublicKey)

        // 3. What the startup screen's NostrPublic branch does: an identity with nothing behind it.
        val dataStoreManager = DataStoreManager(phoenixGlobal.ctx, chain = NodeParamsManager.chain)
        val identity = Identity.readOnly(
            id = stored.id,
            nostrPublicKey = stored.nostrPublicKey,
            userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
            internalPrefs = dataStoreManager.loadInternalPrefsForWallet(stored.id),
        )
        assertFalse(identity.canSign)
        assertNull(identity.nostrPrivateKey)

        val activeIdentity = MutableStateFlow<Identity?>(null)
        val navigation = NavigationViewModel(
            activeIdentityStateFlow = activeIdentity,
            initialNavigationUIState = NavigationUIState.Loading("Introducing... Torch"),
            nostrRepository = nostrRepository,
            scope = scope,
        )
        // The real notary, watching the same rows the app's would.
        NotaryViewModel(
            activeIdentityStateFlow = activeIdentity,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            marmotRepository = marmotRepository,
            scope = scope,
        )

        activeIdentity.value = identity

        // 4. The machine takes it from the placeholder: fetch, do not create.
        val first = withTimeout(15_000) {
            navigation.navigationUIState.first { it !is NavigationUIState.Loading }
        }
        assertIs<NavigationUIState.UnqueuedProfileSynchronization>(first)

        // 5. The relays answered; the kind 0 is indexed through the read-only pair: home.
        db.nostrDao().storeNostrEvent(
            nostrEvent = kind0(),
            relayURL = "wss://relay.example",
            synchronizationRelayURLs = listOf("wss://relay.example"),
            level = 0,
            activeKeyPair = identity.toKeyPair(),
        )
        val second = withTimeout(15_000) {
            navigation.navigationUIState.first { it is NavigationUIState.ProfileLoaded }
        }
        assertEquals(NavigationUIState.ProfileLoaded(publicKey = publicKey), second)

        // 6. Longer than the notary's key package delay, and then: nothing was signed.
        delay(4_500)
        val accounts = nostrRepository.getLocalAccounts().filter { it.unsignedNostrEvent?.pubKey == publicKey }
        assertEquals(1, accounts.size, "one account for the pubkey")
        assertEquals(GENESIS_AT, accounts.single().unsignedNostrEvent!!.signedAt, "the placeholder is still the placeholder")
        assertEquals(emptyList(), nostrRepository.observeUnsignedNostrEvents(publicKey).first(), "nothing is queued for a signature")
        assertEquals(emptyList(), db.marmotKeyPackageBundleDao().getAllMarmotKeyPackageBundles(publicKey), "no key package bundle was made")
        assertNull(db.broadcastNostrEventRequestDao().observeBroadcastNostrEventRequestsByStatus("pending").first(), "nothing is waiting for a relay")
        assertNull(identity.business)
        assertTrue(BusinessManager.businessFlow.value.isEmpty(), "no PhoenixBusiness was started for a public key")
    }

    /**
     * The contrast that makes the assertions above worth having: the same harness, as an
     * identity that *can* sign, does sign -- the notary makes a key package bundle for it
     * within its delay. Without this, "nothing was signed" could be true of a harness in
     * which nothing can be signed.
     */
    @Test
    fun `the same harness as a signing identity does sign`() = runBlocking<Unit> {
        val signedIn = assertIs<SignInToProfileViewModel.Outcome.SignedIn>(
            signInViewModel().commit(SignInCredential.NostrSecret(privateKey, publicKey))
        )
        val stored = assertIs<StoredIdentity.NostrSecret>(listed()[signedIn.id])
        val dataStoreManager = DataStoreManager(phoenixGlobal.ctx, chain = NodeParamsManager.chain)
        val identity = Identity.signing(
            id = stored.id,
            kind = IdentityKind.NostrSecret,
            nostrPrivateKey = stored.privateKey,
            userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
            internalPrefs = dataStoreManager.loadInternalPrefsForWallet(stored.id),
            business = null,
        )
        val activeIdentity = MutableStateFlow<Identity?>(identity)
        NotaryViewModel(
            activeIdentityStateFlow = activeIdentity,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            marmotRepository = marmotRepository,
            scope = scope,
        )

        // One per profile: the notary keeps a current-profile bundle and a legacy one
        // publishable at all times -- docs/marmot-v2.md, Phase 3.
        val bundles = withTimeout(20_000) {
            marmotRepository.observeActiveMarmotKeyPackageBundles(publicKey).first { it.size >= 2 }
        }
        assertEquals(
            MarmotProfile.entries.toSet(),
            bundles.map { it.profileOrLegacy }.toSet(),
            "the notary did not publish one bundle per profile",
        )
        assertTrue(db.marmotKeyPackageBundleDao().getAllMarmotKeyPackageBundles(publicKey).isNotEmpty())
    }

    @Test
    fun `the nsec of a key held read-only signs in over it, under the same id, planting nothing new`() = runBlocking<Unit> {
        val readOnly = assertIs<SignInToProfileViewModel.Outcome.SignedIn>(
            signInViewModel().commit(SignInCredential.NostrPublicKey(publicKey))
        )
        assertIs<StoredIdentity.NostrPublic>(listed()[readOnly.id])

        val upgraded = assertIs<SignInToProfileViewModel.Outcome.SignedIn>(
            signInViewModel().commit(SignInCredential.NostrSecret(privateKey, publicKey))
        )

        assertEquals(readOnly.id, upgraded.id, "the identity keeps its id, and with it its preferences and metadata")
        val credentials = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)
        assertEquals(mapOf(publicKey to NostrCredential.Secret(privateKey)), credentials, "one entry, now a secret")
        val identities = listed()
        assertEquals(1, identities.size)
        assertIs<StoredIdentity.NostrSecret>(identities[upgraded.id])
        assertEquals(1, nostrRepository.getLocalAccounts().count { it.unsignedNostrEvent?.pubKey == publicKey }, "the second sign-in planted nothing")
    }
}
