package com.machankura.dedun.compose.identity

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.Lightning
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.jvm.BusinessManager
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.managers.computePreferencePath
import fr.acinq.phoenix.security.JvmKeyStore
import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalAccount
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.model.NavigationViewModel
import com.machankura.dedun.compose.ui.view.state.NavigationUIState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The path a user takes, end to end, with the network faked at the repository.
 *
 * Unlock the key store into a temporary directory, write an nsec the way the sign-in
 * screen does, list identities the way startup does, activate the one that came back,
 * and hand NavigationViewModel a device holding the placeholder account the sign-in
 * planted: it must land on UnqueuedProfileSynchronization. Then the same account with
 * a kind 0 indexed: ProfileLoaded. Phases 2 through 5 each pin one seam; this is the
 * seams in a row.
 *
 * And the assertion nothing in the UI would reveal: no Lightning node ran. The jvm
 * BusinessManager's flow of running businesses is empty afterwards, and the identity
 * has no business behind it.
 */
class NsecRestoreRoundTripJvmTest {

    private lateinit var storeDir: File
    private lateinit var appDir: File
    private lateinit var phoenixGlobal: PhoenixGlobal
    private lateinit var globalPrefs: GlobalPrefs

    private val log = Logger.withTag("NsecRestoreRoundTripJvmTest")
    private val privateKey = PrivateKey(Lightning.randomBytes(32))
    private val at = Instant.fromEpochSeconds(1_700_000_000)

    @BeforeTest
    fun setUp() {
        storeDir = Files.createTempDirectory("dedun-round-trip-store").toFile()
        appDir = Files.createTempDirectory("dedun-round-trip-app").toFile()
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
        JvmKeyStore.lock()
        storeDir.deleteRecursively()
        appDir.deleteRecursively()
    }

    /** A device whose one account can change under the observer, as rows do. */
    private class Device(initial: LocalAccount?) : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        val account = MutableStateFlow(initial)
        override suspend fun getLocalAccounts(): List<LocalAccount> = listOfNotNull(account.value)
        override suspend fun observeLocalAccount(publicKey: HexKey): Flow<LocalAccount?> = account
    }

    private fun placeholder(publicKey: HexKey) = LocalAccount(
        unsignedNostrEvent = UnsignedNostrEvent(
            id = 1, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}",
            signedAt = GENESIS_AT, createdAt = at, updatedAt = at, savedAt = at,
        ),
        nostrEvent = null,
        profile = null,
        broadcastNostrEventRequest = null,
        broadcastNostrEventReceipt = null,
        synchronizeNostrEventRequests = emptyList(),
    )

    private fun synced(publicKey: HexKey) = placeholder(publicKey).copy(
        profile = Profile(publicKey = publicKey, nostrEventId = "e".repeat(64), createdAt = at, updatedAt = at, savedAt = at),
    )

    @Test
    fun `an nsec written, listed, activated and routed, with no node started`() = runBlocking<Unit> {
        // 1. The sign-in screen's commit, minus the screen.
        val written = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(
            IdentityWriter.writeNostrKey(log, phoenixGlobal, globalPrefs, privateKey, isTorEnabled = false, customElectrumServer = null)
        )

        // 2. What startup lists: both stores, merged.
        val identities = StoredIdentity.merge(
            wallets = SeedManager.loadAndDecryptOrNull(phoenixGlobal) ?: error("seed store unreadable"),
            credentials = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal) ?: error("key store unreadable"),
        )
        val stored = assertIs<StoredIdentity.NostrSecret>(identities[written.id], "the imported key is listed under the id the writer returned")

        // 3. What the startup screen's NostrSecret branch does: an identity, no node.
        val dataStoreManager = DataStoreManager(phoenixGlobal.ctx, chain = NodeParamsManager.chain)
        val identity = Identity.signing(
            id = stored.id,
            kind = IdentityKind.NostrSecret,
            nostrPrivateKey = stored.privateKey,
            userPrefs = dataStoreManager.loadUserPrefsForWallet(stored.id),
            internalPrefs = dataStoreManager.loadInternalPrefsForWallet(stored.id),
            business = null,
        )
        val activeIdentity = MutableStateFlow<Identity?>(null)
        val device = Device(placeholder(identity.nostrPublicKey))
        val scope = CoroutineScope(Job())
        val navigation = NavigationViewModel(
            activeIdentityStateFlow = activeIdentity,
            initialNavigationUIState = NavigationUIState.Loading("Introducing... Torch"),
            nostrRepository = device,
            scope = scope,
        )

        try {
            activeIdentity.value = identity

            // 4. The machine takes it from the placeholder: fetch, do not create.
            val first = withTimeout(15_000) {
                navigation.navigationUIState.first { it !is NavigationUIState.Loading }
            }
            assertEquals(
                NavigationUIState.UnqueuedProfileSynchronization(unsignedNostrEvent = placeholder(identity.nostrPublicKey).unsignedNostrEvent!!),
                first,
            )

            // 5. The relays answered, the kind 0 was indexed: home.
            device.account.value = synced(identity.nostrPublicKey)
            val second = withTimeout(15_000) {
                navigation.navigationUIState.first { it is NavigationUIState.ProfileLoaded }
            }
            assertEquals(NavigationUIState.ProfileLoaded(publicKey = identity.nostrPublicKey), second)
        } finally {
            scope.cancel()
        }

        // 6. Nothing Lightning ran.
        assertNull(identity.business)
        assertTrue(BusinessManager.businessFlow.value.isEmpty(), "no PhoenixBusiness was started for a bare key")
    }
}
