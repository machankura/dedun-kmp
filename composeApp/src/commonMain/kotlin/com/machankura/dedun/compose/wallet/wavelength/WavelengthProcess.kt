package com.machankura.dedun.compose.wallet.wavelength

import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenix.utils.getApplicationFilesDirectoryPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toPath
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The one [IdentityWavelength] in the process -- `docs/wavelength-wallet.md`, D8.
 *
 * Process-wide rather than held by the nav host, for two reasons. Android destroys an activity on
 * a rotation, and a holder in the composition would stop and reopen the daemon every time. And the
 * background renewal runs in the same process with no composition at all, and must go through the
 * same lock as the screens.
 */
@OptIn(ExperimentalAtomicApi::class)
object WavelengthProcess {
    private val holder = AtomicReference<IdentityWavelength?>(null)

    /** Words made for profiles with none of their own (D13), in `node-data`, beside `seed.dat`. */
    fun seeds(context: PlatformContext): WalletSeedStore =
        WalletSeedStore.forApp(getApplicationFilesDirectoryPath(context).toPath() / "node-data")

    /**
     * The holder, made on the first call. Two first calls at once -- the activity and the worker,
     * at a cold start -- both make one and the loser's is dropped unused: nothing is opened until
     * a profile is activated.
     */
    fun holder(context: PlatformContext): IdentityWavelength {
        holder.load()?.let { return it }
        // With no runtime there is nothing to keep private, and nothing reads this anyway.
        val root = WavelengthPlatform.dataRoot(context) ?: getApplicationFilesDirectoryPath(context)
        val made = IdentityWavelength(
            client = WavelengthPlatform.client,
            files = WalletFiles.under(root.toPath()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            createSeed = seeds(context)::create,
        )
        return if (holder.compareAndSet(null, made)) made else holder.load()!!
    }
}
