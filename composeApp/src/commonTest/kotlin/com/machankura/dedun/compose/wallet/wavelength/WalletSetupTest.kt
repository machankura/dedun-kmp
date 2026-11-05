package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.Network
import fr.acinq.bitcoin.Satoshi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletSetupTest {
    private fun resolve(
        hasSeed: Boolean = true,
        platformSupported: Boolean = true,
        torEnabled: Boolean = false,
        consented: Boolean = true,
        offered: Boolean = true,
    ) = WalletSetup.resolve(hasSeed, platformSupported, torEnabled, consented, offered)

    @Test
    fun `reasons come in the order a person would want them explained`() {
        assertEquals(WalletSetup.Unavailable(WalletUnavailability.NoSeed), resolve(hasSeed = false, platformSupported = false, torEnabled = true, consented = false))
        assertEquals(WalletSetup.Unavailable(WalletUnavailability.UnsupportedPlatform), resolve(platformSupported = false, torEnabled = true, consented = false))
        assertEquals(WalletSetup.Unavailable(WalletUnavailability.TorOn), resolve(torEnabled = true, consented = false))
        assertEquals(WalletSetup.Unavailable(WalletUnavailability.NotConsented), resolve(consented = false))
        assertEquals(WalletSetup.Ready, resolve())
    }

    @Test
    fun `shutting the gate stops new consent and locks nobody out`() {
        assertEquals(WalletSetup.Unavailable(WalletUnavailability.NotOffered), resolve(consented = false, offered = false))
        assertEquals(WalletSetup.Ready, resolve(consented = true, offered = false))
    }

    @Test
    fun `the cap is for mainnet`() {
        assertTrue(WavelengthWalletRollout.sendExceedsCap(Network.MAINNET, Satoshi(100_001)))
        assertFalse(WavelengthWalletRollout.sendExceedsCap(Network.MAINNET, Satoshi(100_000)))
        assertFalse(WavelengthWalletRollout.sendExceedsCap(Network.SIGNET, Satoshi(100_001)))
        assertTrue(WavelengthWalletRollout.wouldExceedCap(Network.MAINNET, Satoshi(60_000), Satoshi(40_001)))
        assertFalse(WavelengthWalletRollout.wouldExceedCap(Network.MAINNET, Satoshi(60_000), Satoshi(40_000)))
    }

    @Test
    fun `mainnet names its hosts and takes its chain view from no staging host`() {
        val config = WavelengthWalletRollout.configFor(Network.MAINNET, "/data/x")
        assertTrue(config.allowMainnet)
        assertEquals("wavelength.lightning.finance:443", config.arkServerAddress)
        assertEquals("swap.wavelength.lightning.finance:443", config.swapServerAddress)
        assertFalse(config.walletEsploraUrl!!.contains("staging"))
        config.validate()
    }

    @Test
    fun `only mainnet is allowed mainnet`() {
        assertFalse(WavelengthWalletRollout.configFor(Network.SIGNET, "/data/x").allowMainnet)
    }
}
