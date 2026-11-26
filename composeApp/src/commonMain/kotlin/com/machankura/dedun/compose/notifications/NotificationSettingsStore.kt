package com.machankura.dedun.compose.notifications

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.NotificationLedger

/** The settings screen's view of an identity's ledger. Phase 7. */
class NotificationSettingsStore(private val database: DedunDatabase) {

    /** The ledger, or null until the identity is armed (D3). */
    fun observe(userPublicKey: HexKey): Flow<NotificationLedger?> =
        database.notificationDao().observeLedger(userPublicKey)

    suspend fun save(userPublicKey: HexKey, settings: NotificationSettings) {
        database.notificationDao().setSettings(
            userPublicKey = userPublicKey,
            enabled = settings.enabled,
            messages = settings.messages,
            needsYou = settings.needsYou,
            groupActivity = settings.groupActivity,
            showMessageText = settings.showMessageText,
        )
    }
}
