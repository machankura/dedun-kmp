package com.machankura.dedun.compose.wallet.ark

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
import fr.acinq.phoenix.utils.getApplicationFilesDirectoryPath
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath
import java.util.concurrent.TimeUnit

/**
 * Keeps the default profile's Ark coins alive while the app is not open --
 * `docs/bark-wallet.md`, D12 and Phase 7. Once a day, with a network, it runs Bark's
 * delegated maintenance and claims any Lightning payment waiting for this device.
 *
 * Through [ArkProcess]'s holder, so it never opens a second copy of a wallet the screens
 * have open: if that profile is open it maintains it, if another one is it does nothing,
 * and if none is it opens the default profile's, maintains it and closes it again.
 * Only the default profile: the others are renewed when they are next opened.
 */
class ArkMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val logger = Logger.withTag("ArkMaintenanceWorker")

    override suspend fun doWork(): Result {
        val ctx = PlatformContext(applicationContext)
        val id = (applicationContext as DedunApplication).getGlobalPrefs().getDefaultWallet.first() as? WalletId
            ?: return Result.success()
        if (!ArkFiles.under(getApplicationFilesDirectoryPath(ctx).toPath()).consented(id)) return Result.success()
        val words = SeedManager.loadAndDecryptOrNull(PhoenixGlobal(ctx))?.get(id)?.words ?: return Result.success()
        val tor = DataStoreManager(ctx, NodeParamsManager.chain).loadUserPrefsForWallet(id).getIsTorEnabled.first()
        val ran = ArkProcess.holder(getApplicationFilesDirectoryPath(ctx)).maintainInBackground(ArkProfile(id, words, tor))
        logger.i { if (ran) "maintained the Ark wallet for $id" else "left $id's Ark wallet to the app" }
        return Result.success()
    }

    companion object {
        private const val TAG = "com.machankura.dedun.compose.wallet.ark.ArkMaintenanceWorker"

        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<ArkMaintenanceWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, work)
        }
    }
}
