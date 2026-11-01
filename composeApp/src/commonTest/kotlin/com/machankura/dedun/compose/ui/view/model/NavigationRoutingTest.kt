package com.machankura.dedun.compose.ui.view.model

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalAccount
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.SovereignWalletStartupRoute
import com.machankura.dedun.compose.ui.view.state.NavigationUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant

/**
 * Where a boot goes once it knows whose device it is on.
 *
 * `loadNostrProfile` is the only exit from a loading screen. Its callers
 * navigate to one first and then ask it where the user actually belongs, so a
 * pass through it that decides nothing does not fail -- it strands. The user is
 * left on a placeholder with nothing still running that would move them: the
 * startup screen has been navigated away from, so no wallet comes up, so the
 * flow that watches the wallet never emits again.
 *
 * That is what happened. The branch for a device that already had an account was
 * commented out when `NostrRepository.observeProfile` was renamed
 * `observeLocalAccount`, leaving only the no-account case doing any work. Anyone
 * who had ever made a profile could boot to "Introducing... Torch" and stay.
 *
 * So each of these starts the view model on exactly that screen and asks whether
 * the answer moved. Asserting the destination is the point, but the weaker
 * assertion underneath it -- that the state is no longer the one it started on
 * -- is the one the bug broke, and it is what makes a silently skipped branch
 * fail here rather than on someone's phone.
 */
class NavigationRoutingTest {

    private val publicKey: HexKey = "a".repeat(64)

    /** The screen the bug left people on, used as the starting state throughout. */
    private val stranded = NavigationUIState.Loading("Introducing... Torch")

    /**
     * Everything `loadNostrProfile` reads, and nothing else. The no-op
     * repository throws from all 41 members, so any call this does not name is a
     * failure rather than a quiet default.
     */
    private class Device(
        private val accounts: List<LocalAccount>
    ) : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        override suspend fun getLocalAccounts(): List<LocalAccount> = accounts

        override suspend fun observeLocalAccount(publicKey: HexKey): Flow<LocalAccount?> =
            flowOf(accounts.firstOrNull { it.unsignedNostrEvent?.pubKey == publicKey })
    }

    /**
     * Both entities default their timestamps to `Clock.System.now()` and both
     * compare them in `equals`, so the fixture pins them: two builds of the same
     * account have to be the same account for an assertion to mean anything.
     */
    private val at = Instant.fromEpochSeconds(1_700_000_000)

    private fun account(signed: Boolean) = LocalAccount(
        unsignedNostrEvent = UnsignedNostrEvent(
            id = 1,
            pubKey = publicKey,
            kind = 0,
            tags = emptyArray(),
            content = "{}",
            signedAt = if (signed) at else null,
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

    /**
     * What `signInToProfile` plants for a key that already exists elsewhere: a kind 0
     * stamped `GENESIS_AT` so the notary skips it, and nothing else -- no `Profile`
     * row, which only the notary's signing writes, and no sync request yet.
     */
    private fun placeholderAccount() = LocalAccount(
        unsignedNostrEvent = UnsignedNostrEvent(
            id = 1,
            pubKey = publicKey,
            kind = 0,
            tags = emptyArray(),
            content = "{}",
            signedAt = GENESIS_AT,
            createdAt = at,
            updatedAt = at,
            savedAt = at,
        ),
        nostrEvent = null,
        profile = null,
        broadcastNostrEventRequest = null,
        broadcastNostrEventReceipt = null,
        synchronizeNostrEventRequests = emptyList(),
    )

    /**
     * The view model's `init` starts a wallet observer that would write to the
     * same state after its 2.1s wait. Cancelling the scope leaves that observer
     * parked in the wait, so what these read back came from the call under test.
     */
    private fun bootedOn(vararg accounts: LocalAccount): NavigationViewModel {
        val scope = CoroutineScope(Job())
        return NavigationViewModel(
            activeIdentityStateFlow = MutableStateFlow<Identity?>(null),
            initialNavigationUIState = stranded,
            nostrRepository = Device(accounts.toList()),
            scope = scope,
        ).also { scope.cancel() }
    }

    @Test
    fun `an account already on the device does not stay on the loading screen`() = runTest {
        val viewModel = bootedOn(account(signed = true))

        viewModel.loadNostrProfile(SovereignWalletStartupRoute)

        assertNotEquals(
            stranded,
            viewModel.navigationUIState.value,
            "boot stopped on the screen it was asked to move off",
        )
        assertEquals(
            NavigationUIState.ProfileLoaded(publicKey = publicKey),
            viewModel.navigationUIState.value,
        )
    }

    @Test
    fun `a profile not yet queued for broadcast is routed by what it is missing`() = runTest {
        val viewModel = bootedOn(account(signed = false))

        viewModel.loadNostrProfile(SovereignWalletStartupRoute)

        // Same device, same call, different answer: what comes back is read off
        // the account rather than being one fixed destination for "has an
        // account", which is what makes the assertion above worth anything.
        assertEquals(
            NavigationUIState.UnqueuedProfile(profile = account(signed = false).profile!!),
            viewModel.navigationUIState.value,
        )
    }

    @Test
    fun `a device with no account is sent to go and make one`() = runTest {
        val viewModel = bootedOn()

        viewModel.loadNostrProfile(SovereignWalletStartupRoute)

        assertEquals(NavigationUIState.Landing, viewModel.navigationUIState.value)
    }

    @Test
    fun `asked from anywhere but startup it sends the wallet up first`() = runTest {
        val viewModel = bootedOn(account(signed = true))

        viewModel.loadNostrProfile(startupRoute = null)

        // The default-argument contract: no startup route means the caller is
        // not the startup screen, and nothing downstream works before the node
        // is up, so the answer is to go and start it.
        assertEquals(NavigationUIState.StartupPhoenix, viewModel.navigationUIState.value)
    }

    /**
     * `loadNostrProfile` used to read the account through `profile.publicKey`. A
     * placeholder has no Profile, so that read was null and the answer was Landing --
     * "go and make an account" -- for a key that had just been imported, while the
     * identity observer, reading the same rows, answered the sync screen. Two writers
     * to one state, and Landing pops the back stack.
     */
    @Test
    fun `a key just imported is sent to fetch its profile not to make one`() = runTest {
        val viewModel = bootedOn(placeholderAccount())

        viewModel.loadNostrProfile(SovereignWalletStartupRoute)

        assertNotEquals(NavigationUIState.Landing, viewModel.navigationUIState.value)
        assertEquals(
            NavigationUIState.UnqueuedProfileSynchronization(
                unsignedNostrEvent = placeholderAccount().unsignedNostrEvent!!
            ),
            viewModel.navigationUIState.value,
        )
    }
}
