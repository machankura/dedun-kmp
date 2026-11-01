package com.machankura.dedun.compose.wallet.ark

import com.machankura.dedun.compose.AppVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toPath
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The one [IdentityArk] in the process -- `docs/bark-wallet.md`, D2 and Phase 7.
 *
 * Process-wide rather than held by the nav host, for two reasons. Bark asks that the wallet
 * be opened once per process and not closed when Android destroys an activity, which it does
 * on a rotation; a holder in the composition would close and reopen it every time. And
 * Android's background renewal runs in the same process with no composition at all, and must
 * go through the same lock as the screens, or the two would race for the datadir.
 */
@OptIn(ExperimentalAtomicApi::class)
object ArkProcess {
    private val holder = AtomicReference<IdentityArk?>(null)

    /**
     * The holder, made on the first call with the app's files directory. Two first calls at
     * once -- the activity and the worker, at a cold start -- both make one and the loser's is
     * dropped unused: nothing is opened until a profile is activated.
     */
    fun holder(applicationFilesDirectory: String): IdentityArk {
        holder.load()?.let { return it }
        val made = IdentityArk(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            files = ArkFiles.under(applicationFilesDirectory.toPath()),
            appVersion = AppVersion.versionName,
        )
        return if (holder.compareAndSet(null, made)) made else holder.load()!!
    }
}
