package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a profile needs before its Ark wallet opens, and the cap -- `docs/bark-wallet.md`, D5 and D6. */
class ArkSetupTest {
    private fun resolve(
        hasSeed: Boolean = true,
        platformSupported: Boolean = true,
        torEnabled: Boolean = false,
        consented: Boolean = true,
        rolloutEnabled: Boolean = true,
    ) = ArkSetup.resolve(hasSeed, platformSupported, torEnabled, consented, "1.4.0", ArkNetwork.Signet, rolloutEnabled)

    @Test
    fun `a seed profile that agreed, with Tor off, on a supported platform, is ready on signet`() {
        val ready = assertIs<ArkSetup.Ready>(resolve())
        assertEquals(ArkNetwork.Signet, ready.config.network)
        assertEquals(ArkEndpoints("https://ark.signet.2nd.dev", "https://esplora.signet.2nd.dev"), ready.config.endpoints)
        assertEquals("dedun/1.4.0", ready.config.userAgent)
    }

    @Test
    fun `Tor on is never a clearnet connection`() {
        assertEquals(ArkSetup.Unavailable(ArkUnavailability.TorOn), resolve(torEnabled = true))
    }

    @Test
    fun `the reasons come in the order a person would want them explained`() {
        assertEquals(ArkSetup.Unavailable(ArkUnavailability.RolloutOff), resolve(rolloutEnabled = false, hasSeed = false, torEnabled = true))
        assertEquals(ArkSetup.Unavailable(ArkUnavailability.NoSeed), resolve(hasSeed = false, platformSupported = false, consented = false))
        assertEquals(ArkSetup.Unavailable(ArkUnavailability.UnsupportedPlatform), resolve(platformSupported = false, torEnabled = true))
        assertEquals(ArkSetup.Unavailable(ArkUnavailability.NotConsented), resolve(consented = false))
    }

    @Test
    fun `the user agent always has a slash and never a space`() {
        assertEquals("dedun/1.4.0-beta", ArkSetup.userAgent(" 1.4.0 beta "))
        assertEquals("dedun/0", ArkSetup.userAgent(""))
        assertTrue('/' in ArkSetup.userAgent("x"))
    }

    @Test
    fun `the cap holds on mainnet only, for sends and for amounts asked for`() {
        assertNull(ArkWalletRollout.capFor(ArkNetwork.Signet))
        assertEquals(Satoshi(100_000), ArkWalletRollout.capFor(ArkNetwork.Mainnet))

        assertTrue(ArkWalletRollout.sendExceedsCap(ArkNetwork.Mainnet, Satoshi(100_001)))
        assertFalse(ArkWalletRollout.sendExceedsCap(ArkNetwork.Mainnet, Satoshi(100_000)))
        assertFalse(ArkWalletRollout.sendExceedsCap(ArkNetwork.Signet, Satoshi(5_000_000)))

        assertTrue(ArkWalletRollout.wouldExceedCap(ArkNetwork.Mainnet, Satoshi(90_000), Satoshi(10_001)))
        assertFalse(ArkWalletRollout.wouldExceedCap(ArkNetwork.Mainnet, Satoshi(90_000), Satoshi(10_000)))
        assertFalse(ArkWalletRollout.wouldExceedCap(ArkNetwork.Mainnet, Satoshi(90_000), null))
    }

    @Test
    fun `a network's datadir segment is its name in lower case`() {
        assertEquals(listOf("mainnet", "signet", "regtest"), ArkNetwork.entries.map { it.segment })
    }
}
