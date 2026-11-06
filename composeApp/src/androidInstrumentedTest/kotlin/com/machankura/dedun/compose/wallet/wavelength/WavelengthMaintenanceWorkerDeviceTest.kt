package com.machankura.dedun.compose.wallet.wavelength

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import fr.acinq.phoenix.utils.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/**
 * The real worker, the real runtime, and the words decrypted the way a background run has to, with
 * no screen. It needs an installed app with a default profile that has turned its wallet on, so it
 * runs only with the instrumentation argument `wavelengthWorker=1`. WorkManager won't run a periodic
 * job ahead of its schedule even when JobScheduler is told to, so this is how it gets tested.
 */
@RunWith(AndroidJUnit4::class)
class WavelengthMaintenanceWorkerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun theWorkerOpensTheDefaultWalletAndGivesWayToTheApp(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("wavelengthWorker") == "1")
        val holder = WavelengthProcess.holder(PlatformContext(context))
        val worker = TestListenableWorkerBuilder<WavelengthMaintenanceWorker>(context).build()

        val work = async(Dispatchers.Default) { worker.doWork() }
        val opened = withTimeout(4.minutes) {
            holder.state.first { it is WalletState.Open || it is WalletState.Failed || it is WalletState.WrongIdentity }
        }
        Log.i("WalletRuntime", "worker reached $opened")
        assertIs<WalletState.Open>(opened)

        // The app comes back: the worker has to let go at once, not after its seven minutes.
        holder.activate(null)
        assertEquals(ListenableWorker.Result.success(), withTimeout(1.minutes) { work.await() })
        Log.i("WalletRuntime", "worker gave way, with the daemon running: ${engineering.lightning.wavelength.embedded.Wavelength.client.isRunning()}")
        holder.stop()
    }
}
