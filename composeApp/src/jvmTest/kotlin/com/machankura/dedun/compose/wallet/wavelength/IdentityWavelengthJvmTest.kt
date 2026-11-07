package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.model.WalletState as DaemonWalletState
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import engineering.lightning.wavelength.testing.FakeWavelengthClient
import fr.acinq.phoenix.data.WalletId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityWavelengthJvmTest {
    private val dir = Files.createTempDirectory("wavelength-wallet").toFile()
    private val files = WalletFiles.under(dir.path.toPath())
    private val alice = WalletId("a1".repeat(20))
    private val bob = WalletId("b0".repeat(20))
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun profile(id: WalletId = alice, words: List<String>? = this.words, tor: Boolean = false) = WalletProfile(id, words, tor)

    private fun TestScope.holder(
        bindings: FakeFacadeBindings = FakeFacadeBindings(),
        offered: Boolean = true,
    ) = IdentityWavelength(
        client = FakeWavelengthClient(bindings),
        files = files,
        scope = backgroundScope,
        network = Network.SIGNET,
        offered = offered,
        backgroundGrace = 1.minutes,
    )

    private fun runWallet(block: suspend TestScope.() -> Unit) = runTest(StandardTestDispatcher(), testBody = block)

    @Test
    fun `no consent means nothing opens and nothing connects`() = runWallet {
        val bindings = FakeFacadeBindings()
        val holder = holder(bindings)
        holder.activate(profile())
        runCurrent()
        assertEquals(WalletState.Unavailable(WalletUnavailability.NotConsented), holder.state.value)
        assertFalse(bindings.isRunning())
    }

    @Test
    fun `accepting the sheet opens the wallet, recovers and pins the identity`() = runWallet {
        val bindings = FakeFacadeBindings()
        val holder = holder(bindings)
        holder.activate(profile())
        runCurrent()
        holder.consent()
        runCurrent()
        val open = assertIs<WalletState.Open>(holder.state.value)
        assertEquals(Network.SIGNET, open.network)
        assertNotNull(open.recovery, "a first open recovers")
        assertNotNull(files.pinnedIdentity(Network.SIGNET, alice))
        assertTrue(bindings.isRunning())
    }

    @Test
    fun `a later open does not recover, and carries the pin`() = runWallet {
        files.recordConsent(alice)
        files.pinIdentity(Network.SIGNET, alice, "not the fake's identity")
        val holder = holder(FakeFacadeBindings(initialState = DaemonWalletState.LOCKED))
        holder.activate(profile())
        runCurrent()
        assertEquals(WalletState.WrongIdentity, holder.state.value)
    }

    @Test
    fun `a wrong identity leaves the daemon stopped and the files alone`() = runWallet {
        files.recordConsent(alice)
        files.pinIdentity(Network.SIGNET, alice, "someone else")
        val bindings = FakeFacadeBindings(initialState = DaemonWalletState.LOCKED)
        val holder = holder(bindings)
        holder.activate(profile())
        runCurrent()
        assertFalse(bindings.isRunning())
        assertEquals("someone else", files.pinnedIdentity(Network.SIGNET, alice))
    }

    @Test
    fun `an npub, Tor and the desktop each say why`() = runWallet {
        files.recordConsent(alice)
        val holder = holder()
        holder.activate(profile(words = null))
        runCurrent()
        assertEquals(WalletState.Unavailable(WalletUnavailability.NoSeed), holder.state.value)
        holder.activate(profile(tor = true))
        runCurrent()
        assertEquals(WalletState.Unavailable(WalletUnavailability.TorOn), holder.state.value)

        val noRuntime = IdentityWavelength(null, files, backgroundScope, Network.SIGNET, offered = true)
        noRuntime.activate(profile())
        runCurrent()
        assertEquals(WalletState.Unavailable(WalletUnavailability.UnsupportedPlatform), noRuntime.state.value)
    }

    @Test
    fun `turning Tor on stops the daemon, and off opens it`() = runWallet {
        files.recordConsent(alice)
        val bindings = FakeFacadeBindings()
        val holder = holder(bindings)
        holder.activate(profile())
        runCurrent()
        assertTrue(bindings.isRunning())
        holder.activate(profile(tor = true))
        runCurrent()
        assertFalse(bindings.isRunning())
        holder.activate(profile())
        runCurrent()
        assertTrue(bindings.isRunning())
    }

    @Test
    fun `a switch stops the last daemon before the next opens, with its own directory`() = runWallet {
        files.recordConsent(alice)
        files.recordConsent(bob)
        val bindings = FakeFacadeBindings()
        val holder = holder(bindings)
        holder.activate(profile(alice))
        runCurrent()
        assertTrue(bindings.lastConfigJson!!.contains(alice.nodeIdHash))
        // The fake refuses a second start while one is running, as the real facade does.
        holder.activate(profile(bob))
        runCurrent()
        assertIs<WalletState.Open>(holder.state.value)
        assertTrue(bindings.lastConfigJson!!.contains(bob.nodeIdHash))
    }

    @Test
    fun `a failure is a retry, and the retry works`() = runWallet {
        files.recordConsent(alice)
        val bindings = FakeFacadeBindings()
        val holder = holder(bindings)
        bindings.failNext("startDescriptorWallet", "connection refused")
        holder.activate(profile())
        runCurrent()
        assertIs<WalletState.Failed>(holder.state.value)
        assertNull(files.pinnedIdentity(Network.SIGNET, alice), "a failed first open must recover next time")
        holder.retry()
        runCurrent()
        assertIs<WalletState.Open>(holder.state.value)
    }

    @Test
    fun `the background stops the daemon only after the grace, and the foreground reopens it`() = runWallet {
        files.recordConsent(alice)
        val bindings = FakeFacadeBindings()
        val holder = holder(bindings)
        holder.activate(profile())
        runCurrent()

        holder.enteredBackground()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertTrue(bindings.isRunning(), "a quick return does not cost a reopen")
        holder.enteredForeground()
        advanceTimeBy(2.minutes)
        runCurrent()
        assertTrue(bindings.isRunning())

        holder.enteredBackground()
        advanceTimeBy(2.minutes)
        runCurrent()
        assertFalse(bindings.isRunning())
        holder.enteredForeground()
        runCurrent()
        assertTrue(bindings.isRunning())
        assertIs<WalletState.Open>(holder.state.value)
    }

    @Test
    fun `the renewal opens the wallet, stays open for its time, and stops`() = runWallet {
        files.recordConsent(alice)
        val bindings = FakeFacadeBindings()
        val holder = holder(bindings)
        val ran = async { holder.maintainInBackground(profile(), 5.minutes) }
        runCurrent()
        assertTrue(bindings.isRunning())
        advanceTimeBy(6.minutes)
        runCurrent()
        assertTrue(ran.await())
        assertFalse(bindings.isRunning())
        assertEquals(WalletState.None, holder.state.value)
    }

    @Test
    fun `the renewal gives way the moment the app asks`() = runWallet {
        files.recordConsent(alice)
        val bindings = FakeFacadeBindings()
        val holder = holder(bindings)
        val ran = async { holder.maintainInBackground(profile(), 5.minutes) }
        runCurrent()
        holder.activate(profile())
        runCurrent()
        assertTrue(ran.await())
        assertIs<WalletState.Open>(holder.state.value)
        assertTrue(bindings.isRunning())
    }

    @Test
    fun `the renewal leaves an open wallet to the app, and skips a profile with no consent`() = runWallet {
        files.recordConsent(alice)
        val holder = holder()
        holder.activate(profile())
        runCurrent()
        assertFalse(holder.maintainInBackground(profile(), 5.minutes))

        val fresh = IdentityWavelength(FakeWavelengthClient(), files, backgroundScope, Network.SIGNET, offered = true)
        assertFalse(fresh.maintainInBackground(profile(bob), 5.minutes))
    }

    @Test
    fun `a first open scans a small window, a later one none, and asking scans a wide one`() = runWallet {
        files.recordConsent(alice)
        val bindings = FakeFacadeBindings()
        val holder = holder(bindings)
        holder.activate(profile())
        runCurrent()
        assertEquals(WavelengthWalletRollout.FIRST_OPEN_RECOVERY_WINDOW, assertNotNull(assertIs<WalletState.Open>(holder.state.value).recovery).let { lastWindow(bindings) })

        holder.stop()
        holder.activate(profile())
        runCurrent()
        assertNull(assertIs<WalletState.Open>(holder.state.value).recovery)

        holder.lookForMoreCoins()
        runCurrent()
        assertNotNull(assertIs<WalletState.Open>(holder.state.value).recovery)
        assertEquals(WavelengthWalletRollout.FULL_RECOVERY_WINDOW, lastWindow(bindings))
    }

    private fun lastWindow(bindings: FakeFacadeBindings): Long? = bindings.requests.last { it.first == "startDescriptorWallet" }.second
        .let { Regex("\"recovery_window\":(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }

    @Test
    fun `an nsec makes words on consent, and only then`() = runWallet {
        val made = mutableListOf<WalletId>()
        val holder = IdentityWavelength(
            client = FakeWavelengthClient(),
            files = files,
            scope = backgroundScope,
            network = Network.SIGNET,
            offered = true,
            createSeed = { id -> made += id; words },
        )
        holder.activate(WalletProfile(alice, null, torEnabled = false, canCreateSeed = true))
        runCurrent()
        assertEquals(WalletState.Unavailable(WalletUnavailability.NotConsented), holder.state.value)
        assertEquals(emptyList(), made)

        holder.consent()
        runCurrent()
        assertEquals(listOf(alice), made)
        assertIs<WalletState.Open>(holder.state.value)
    }

    @Test
    fun `an empty wallet may be forgotten, and one that holds anything or can't be read may not`() = runWallet {
        files.recordConsent(alice)
        val empty = holder()
        assertTrue(empty.mayHoldMoney(alice), "a wallet that isn't open can't say it's empty")
        empty.activate(profile())
        runCurrent()
        assertFalse(empty.mayHoldMoney(alice))
        assertTrue(empty.mayHoldMoney(bob), "another profile's wallet says nothing about this one")
        empty.stop()

        val funded = holder(FakeFacadeBindings(initialConfirmedSat = 1))
        funded.activate(profile())
        runCurrent()
        assertTrue(funded.mayHoldMoney(alice))
    }

    @Test
    fun `the words never reach a toString`() {
        assertFalse(profile().toString().contains("abandon"))
    }

    @Test
    fun `a group's wallet takes the daemon from the person's, and nothing of theirs touches it until it's given back`() = runWallet {
        val bindings = FakeFacadeBindings()
        files.recordConsent(alice)
        val holder = holder(bindings)
        holder.activate(profile())
        runCurrent()
        assertIs<WalletState.Open>(holder.state.value)

        assertTrue(holder.acquire("Treasury"))
        assertEquals(WalletState.RunByGroup("Treasury"), holder.state.value)
        assertFalse(bindings.isRunning(), "the person's daemon was stopped for it")

        // What follows would each stop or open a daemon, which is now the group's.
        bindings.startWalletAsIfTheGroupsHadOpened()
        holder.activate(profile())
        holder.retry()
        holder.enteredForeground()
        holder.lookForMoreCoins()
        runCurrent()
        holder.enteredBackground()
        advanceTimeBy(2.minutes)
        runCurrent()
        assertEquals(WalletState.RunByGroup("Treasury"), holder.state.value)
        assertTrue(bindings.isRunning(), "still the group's")
        assertFalse(holder.maintainInBackground(profile(), 1.minutes), "the renewal leaves it alone")
        assertTrue(bindings.isRunning())
        holder.enteredForeground()
        runCurrent()

        bindings.stop()
        holder.release()
        runCurrent()
        assertIs<WalletState.Open>(holder.state.value, "and the person's comes back")
    }

    @Test
    fun `while the person is in their own wallet the group can't take it, and can once they've left`() = runWallet {
        files.recordConsent(alice)
        val holder = holder()
        holder.activate(profile())
        runCurrent()
        holder.wantPersonal(true)
        assertFalse(holder.acquire("Treasury"))
        assertIs<WalletState.Open>(holder.state.value)
        holder.wantPersonal(false)
        assertTrue(holder.acquire("Treasury"))
    }

    private fun FakeFacadeBindings.startWalletAsIfTheGroupsHadOpened() {
        start("""{"network":"signet"}""")
    }
}
