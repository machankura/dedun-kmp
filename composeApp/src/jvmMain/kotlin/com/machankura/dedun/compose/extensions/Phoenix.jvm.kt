package com.machankura.dedun.compose.extensions

import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.StartBusinessResult
import fr.acinq.phoenix.jvm.BusinessManager
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * `Dispatchers.Default`, following android rather than ios -- ios hops to Main, and there is
 * no reason for the jvm to start a lightning node on the ui thread.
 */
actual suspend fun platformStartupLogic(words: List<String>): StartBusinessResult {
    return withContext(Dispatchers.Default) {
        BusinessManager.startNewBusiness(words, isHeadless = false)
    }
}

/**
 * **Nothing is scheduled, and that has a consequence worth stating.**
 *
 * Android schedules two WorkManager jobs here: `ChannelsWatcher`, which wakes periodically
 * to check whether a channel has been force-closed while the app was shut, and
 * `ContactsPhotoCleaner`. Ios schedules neither and logs that it does not.
 *
 * The jvm has no equivalent, and not for want of an api -- a desktop application simply has
 * no process once its window closes, so there is nothing for a scheduler to wake. Running
 * the watcher in-process would be worse than not running it: it would only ever fire while
 * the app was already open and watching anyway, which is the case that needs no help.
 *
 * The exposure is real. A desktop wallet that stays closed past a force-close deadline does
 * not notice. Anything that closes it -- a system daemon, a scheduled task -- has to live
 * outside this process, which is why this logs rather than pretending.
 */
actual fun schedulePlatformLogic(phoenixGlobal: PhoenixGlobal) {
    Logger.withTag("schedulePlatformLogic").w {
        "no background scheduling on the jvm target: channels are only watched while the app is open"
    }
}

actual fun getShowIntroFlow(phoenixGlobal: PhoenixGlobal): Flow<Boolean> {
    return JvmGlobalPrefs.of(phoenixGlobal).getShowIntro
}

actual fun getGlobalPrefs(phoenixGlobal: PhoenixGlobal): GlobalPrefs {
    return JvmGlobalPrefs.of(phoenixGlobal)
}
