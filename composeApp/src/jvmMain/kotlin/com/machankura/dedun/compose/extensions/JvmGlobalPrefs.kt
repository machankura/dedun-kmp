package com.machankura.dedun.compose.extensions

import fr.acinq.bitcoin.Chain
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.utils.preferences.GlobalPrefs

/**
 * The one way the jvm target reaches the global preferences.
 *
 * `DataStoreManager.loadGlobalPrefsForWallet` caches one `GlobalPrefs` per process behind
 * an unsynchronised check-then-set, and DataStore refuses a second instance over the same
 * file. Two first calls at once -- the listing on IO and the startup screen on Main, or a
 * sign-in's write and the listing it races -- can both see the empty cache, both build
 * one, and the loser throws "multiple DataStores active for the same file" on first use.
 * Android is unaffected, since it reaches the prefs through the Application's single
 * instance; the jvm actuals all went through the cache, and this serialises them. The
 * library's own callers come later, at node start, when the cache is long populated.
 */
internal object JvmGlobalPrefs {
    private val lock = Any()

    fun of(phoenixGlobal: PhoenixGlobal): GlobalPrefs = synchronized(lock) {
        DataStoreManager(phoenixGlobal.ctx, chain = Chain.Mainnet).loadGlobalPrefsForWallet()
    }
}
