package com.machankura.dedun.compose.identity

import androidx.room3.Room
import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.Lightning
import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.jvm.BusinessManager
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.JvmKeyStore
import fr.acinq.phoenix.utils.PlatformContext
import com.machankura.dedun.compose.extensions.getGlobalPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.ui.view.model.NavigationViewModel
import com.machankura.dedun.compose.ui.view.model.NotaryViewModel
import com.machankura.dedun.compose.ui.view.model.SovereignWalletViewModel
import com.machankura.dedun.compose.ui.view.state.NavigationUIState
import com.machankura.dedun.compose.ui.view.state.StartupChoice
import fr.acinq.phoenix.data.EmptyWalletId
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
 * A switch, end to end -- Phase 2 of docs/multiple-profiles.md -- through the real
 * `SovereignWalletViewModel`, with a real notary and a real navigation machine on an
 * in-memory database.
 *
 * The assertion a switch is for: the notary signs as whoever is open and nobody else.
 * Two bare keys, A and B; A active, and a kind 1 queued for A is signed. Switch to B --
 * the identity clears, the machine goes to startup, B is activated the way startup
 * does -- and a kind 1 queued for A now waits. Switch back, and it goes out.
 *
 * And the one effect a switch has outside the identity flow: the node of the profile
 * being left is stopped, once, and only when there was one. The manager is injected as
 * a recorder, since a node cannot be started here; a `PhoenixBusiness` can be built
 * without one, because everything in it is lazy, and that is enough to make the branch
 * true.
 *
 * Then Phase 3's half: every activation saves which profile is open, so that a cold
 * boot -- a fresh view model over the same directory -- resolves to it without asking;
 * and a forget tail clears it, so the boot after shows the list.
 */
class IdentitySwitchJvmTest {

    private lateinit var storeDir: File
    private lateinit var appDir: File
    private lateinit var phoenixGlobal: PhoenixGlobal

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val nostrRepository = DatabaseNostrRepository(db, scope)
    private val chatRepository = DatabaseChatRepository(db, scope)
    private val marmotRepository = DatabaseMarmotRepository(db, scope)

    private val log = Logger.withTag("IdentitySwitchJvmTest")
    private val keyA = PrivateKey(Lightning.randomBytes(32))
    private val keyB = PrivateKey(Lightning.randomBytes(32))

    private val stopped = mutableListOf<WalletId>()

