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
    fun `the words never reach a toString`() {
        assertFalse(profile().toString().contains("abandon"))
    }
}
