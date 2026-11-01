package com.machankura.dedun.compose.ui.view.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalAccount
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.toWalletId
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.SovereignWalletStartupRoute
import com.machankura.dedun.compose.ui.view.state.NavigationUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * An identity with no node behind it is routed like any other.
 *
 * `NavigationViewModel.observeProfile` used to reach the active user's pubkey through
 * the wallet -- `business.walletManager.keyManager.value.nostrPublicKey()` -- and
 * answered a null `business` by navigating back to startup. For a wallet that is a
 * loop the startup screen breaks by starting the node; for an identity that will
 * never have a node it is a loop with no exit. The [Identity] type carries the key
 * itself, so the branch is gone, and this pins that it stays gone: a `NostrSecret`
 * identity with a signed, synced account on the device lands on `ProfileLoaded`.
 *
 * A jvm test rather than a common one because [Identity] derives its public key on
 * construction, which takes the secp256k1 natives, and those are on this classpath
 * (see the other tests in `jvmTest` that build a `PrivateKey`).
 *
 * The observer waits 2.1 seconds on `Dispatchers.IO` before its first read, so this
 * runs under `runBlocking` and a real-time `withTimeout`: `runTest` would advance a
 * virtual clock past the timeout while the real delay had not elapsed.
 */
class NavigationIdentityRoutingJvmTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val stranded = NavigationUIState.Loading("Introducing... Torch")

    private val privateKey = PrivateKey(ByteVector32("01".repeat(32)))

    private fun prefsStore(name: String) = PreferenceDataStoreFactory.createWithPath {
        temporaryFolder.newFolder().resolve("$name.preferences_pb").path.toPath()
    }

    private fun nostrSecretIdentity() = Identity.signing(
        id = privateKey.publicKey().xOnly().toWalletId(),
        kind = IdentityKind.NostrSecret,
        nostrPrivateKey = privateKey,
        userPrefs = UserPrefs(prefsStore("user")),
        internalPrefs = InternalPrefs(prefsStore("internal")),
        business = null,
    )

    /** The same key, held as a public key only: same id, no secret, no node. */
    private fun readOnlyIdentity() = Identity.readOnly(
        id = privateKey.publicKey().xOnly().toWalletId(),
        nostrPublicKey = privateKey.nostrPublicKeyHex(),
        userPrefs = UserPrefs(prefsStore("user")),
        internalPrefs = InternalPrefs(prefsStore("internal")),
    )

    /** Everything `observeProfile` reads, and nothing else; every other member throws. */
    private class Device(
        private val accounts: List<LocalAccount>
    ) : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        override suspend fun getLocalAccounts(): List<LocalAccount> = accounts
        override suspend fun observeLocalAccount(publicKey: HexKey): Flow<LocalAccount?> =
            flowOf(accounts.firstOrNull { it.unsignedNostrEvent?.pubKey == publicKey })
    }

    private val at = Instant.fromEpochSeconds(1_700_000_000)

    private fun syncedAccount(publicKey: HexKey) = LocalAccount(
        unsignedNostrEvent = UnsignedNostrEvent(
            id = 1,
            pubKey = publicKey,
            kind = 0,
            tags = emptyArray(),
            content = "{}",
            signedAt = at,
            createdAt = at,
            updatedAt = at,
            savedAt = at,
        ),
        nostrEvent = null,
        profile = Profile(
            publicKey = publicKey,
            nostrEventId = "e".repeat(64),
            createdAt = at,
            updatedAt = at,
            savedAt = at,
        ),
        broadcastNostrEventRequest = null,
        broadcastNostrEventReceipt = null,
        synchronizeNostrEventRequests = emptyList(),
    )

    @Test
    fun `the identity's public key is the x-only key nostr uses`() {
        val identity = nostrSecretIdentity()

        // The same key quartz derives, so the notary's KeyPair and this agree on who
        // the user is. Not the 33-byte compressed form the library's own helper returns.
        assertEquals(
            KeyPair(privKey = privateKey.value.toByteArray()).pubKey.toHexKey(),
            identity.nostrPublicKey,
        )
        assertEquals(64, identity.nostrPublicKey.length)
        // Same shape as a wallet's id: forty hex characters of a hash160.
        assertEquals(40, identity.id.nodeIdHash.length)
    }

    @Test
    fun `an identity with no node behind it is routed by its account, not back to startup`() {
        val identity = nostrSecretIdentity()
        val scope = CoroutineScope(Job())
        val viewModel = NavigationViewModel(
            activeIdentityStateFlow = MutableStateFlow<Identity?>(identity),
            initialNavigationUIState = stranded,
            nostrRepository = Device(listOf(syncedAccount(identity.nostrPublicKey))),
            scope = scope,
        )

        try {
            val destination = runBlocking {
                withTimeout(15_000) { viewModel.navigationUIState.first { it != stranded } }
            }

            assertEquals(
                NavigationUIState.ProfileLoaded(publicKey = identity.nostrPublicKey),
                destination,
                "a nodeless identity was not routed by its account",
            )
        } finally {
            scope.cancel()
        }
    }

    /**
     * The startup screen's own entrance, `loadNostrProfile(startupRoute)`, used to take
     * the *first* kind-0 account on the device whichever identity was active. With one
     * wallet that was harmless; with two it is the wrong account half the time, and an
     * imported key is how a device gets its second identity.
     */
    @Test
    fun `with two accounts on the device, the startup entrance reads the active identity's`() {
        val identity = nostrSecretIdentity()
        val someoneElse = "a".repeat(64)
        val scope = CoroutineScope(Job())
        val viewModel = NavigationViewModel(
            activeIdentityStateFlow = MutableStateFlow<Identity?>(identity),
            initialNavigationUIState = stranded,
            nostrRepository = Device(listOf(syncedAccount(someoneElse), syncedAccount(identity.nostrPublicKey))),
            scope = scope,
        ).also { scope.cancel() }

        runBlocking { viewModel.loadNostrProfile(SovereignWalletStartupRoute) }

        assertEquals(
            NavigationUIState.ProfileLoaded(publicKey = identity.nostrPublicKey),
            viewModel.navigationUIState.value,
        )
    }

    /**
     * A read-only identity is the same identity minus the secret: same public key, same
     * id, and the machine routes it by its account exactly as it routes the nsec.
     */
    @Test
    fun `a read-only identity has the id and public key its secret would, and cannot sign`() {
        val readOnly = readOnlyIdentity()
        val signing = nostrSecretIdentity()

        assertEquals(signing.nostrPublicKey, readOnly.nostrPublicKey)
        assertEquals(signing.id, readOnly.id)
        assertNull(readOnly.nostrPrivateKey)
        assertNull(readOnly.business)
        assertFalse(readOnly.canSign)
        assertTrue(signing.canSign)
        assertEquals(IdentityKind.NostrPublic, readOnly.kind)
    }

    @Test
    fun `a read-only identity is routed by its account, like any other`() {
        val identity = readOnlyIdentity()
        val scope = CoroutineScope(Job())
        val viewModel = NavigationViewModel(
            activeIdentityStateFlow = MutableStateFlow<Identity?>(identity),
            initialNavigationUIState = stranded,
            nostrRepository = Device(listOf(syncedAccount(identity.nostrPublicKey))),
            scope = scope,
        )

        try {
            val destination = runBlocking {
                withTimeout(15_000) { viewModel.navigationUIState.first { it != stranded } }
            }

            assertEquals(
                NavigationUIState.ProfileLoaded(publicKey = identity.nostrPublicKey),
                destination,
                "a read-only identity was not routed by its account",
            )
        } finally {
            scope.cancel()
        }
    }

    /**
     * The two checks that make [Identity.nostrPublicKey] safe to be a field. Without
     * them a data class would accept a key filed under someone else's public key, or a
     * "read-only" identity that could in fact sign.
     */
    @Test
    fun `an identity whose kind and key disagree is refused, and so is one whose key does not derive its public key`() {
        val prefs = UserPrefs(prefsStore("user"))
        val internal = InternalPrefs(prefsStore("internal"))

        // A secret filed under a public key it does not derive.
        assertFailsWith<IllegalArgumentException> {
            Identity(
                id = privateKey.publicKey().xOnly().toWalletId(),
                kind = IdentityKind.NostrSecret,
                nostrPublicKey = "a".repeat(64),
                nostrPrivateKey = privateKey,
                userPrefs = prefs,
                internalPrefs = internal,
                business = null,
            )
        }
        // A read-only kind that nonetheless carries a key.
        assertFailsWith<IllegalArgumentException> {
            Identity(
                id = privateKey.publicKey().xOnly().toWalletId(),
                kind = IdentityKind.NostrPublic,
                nostrPublicKey = privateKey.nostrPublicKeyHex(),
                nostrPrivateKey = privateKey,
                userPrefs = prefs,
                internalPrefs = internal,
                business = null,
            )
        }
        // A signing kind with no key to sign with.
        assertFailsWith<IllegalArgumentException> {
            Identity(
                id = privateKey.publicKey().xOnly().toWalletId(),
                kind = IdentityKind.NostrSecret,
                nostrPublicKey = privateKey.nostrPublicKeyHex(),
                nostrPrivateKey = null,
                userPrefs = prefs,
                internalPrefs = internal,
                business = null,
            )
        }
        // And the factory refuses to build a "signing" read-only identity at all.
        assertFailsWith<IllegalArgumentException> {
            Identity.signing(
                id = privateKey.publicKey().xOnly().toWalletId(),
                kind = IdentityKind.NostrPublic,
                nostrPrivateKey = privateKey,
                userPrefs = prefs,
                internalPrefs = internal,
                business = null,
            )
        }
    }
}