    @BeforeTest
    fun setUp() {
        storeDir = Files.createTempDirectory("dedun-switch-store").toFile()
        appDir = Files.createTempDirectory("dedun-switch-app").toFile()
        JvmKeyStore.lock()
        JvmKeyStore.unlock("correct horse battery staple".toCharArray(), storeDir)
        // No GlobalPrefs of the test's own -- see AddProfileFromInsideJvmTest.setUp: the
        // process-wide cache and a second instance over the same file is what DataStore
        // refuses, and which of the two this class gets depends on the order of the run.
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

    private suspend fun signIn(key: PrivateKey): WalletId {
        val written = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(
            IdentityWriter.writeNostrKey(log, phoenixGlobal, getGlobalPrefs(phoenixGlobal), key, isTorEnabled = false, customElectrumServer = null)
        )
        nostrRepository.signInToProfile(key.nostrPublicKeyHex())
        return written.id
    }

    /** What the startup screen's bare-key branch builds. */
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

    private suspend fun queueNote(key: PrivateKey, content: String) {
        val now = Clock.System.now()
        db.unsignedNostrEventDao().upsert(
            UnsignedNostrEvent(pubKey = key.nostrPublicKeyHex(), kind = 1, tags = emptyArray(), content = content, createdAt = now, updatedAt = now, savedAt = now)
        )
    }

    private suspend fun unsignedFor(key: PrivateKey) =
        nostrRepository.observeUnsignedNostrEvents(key.nostrPublicKeyHex()).first()

    @Test
    fun `the notary signs as whoever is open and nobody else`() = runBlocking<Unit> {
        val idA = signIn(keyA)
        val idB = signIn(keyB)

        val sovereign = SovereignWalletViewModel(phoenixGlobal, stopBusiness = { stopped += it })
        withTimeout(15_000) { sovereign.availableIdentities.first { it.size == 2 } }
        val storedA = assertIs<StoredIdentity.NostrSecret>(sovereign.availableIdentities.value[idA])
        val storedB = assertIs<StoredIdentity.NostrSecret>(sovereign.availableIdentities.value[idB])

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

        // A is open, and what A queues goes out.
        sovereign.setActiveIdentity(activated(storedA))
        queueNote(keyA, "first, as A")
        withTimeout(15_000) { nostrRepository.observeUnsignedNostrEvents(keyA.nostrPublicKeyHex()).first { it.isEmpty() } }

        // The switch: the identity clears, the machine goes to startup, and startup opens B.
        sovereign.switchToIdentity(idB)
        assertEquals(idB, sovereign.desiredWalletId.value)
        assertNull(sovereign.activeIdentity.value)
        assertTrue(sovereign.startWalletImmediately.value, "a switch is the user saying which one")
        withTimeout(15_000) { navigation.navigationUIState.first { it is NavigationUIState.StartupPhoenix } }
        sovereign.setActiveIdentity(activated(storedB))
        val routed = withTimeout(15_000) {
            navigation.navigationUIState.first { it is NavigationUIState.UnqueuedProfileSynchronization }
        }
        assertEquals(keyB.nostrPublicKeyHex(), (routed as NavigationUIState.UnqueuedProfileSynchronization).unsignedNostrEvent.pubKey)

        // What A queues while B is open waits for A.
        queueNote(keyA, "second, while B is open")
        delay(3_000)
        assertEquals(1, unsignedFor(keyA).size, "A's note is not B's to sign")
        assertNull(unsignedFor(keyA).single().signedAt)

        // And goes out when A is back.
        sovereign.switchToIdentity(idA)
        sovereign.setActiveIdentity(activated(storedA))
        withTimeout(15_000) { nostrRepository.observeUnsignedNostrEvents(keyA.nostrPublicKeyHex()).first { it.isEmpty() } }

        assertEquals(emptyList(), stopped, "bare keys have no node to stop")
        assertTrue(BusinessManager.businessFlow.value.isEmpty())
    }

    @Test
    fun `leaving a profile with a node behind it stops that node, once`() = runBlocking<Unit> {
        val sovereign = SovereignWalletViewModel(phoenixGlobal, stopBusiness = { stopped += it })
        val dataStoreManager = DataStoreManager(phoenixGlobal.ctx, chain = NodeParamsManager.chain)
        val walletId = WalletId("ab".repeat(20))
        val withNode = Identity.signing(
            id = walletId,
            kind = IdentityKind.Mnemonic,
            nostrPrivateKey = keyA,
            userPrefs = dataStoreManager.loadUserPrefsForWallet(walletId),
            internalPrefs = dataStoreManager.loadInternalPrefsForWallet(walletId),
            business = PhoenixBusiness(phoenixGlobal),
        )
        val other = StoredIdentity.nostrSecret(keyB).id

        sovereign.setActiveIdentity(withNode)
        sovereign.switchToIdentity(other)
        withTimeout(5_000) { while (stopped.isEmpty()) delay(20) }

        assertEquals(listOf(walletId), stopped)
        assertNull(sovereign.activeIdentity.value)
        assertEquals(other, sovereign.desiredWalletId.value)
    }

    @Test
    fun `the last profile opened is the one a cold boot resolves, until it is forgotten`() = runBlocking<Unit> {
        val idA = signIn(keyA)
        val idB = signIn(keyB)
        val first = SovereignWalletViewModel(phoenixGlobal, stopBusiness = { stopped += it })
        withTimeout(15_000) { first.availableIdentities.first { it.size == 2 } }
        val storedA = assertIs<StoredIdentity.NostrSecret>(first.availableIdentities.value[idA])
        val storedB = assertIs<StoredIdentity.NostrSecret>(first.availableIdentities.value[idB])

        first.setActiveIdentity(activated(storedA))
        first.switchToIdentity(idB)
        first.setActiveIdentity(activated(storedB))
        // Read through the view model's own GlobalPrefs, not this test's: DataStoreManager
        // caches the global preferences once per process, bound to whichever directory
        // was current when the first test in this JVM asked -- the same trap the nsec plan
        // recorded for user preferences. The app has one directory; a test JVM has many.
        val prefs = first.getGlobalPrefs()
        withTimeout(5_000) { prefs.getDefaultWallet.first { it == idB } }

        // A cold boot: a fresh view model over the same directory, nothing desired,
        // nothing forced, and the user has not asked for the list.
        val booted = SovereignWalletViewModel(phoenixGlobal, stopBusiness = { stopped += it })
        withTimeout(15_000) { booted.availableIdentities.first { it.size == 2 } }
        val resolved = StartupChoice.resolve(
            force = null,
            desired = booted.desiredWalletId.value,
            startImmediately = booted.startWalletImmediately.value,
            identities = booted.availableIdentities.value,
            default = prefs.getDefaultWallet.first() as? WalletId,
        )
        assertEquals(idB, resolved?.id, "the profile that was open is the one that opens")

        // The forget tail clears it; the boot after shows the list.
        booted.forgetDefaultIdentity()
        withTimeout(5_000) { prefs.getDefaultWallet.first { it == EmptyWalletId } }
        assertNull(
            StartupChoice.resolve(
                force = null,
                desired = null,
                startImmediately = true,
                identities = booted.availableIdentities.value,
                default = prefs.getDefaultWallet.first() as? WalletId,
            )
        )
    }

    @Test
    fun `switching from nothing, or from a bare key, stops nothing`() = runBlocking<Unit> {
        val sovereign = SovereignWalletViewModel(phoenixGlobal, stopBusiness = { stopped += it })
        val target = StoredIdentity.nostrSecret(keyB).id

        sovereign.switchToIdentity(target)
        sovereign.setActiveIdentity(activated(StoredIdentity.nostrSecret(keyA)))
        sovereign.switchToIdentity(target)
        delay(500)

        assertEquals(emptyList(), stopped)
    }
}
