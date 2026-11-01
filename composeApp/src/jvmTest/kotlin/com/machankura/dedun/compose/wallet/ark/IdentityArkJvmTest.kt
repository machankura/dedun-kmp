package com.machankura.dedun.compose.wallet.ark

import fr.acinq.phoenix.data.WalletId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One Ark wallet per active profile, closed before the next opens -- `docs/bark-wallet.md`, Phase 3. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityArkJvmTest {
    private val alice = WalletId("a1".repeat(20))
    private val bob = WalletId("b0".repeat(20))
    private val words = List(12) { "abandon" }

    /** What happened, in order, across every wallet the opener made. */
    private val log = mutableListOf<String>()
    private var failOpen: ArkError? = null
    private var lastOpened: FakeArkWallet? = null

    private val files = object : ArkFiles {
        val created = mutableSetOf<String>()
        val consents = mutableSetOf<WalletId>()
        override fun datadir(network: ArkNetwork, id: WalletId) = "/ark/${network.segment}/${id.nodeIdHash}"
        override fun exists(datadir: String) = datadir in created
        override fun consented(id: WalletId) = id in consents
        override fun recordConsent(id: WalletId) { consents += id }
    }

    private fun TestScope.identityArk(platformSupported: Boolean = true) = IdentityArk(
        scope = this,
        files = files,
        appVersion = "test",
        platformSupported = platformSupported,
        network = ArkNetwork.Signet,
        rolloutEnabled = true,
        opener = { config, phrase, datadir ->
            failOpen?.let { failOpen = null; throw it }
            assertEquals("dedun/test", config.userAgent)
            assertEquals(words.joinToString(" "), phrase)
            log += "open $datadir"
            files.created += datadir
            val fake = FakeArkWallet().also { lastOpened = it }
            object : ArkWallet by fake {
                override suspend fun close() { log += "close $datadir" }
            }
        },
    )

    private fun profile(id: WalletId, tor: Boolean = false, seed: Boolean = true) = ArkProfile(id, words.takeIf { seed }, tor)

    @Test
    fun `a switch closes the last profile's wallet before it opens the next`() = runTest {
        files.consents += listOf(alice, bob)
        val ark = identityArk()
        ark.activate(profile(alice))
        ark.activate(profile(bob))
        advanceUntilIdle()
        assertEquals(listOf("open /ark/signet/${alice.nodeIdHash}", "close /ark/signet/${alice.nodeIdHash}", "open /ark/signet/${bob.nodeIdHash}"), log)
        assertIs<ArkWalletState.Open>(ark.state.value)

        ark.stop()
        assertEquals("close /ark/signet/${bob.nodeIdHash}", log.last())
        assertEquals(ArkWalletState.None, ark.state.value)
    }

    @Test
    fun `the same profile activated again is not reopened`() = runTest {
        files.consents += alice
        val ark = identityArk()
        ark.activate(profile(alice))
        ark.activate(profile(alice))
        advanceUntilIdle()
        assertEquals(1, log.count { it.startsWith("open") })
    }

    @Test
    fun `Tor on, no seed, no library and no consent each open nothing`() = runTest {
        files.consents += alice
        val ark = identityArk()
        ark.activate(profile(alice, tor = true))
        advanceUntilIdle()
        assertEquals(ArkWalletState.Unavailable(ArkUnavailability.TorOn), ark.state.value)

        ark.activate(profile(alice, seed = false))
        advanceUntilIdle()
        assertEquals(ArkWalletState.Unavailable(ArkUnavailability.NoSeed), ark.state.value)

        ark.activate(profile(bob))
        advanceUntilIdle()
        assertEquals(ArkWalletState.Unavailable(ArkUnavailability.NotConsented), ark.state.value)

        val elsewhere = identityArk(platformSupported = false)
        elsewhere.activate(profile(alice))
        advanceUntilIdle()
        assertEquals(ArkWalletState.Unavailable(ArkUnavailability.UnsupportedPlatform), elsewhere.state.value)

        assertTrue(log.isEmpty())
    }

    @Test
    fun `accepting the sheet records it and opens`() = runTest {
        val ark = identityArk()
        ark.activate(profile(alice))
        advanceUntilIdle()
        assertEquals(ArkWalletState.Unavailable(ArkUnavailability.NotConsented), ark.state.value)

        ark.consent()
        advanceUntilIdle()
        assertTrue(files.consented(alice))
        assertIs<ArkWalletState.Open>(ark.state.value)
    }

    @Test
    fun `a first open is a recovery, and a later one is not`() = runTest {
        files.consents += alice
        val ark = identityArk()
        val seen = mutableListOf<ArkWalletState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ark.state.collect { seen += it } }
        ark.activate(profile(alice))
        advanceUntilIdle()
        assertTrue(ArkWalletState.Recovering in seen)

        ark.stop()
        seen.clear()
        ark.activate(profile(alice))
        advanceUntilIdle()
        assertTrue(ArkWalletState.Opening in seen)
        assertTrue(ArkWalletState.Recovering !in seen)
    }

    @Test
    fun `a failed open says why, and a retry opens`() = runTest {
        files.consents += alice
        failOpen = ArkError.ServerUnreachable("Failed to connect to Ark server")
        val ark = identityArk()
        ark.activate(profile(alice))
        advanceUntilIdle()
        assertIs<ArkError.ServerUnreachable>(assertIs<ArkWalletState.Failed>(ark.state.value).error)

        ark.retry()
        advanceUntilIdle()
        assertIs<ArkWalletState.Open>(ark.state.value)
    }

    @Test
    fun `the background work borrows the open wallet, and only that profile's`() = runTest {
        files.consents += alice
        val ark = identityArk()
        ark.activate(profile(alice))
        advanceUntilIdle()
        assertEquals("borrowed", ark.withOpenWallet(alice) { "borrowed" })
        assertEquals(null, ark.withOpenWallet(bob) { "borrowed" })
    }

    @Test
    fun `an open claims the Lightning payments that arrived while the wallet was closed`() = runTest {
        files.consents += alice
        val ark = identityArk()
        ark.activate(profile(alice))
        advanceUntilIdle()
        assertEquals<List<String>?>(listOf("claimLightningReceives"), lastOpened?.calls)
    }

    @Test
    fun `a profile's words never reach its string`() {
        assertTrue("abandon" !in profile(alice).toString())
    }
}
