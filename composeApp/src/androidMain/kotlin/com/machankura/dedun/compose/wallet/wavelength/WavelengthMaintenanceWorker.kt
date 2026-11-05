package com.machankura.dedun.compose.wallet.wavelength

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.machankura.dedun.android.DedunApplication
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.utils.PlatformContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/**
 * Keeps the default profile's coins alive while the app is not open --
 * `docs/wavelength-wallet.md`, D10 and Phase 8. Once a day, with a network, it opens the wallet and
 * leaves the daemon's own maintenance running for a while, then stops it. A day is well inside the
 * 648-block free window the mainnet operator advertised on 6 November 2026.
 *
 * Through the holder, so it never races the screens for the daemon, and it gives way the moment
 * the app wants it. Only the default profile: the others are renewed when they are next opened.
 */
class WavelengthMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val logger = Logger.withTag("WavelengthMaintenanceWorker")

    override suspend fun doWork(): Result {
        val ctx = PlatformContext(applicationContext)
        val id = (applicationContext as DedunApplication).getGlobalPrefs().getDefaultWallet.first() as? WalletId
            ?: return Result.success()
        // A seed profile's words, or an nsec's own (D13).
        val words = SeedManager.loadAndDecryptOrNull(PhoenixGlobal(ctx))?.get(id)?.words
            ?: WavelengthProcess.seeds(ctx).words(id)
            ?: return Result.success()
        val tor = DataStoreManager(ctx, NodeParamsManager.chain).loadUserPrefsForWallet(id).getIsTorEnabled.first()
        val ran = WavelengthProcess.holder(ctx).maintainInBackground(WalletProfile(id, words, tor), STAY_OPEN_FOR)
        logger.i { if (ran) "kept the wallet open for $id" else "left $id's wallet to the app" }
        return Result.success()
    }

    companion object {
        private const val TAG = "com.machankura.dedun.compose.wallet.wavelength.WavelengthMaintenanceWorker"

        /** Inside WorkManager's ten minutes, with room to open and to stop cleanly. */
        private val STAY_OPEN_FOR = 7.minutes

        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<WavelengthMaintenanceWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, work)
        }
    }
}
