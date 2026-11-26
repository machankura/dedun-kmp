package com.machankura.dedun.android.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import com.machankura.dedun.android.DedunApplication
import com.machankura.dedun.compose.managers.BackgroundSyncPass

/**
 * Android's background sync: the full pass, every fifteen minutes at best, deferred by Doze to
 * its maintenance windows. docs/notifications-and-background-sync.md, Phase 10.
 *
 * Always reports success. A pass that could not reach a relay is not a failure WorkManager could
 * do better by retrying sooner -- the next period is the retry -- and its outcome is on the ledger.
 */
class BackgroundSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val runtime = (applicationContext as DedunApplication).runtime
        // Inside WorkManager's ten minutes, so the pass ends itself rather than being stopped.
        runtime.backgroundSync.run(BackgroundSyncPass.Kind.Full, deadline = 9.minutes)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "dedun-background-sync"

        /** Asks for the pass every fifteen minutes, with a network. Keeps a schedule already there. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
