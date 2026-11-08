package com.machankura.dedun.compose.extensions

import com.machankura.dedun.android.DedunApplication
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.android.BusinessManager
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthHealthWorker
import fr.acinq.phoenix.android.services.ChannelsWatcher
import fr.acinq.phoenix.android.services.ContactsPhotoCleaner
import fr.acinq.phoenix.data.StartBusinessResult
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext


actual suspend fun platformStartupLogic(words: List<String>): StartBusinessResult {
    return withContext(Dispatchers.Default) {
        BusinessManager.startNewBusiness(words, isHeadless = false)
    }
}

actual fun schedulePlatformLogic(phoenixGlobal: PhoenixGlobal) {
    ChannelsWatcher.schedule(phoenixGlobal.ctx.applicationContext)
    ContactsPhotoCleaner.schedule(phoenixGlobal.ctx.applicationContext)
    // Tells a member their group's coins are running out of time while the app is closed, which is the
    // only time they need telling (`docs/group-wavelength-redesign.md`, B7). It returns at once where
    // this device holds no group Wavelength coins, so it costs nothing to schedule for everybody.
    GroupWavelengthHealthWorker.schedule(phoenixGlobal.ctx.applicationContext)
}
actual fun getShowIntroFlow(phoenixGlobal: PhoenixGlobal): Flow<Boolean> {
    val machankuraApplication = phoenixGlobal.ctx.applicationContext as DedunApplication
    return machankuraApplication.getGlobalPrefs().getShowIntro
}

actual fun getGlobalPrefs(phoenixGlobal: PhoenixGlobal): GlobalPrefs {
    val machankuraApplication = phoenixGlobal.ctx.applicationContext as DedunApplication
    return machankuraApplication.getGlobalPrefs()
}