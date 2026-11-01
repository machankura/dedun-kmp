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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The adapter against Second's signet server, through the port -- `docs/bark-wallet.md`,
 * Phase 2. Everything a wallet can do with no money in it: the funded flows are the
 * regtest run's, Phase 12, because signet's faucet is a web page and not an API.
 *
 * Skipped unless `DEDUN_ARK_SIGNET=1`, on Linux x86-64 with network access.
 */
class ArkSignetJvmTest {
    private val enabled = System.getenv("DEDUN_ARK_SIGNET") == "1" && ArkPlatform.supported
    private val root = Files.createTempDirectory("ark-signet").toFile()
    private val config = ArkConfig(ArkNetwork.Signet, ArkWalletRollout.endpointsFor(ArkNetwork.Signet), ArkSetup.userAgent("test"))

    /** Twelve words made the way `NewProfileWriters` makes a profile's. */
    private fun words(): String = MnemonicCode.toMnemonics(Lightning.randomBytes(16)).joinToString(" ")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aNewWalletHasAnAddressAnInvoiceAndNothingElse() = runBlocking {
        assumeTrue("set DEDUN_ARK_SIGNET=1 on Linux x86-64", enabled)
        val wallet = ArkPlatform.open(config, words(), root.resolve("a").path)
        try {
            assertIs<ArkRecovery.Completed>(wallet.recovery())
            wallet.sync()
            assertEquals(Satoshi(0), wallet.balance().spendable)
            assertTrue(wallet.history().isEmpty())

            val address = wallet.newArkAddress()
            assertTrue(address.startsWith("tark1"), address)
            val parsed = wallet.parse(address)
            assertIs<ArkPaymentMethod.ArkAddress>(parsed.chosen?.method)
            assertTrue(parsed.chosen!!.requiresAmount)

            val request = wallet.receive(Satoshi(10_000), "a test")
            assertNotNull(request.bolt11)
            assertNotNull(request.arkAddress)
            assertNotNull(request.onchainAddress)
            assertTrue(request.uri.startsWith("bitcoin:"), request.uri)
            assertEquals(Satoshi(10_000), wallet.lightningReceiveFee(Satoshi(10_000)).gross)

            assertTrue(wallet.tipHeight() > 0)
            assertEquals(null, wallet.firstExpiryHeight())

            // Delegated maintenance returns without waiting for a round (Phase 0, spike 4).
            val started = System.currentTimeMillis()
            wallet.maintenance()
            assertTrue(System.currentTimeMillis() - started < 60_000, "maintenance waited for a round")
            assertEquals(null, wallet.renewalFee())
            wallet.renew()
            wallet.claimLightningReceives()
        } finally {
            wallet.close()
        }
    }

    @Test
    fun aDatadirOpensOnceAndOnlyForItsOwnWords() = runBlocking {
        assumeTrue("set DEDUN_ARK_SIGNET=1 on Linux x86-64", enabled)
        val phrase = words()
        val datadir = root.resolve("b").path
        val first = ArkPlatform.open(config, phrase, datadir)
        try {
            assertFailsWith<ArkError.DatadirLocked> { ArkPlatform.open(config, phrase, datadir) }
        } finally {
            first.close()
        }
        assertFailsWith<ArkError.WrongSeed> { ArkPlatform.open(config, words(), datadir) }

        // Closed properly, it opens again, and this time it did not create the wallet.
        val again = ArkPlatform.open(config, phrase, datadir)
        try {
            assertEquals(ArkRecovery.NotRun, again.recovery())
        } finally {
            again.close()
        }
    }
}
