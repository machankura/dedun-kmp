package com.machankura.dedun.compose.wallet.wavelength

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.ReceiveRequest
import engineering.lightning.wavelength.embedded.Wavelength
import engineering.lightning.wavelength.engine.RuntimePhase
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.phoenix.data.WalletId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTimedValue

/**
 * The real gomobile runtime, through the app's own holder, against Lightning Labs' hosted
 * endpoints. Every wallet is a throwaway with fresh words that never holds anything. The mainnet
 * test runs only with the instrumentation argument `wavelengthMainnet=1`.
 */
@RunWith(AndroidJUnit4::class)
class WalletRuntimeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val root = File(instrumentation.targetContext.cacheDir, "wallet-${System.nanoTime()}")
    private val files = WalletFiles.under(root.path.toPath())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val id = WalletId("a1".repeat(20))
    private val words = MnemonicCode.toMnemonics(Random.nextBytes(16))

    @After
    fun tearDown(): Unit = runBlocking {
        Wavelength.client.stop()
        scope.cancel()
        root.deleteRecursively()
    }

    private fun holder(network: Network) = IdentityWavelength(
        client = Wavelength.client,
        files = files,
        scope = scope,
        network = network,
        offered = true,
        configFor = { net, dataDir ->
            val config = WavelengthWalletRollout.configFor(net, dataDir)
            // -e wavelengthEsplora <url>: to compare chain data services without a build.
            InstrumentationRegistry.getArguments().getString("wavelengthEsplora")?.let { config.copy(walletEsploraUrl = it) } ?: config
        },
    )

    private suspend fun IdentityWavelength.opened(): WalletState.Open {
        val state = withTimeout(5.minutes) {
            state.first { it is WalletState.Open || it is WalletState.Failed || it is WalletState.WrongIdentity }
        }
        if (state is WalletState.Failed) throw AssertionError("could not open: ${state.error.code}: ${state.error.message}")
        return assertIs<WalletState.Open>(state)
    }

    private suspend fun WalletState.Open.ready() {
        val seen = mutableListOf<RuntimePhase>()
        withTimeout(5.minutes) {
            engine.snapshot.first { snapshot ->
                if (seen.lastOrNull() != snapshot.phase) seen += snapshot.phase
                snapshot.phase == RuntimePhase.READY || snapshot.phase == RuntimePhase.ERROR
            }
        }
        report("engine phases: $seen")
        assertEquals(RuntimePhase.READY, engine.snapshot.value.phase, engine.snapshot.value.error?.message)
    }

    private fun report(line: String) = Log.i("WalletRuntime", line)

    private suspend fun probe(network: Network) {
        files.recordConsent(id)
        val holder = holder(network)

        val first = measureTimedValue {
            holder.activate(WalletProfile(id, words, torEnabled = false))
            holder.opened().also { it.ready() }
        }
        report("$network first open and recovery: ${first.duration}, recovery ${first.value.recovery}")
        val engine = first.value.engine
        val info = Wavelength.client.getInfo()
        report("$network info: version=${info.version} commit=${info.commit} height=${info.blockHeight} connected=${info.serverConnected} server=${info.serverInfo}")
        report("$network balance: ${engine.snapshot.value.balance}")

        val descriptors = Wavelength.client.listWalletDescriptors().descriptors
        report("$network public descriptors: $descriptors")
        assertTrue(descriptors.isNotEmpty())
        assertFalse(descriptors.any { it.startsWith("wpkh") }, "a wpkh account would share m/84' with Phoenix")
        assertFalse(descriptors.any { "prv" in it })
        val pinned = files.pinnedIdentity(network, id)
        assertEquals(info.identityPubKey, pinned)

        val deposit = engine.deposit()
        report("$network deposit address: ${deposit.address}")
        val invoice = engine.receive(ReceiveRequest(amountSat = 10_000, memo = "probe", timeoutSeconds = 60))
        report("$network invoice: ${invoice.invoice.take(24)}")

        // A return from the background: a reopen, against the pin, with no second recovery.
        holder.stop()
        val again = measureTimedValue {
            holder.activate(WalletProfile(id, words, torEnabled = false))
            holder.opened().also { it.ready() }
        }
        report("$network reopen: ${again.duration}")
        assertEquals(null, again.value.recovery)
        assertEquals(pinned, Wavelength.client.getInfo().identityPubKey)
    }

    @Test
    fun anEmptyWalletOpensAndReopensOnSignet(): Unit = runBlocking { probe(Network.SIGNET) }

    @Test
    fun anEmptyWalletOpensOnMainnet(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("wavelengthMainnet") == "1")
        probe(Network.MAINNET)
    }
}
