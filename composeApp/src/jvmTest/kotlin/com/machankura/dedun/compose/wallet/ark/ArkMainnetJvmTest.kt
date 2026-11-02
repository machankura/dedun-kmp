package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.Lightning
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The adapter against Second's mainnet server, with the endpoints the app ships --
 * `docs/bark-wallet.md`, the mainnet flip. A fresh wallet with throwaway words and no
 * money: it opens, reads the chain and makes requests, and nothing is paid. The funded
 * mainnet checks are made by hand.
 *
 * Skipped unless `DEDUN_ARK_MAINNET=1`, on Linux x86-64 with network access.
 */
class ArkMainnetJvmTest {
    private val enabled = System.getenv("DEDUN_ARK_MAINNET") == "1" && ArkPlatform.supported
    private val root = Files.createTempDirectory("ark-mainnet").toFile()

    /** Twelve words made the way `NewProfileWriters` makes a profile's. */
    private fun words(): String = MnemonicCode.toMnemonics(Lightning.randomBytes(16)).joinToString(" ")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun theShippedNetworkOpensAWalletThatCanAskForMoney() = runBlocking {
        assumeTrue("set DEDUN_ARK_MAINNET=1 on Linux x86-64", enabled)
        val config = assertIs<ArkSetup.Ready>(ArkSetup.resolve(hasSeed = true, platformSupported = true, torEnabled = false, consented = true, appVersion = "test")).config
        assertEquals(ArkNetwork.Mainnet, config.network)

        val wallet = ArkPlatform.open(config, words(), root.resolve("a").path)
        try {
            assertIs<ArkRecovery.Completed>(wallet.recovery())
            wallet.sync()
            assertEquals(Satoshi(0), wallet.balance().spendable)
            assertTrue(wallet.history().isEmpty())

            val address = wallet.newArkAddress()
            assertTrue(address.startsWith("ark1"), address)
            assertIs<ArkPaymentMethod.ArkAddress>(wallet.parse(address).chosen?.method)

            val request = wallet.receive(Satoshi(10_000), "a test")
            assertTrue(request.bolt11!!.startsWith("lnbc"), request.bolt11)
            assertNotNull(request.arkAddress)
            assertTrue(request.onchainAddress!!.startsWith("bc1p"), request.onchainAddress)

            assertTrue(wallet.tipHeight() > 900_000)
            val started = System.currentTimeMillis()
            wallet.maintenance()
            assertTrue(System.currentTimeMillis() - started < 60_000, "maintenance waited for a round")
            assertEquals(null, wallet.renewalFee())
            assertTrue(wallet.onchainAddress().startsWith("bc1p"))
        } finally {
            wallet.close()
        }
    }
}
