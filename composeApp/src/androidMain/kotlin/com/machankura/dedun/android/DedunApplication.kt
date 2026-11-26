package com.machankura.dedun.android

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.android.BusinessManager
import fr.acinq.phoenix.android.LightningApplication
import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import com.machankura.dedun.compose.DedunGlobal
import com.machankura.dedun.compose.DedunRuntime

/** This datastore persists preferences across node ids. */
val Context.globalPrefs: DataStore<Preferences> by preferencesDataStore(name = "globalprefs")

class DedunApplication: Application(), LightningApplication {
    private lateinit var globalPrefs: GlobalPrefs
    private lateinit var phoenixGlobal: PhoenixGlobal

    /**
     * The process's runtime. Here rather than in `MainActivity` because a process can start with
     * no activity -- a background sync, a notification's action -- and every one of those has to
     * find the same database and relay pool the screens use. Nothing in it starts until it is read.
     * docs/notifications-and-background-sync.md, D4.
     */
    lateinit var runtime: DedunRuntime
        private set

    override fun onCreate() {
        super.onCreate()

        phoenixGlobal = PhoenixGlobal(PlatformContext(applicationContext))
        globalPrefs = GlobalPrefs(applicationContext.globalPrefs)
        BusinessManager.initialize(this)

        runtime = DedunRuntime.getOrCreate {
            DedunRuntime(
                dedunGlobal = DedunGlobal(
                    platformContext = com.machankura.dedun.compose.PlatformContext(applicationContext = applicationContext)
                ),
                phoenixGlobal = phoenixGlobal,
            )
        }
    }

    override fun getGlobalPrefs(): GlobalPrefs {
        return globalPrefs
    }

    override fun getPhoenixGlobal(): PhoenixGlobal {
        return phoenixGlobal
    }
}
