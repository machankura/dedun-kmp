package com.machankura.dedun.compose.extensions

import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.Chain
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.StartBusinessResult
import fr.acinq.phoenix.ios.BusinessManager
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext


actual suspend fun platformStartupLogic(
    words: List<String>
): StartBusinessResult {
    return withContext(Dispatchers.Main) {
        BusinessManager.startNewBusiness(words, isHeadless = false)
    }
}

actual fun schedulePlatformLogic(phoenixGlobal: PhoenixGlobal) {
    Logger.withTag("schedulePlatformLogic").e { "schedulePlatformLogic not implemented" }
}

actual fun getShowIntroFlow(phoenixGlobal: PhoenixGlobal): Flow<Boolean> {
    return DataStoreManager(
        phoenixGlobal.ctx,
        Chain.Mainnet
    ).loadGlobalPrefsForWallet().getShowIntro
}

actual fun getGlobalPrefs(phoenixGlobal: PhoenixGlobal): GlobalPrefs {
    return DataStoreManager(
        phoenixGlobal.ctx,
        Chain.Mainnet
    ).loadGlobalPrefsForWallet()
}