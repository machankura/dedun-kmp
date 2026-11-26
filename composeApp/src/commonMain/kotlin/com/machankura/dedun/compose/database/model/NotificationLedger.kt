package com.machankura.dedun.compose.database.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant

/**
 * What one identity's notifications have got to, and how its last background sync went.
 * docs/notifications-and-background-sync.md, D2 and D3.
 *
 * No row means not armed: nothing for that identity notifies until its first sync has settled
 * and a row is written with the cursor at the newest line it then held. Otherwise signing in on
 * a new phone would post one notification per message of history.
 *
 * It also holds the identity's notification switches (Phase 7), which is why it is per identity: two
 * profiles on one phone can want different things.
 *
 * Keyed by public key rather than by wallet id because every row it is compared against --
 * `ChatRoom.userPublicKey` -- is.
 */
@Entity
data class NotificationLedger(
    @PrimaryKey
    val userPublicKey: HexKey,

    /** When this identity started notifying. */
    val armedAt: Instant,

    /**
     * The newest [ChatMessage.id] the dispatcher has decided about. A line above it is new to
     * this device; one at or below it has been posted or dropped already. Only moves forward.
     */
    val cursorMessageId: Long,

    /** When a background pass last finished for this identity, however it finished. */
    val lastBackgroundSyncAt: Instant? = null,

    /** How it finished: `completed`, `deadline`, `noNetwork`, `visible`, `failed`. */
    val lastBackgroundSyncOutcome: String? = null,

    /** `quick` (to EOSE) or `full` (negentropy). */
    val lastBackgroundSyncKind: String? = null,

    /** The master switch. Off, nothing for this identity notifies and nothing syncs in the background. */
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,

    /** Messages: what somebody said to you. */
    @ColumnInfo(defaultValue = "1")
    val messages: Boolean = true,

    /** Needs you: an approval this device is waiting on. */
    @ColumnInfo(defaultValue = "1")
    val needsYou: Boolean = true,

    /** Group activity: members, ceremonies, rotations. Off unless asked for. */
    @ColumnInfo(defaultValue = "0")
    val groupActivity: Boolean = false,

    /** Whether a message notification shows what was said, or only that something was (D9). */
    @ColumnInfo(defaultValue = "1")
    val showMessageText: Boolean = true,
) {
    /** The switches, as the policy reads them. */
    val settings: com.machankura.dedun.compose.notifications.NotificationSettings
        get() = com.machankura.dedun.compose.notifications.NotificationSettings(
            enabled = enabled,
            messages = messages,
            needsYou = needsYou,
            groupActivity = groupActivity,
            showMessageText = showMessageText,
        )
}
