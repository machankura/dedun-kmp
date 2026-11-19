package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity

/**
 * One recipient's round-1 or round-2 message in a share refresh. Keyed on
 * (session, sender, kind) like `DkgParticipantMessage`, so a redelivery
 * replaces rather than adds: a round advances on the count reaching the
 * recipients, and a second row per sender would get it there early.
 */
@Entity(
    primaryKeys = ["sessionId", "participantPublicKey", "kind"],
    foreignKeys = [
        ForeignKey(
            entity = ShareRefreshSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ShareRefreshMessage(
    val sessionId: String,
    val participantPublicKey: HexKey,
    val kind: Kind,
    val payload: String,
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
) : TimestampedEntity, LocalStoreEntity
