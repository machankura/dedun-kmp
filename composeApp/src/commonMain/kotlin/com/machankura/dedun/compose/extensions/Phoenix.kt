package com.machankura.dedun.compose.extensions

import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.StartBusinessResult
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import kotlinx.coroutines.flow.Flow


expect suspend fun platformStartupLogic(words: List<String>): StartBusinessResult

expect fun schedulePlatformLogic(phoenixGlobal: PhoenixGlobal)

expect fun getShowIntroFlow(phoenixGlobal: PhoenixGlobal): Flow<Boolean>

expect fun getGlobalPrefs(phoenixGlobal: PhoenixGlobal): GlobalPrefs
