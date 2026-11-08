package com.machankura.dedun.compose.wallet.wavelength.group

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
import com.machankura.dedun.compose.PlatformContext
import com.machankura.dedun.compose.database.builder.PlatformDatabaseBuilder
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.managers.GroupArkCoSigner
import com.machankura.dedun.compose.wallet.ElectrumClientLink
import com.machankura.dedun.compose.wallet.GroupChainClients
import com.machankura.dedun.compose.wallet.IdentityElectrum
import com.machankura.dedun.compose.wallet.StandaloneElectrum
import fr.acinq.bitcoin.Chain
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/**
 * Tells a member their group's coins are running out of time while the app is closed.
 *
 * Option 2 of *What Phase 6 left* in `docs/group-wavelength-redesign.md`. `GroupArkWatcher` has been
 * computing both of B7's triggers every thirty minutes all along, and since option 1 it notifies as
 * well as writing the room line -- but only while the app's process is alive. On the operator's device
 * the foreground service keeps that true; on every other member's it is true while they are using the
 * app, which is precisely when they do not need telling.
 *
 * So this is the same `deadlinePass`, from a worker. **Nothing is reimplemented**: the worker's whole
 * job is to assemble the three things the pass needs and let it run.
 *
 * **What it needs, and what it does not.** The aux database takes a platform context and nothing else.
 * The tip is public data, so the Electrum client needs no seed and no identity -- only the member's Tor
 * setting and their custom server if they have one, which is two `UserPrefs` flows, exactly as
 * `WavelengthMaintenanceWorker` already reads them. **Nothing is decrypted here and no wallet is
 * opened.**
 *
 * It is the app's third periodic worker, beside `ChannelsWatcher` (36 hours, which starts a whole
 * headless Phoenix business) and `WavelengthMaintenanceWorker` (24 hours, which opens the personal
 * wallet). Twelve hours, because the thing it watches for is measured in days and the deadline that
 * matters -- `WalletExpiry.URGENT_BLOCKS` -- is two of them.
 */
class GroupWavelengthHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val logger = Logger.withTag("GroupWavelengthHealthWorker")

    override suspend fun doWork(): Result {
        val platform = PlatformContext(applicationContext)
        val database = getRoomDatabase(PlatformDatabaseBuilder.getDatabaseBuilder(platform))
        // Nothing to look at, and no reason to open a socket to find that out.
        val chains = database.groupArkDao().walletsWithCoins()
            .mapNotNull { key -> key.split(":").getOrNull(1)?.let { room -> GroupArkCoSigner.parseWalletKey(room, key)?.first } }
            .distinct()
        if (chains.isEmpty()) {
            logger.i { "No group Wavelength coins on this device" }
            return Result.success()
        }

        val phoenixGlobal = PhoenixGlobal(fr.acinq.phoenix.utils.PlatformContext(applicationContext))
        // Read once, before anything is built: this is the only suspending part, and
        // `GroupChainClients.forNode` wants a plain function.
        val prefs = userPrefs()
        val standalones = mutableListOf<StandaloneElectrum>()
        return try {
            val standaloneFor = { chain: Chain -> standalone(chain, phoenixGlobal, prefs).also { standalones += it } }
            // The node's client is one of these too: this worker has no node, so every network is
            // reached the same way.
            val node = standaloneFor(chains.first()).also { it.start() }
            val clients = GroupChainClients.forNode(chains.first(), node.client, standaloneFor)
            val watcher = GroupArkWatcher(database, clients, CoroutineScope(SupervisorJob() + Dispatchers.IO), groupWavelengthAlerts(platform))
            // One pass, not the thirty-minute loop: WorkManager's own schedule is the loop here.
            val ran = withTimeoutOrNull(RUN_FOR) { watcher.deadlinePass() }
            if (ran == null) logger.w { "Gave up waiting for the chain before every wallet was looked at" }
            Result.success()
        } catch (e: Throwable) {
            // A failed look is a look to try again, not a reason to stop scheduling: a member whose
            // Electrum server is down still wants telling tomorrow.
            logger.w(e) { "Couldn't look at the group wallets' deadlines" }
            Result.success()
        } finally {
            standalones.forEach { runCatching { it.stop() } }
        }
    }

    /**
     * The member's Tor setting and their own server, if they have set one.
     *
     * The default wallet's, because that is whose setting the app itself honours. Where there is no
     * default wallet the defaults apply -- Tor off, a random server -- which is also what a member who
     * has never opened a wallet would get.
     */
    private suspend fun userPrefs(): UserPrefs? = runCatching {
        val id = (applicationContext as DedunApplication).getGlobalPrefs().getDefaultWallet.first() as? WalletId
        id?.let { DataStoreManager(fr.acinq.phoenix.utils.PlatformContext(applicationContext), NodeParamsManager.chain).loadUserPrefsForWallet(it) }
    }.getOrNull()

    /**
     * A client for [chain], unstarted: `GroupChainClients.forNode` starts the ones it asks for.
     *
     * The same connection loop the app's own uses, so the same server choice and the same refusal of a
     * clearnet server while Tor is on. A custom server is for the node's own network only, where it is
     * set at all.
     */
    private fun standalone(chain: Chain, phoenixGlobal: PhoenixGlobal, prefs: UserPrefs?): StandaloneElectrum =
        StandaloneElectrum(
            chain = chain,
            networkState = phoenixGlobal.networkMonitor.also { it.start() }.networkState,
            isTorEnabled = prefs?.getIsTorEnabled ?: flowOf(false),
            customServer = if (chain == NodeParamsManager.chain) prefs?.getElectrumServer ?: flowOf(null) else flowOf(null),
            link = ElectrumClientLink(phoenixGlobal.loggerFactory),
        )

    companion object {
        private const val TAG = "com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthHealthWorker"

        /** Long enough for a cold Electrum connection on a bad network, short of WorkManager's ten minutes. */
        private val RUN_FOR = 6.minutes

        /**
         * Every twelve hours, with a twelve-hour window for the system to choose in.
         *
         * The deadline it watches for is two days of blocks, so twice a day is four looks before a
         * member has run out of time to act on the first.
         */
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<GroupWavelengthHealthWorker>(12, TimeUnit.HOURS, 12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, work)
        }
    }
}
