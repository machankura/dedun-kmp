package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.machankura.dedun.compose.database.model.traits.BroadcastableEntity
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.SoftDeletableEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.machankura.dedun.compose.managers.roomRelays
import com.vitorpamplona.quartz.marmot.mls.tree.Credential
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Derived from kind14/15 tags
 * [
 *     ["p", "<receiver-1-pubkey>", "<relay-url>"],
 *     ["p", "<receiver-2-pubkey>", "<relay-url>"],
 *     // rest of tags...
 *   ]
 *
 *   TODO: Might want to add a relation for this...
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["publicKey"],
            childColumns = ["participantPublicKey"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ]
)
data class Participant(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val participantPublicKey: HexKey,
    val chatRoomId: String,

    val relayHint: String?,
    val adminAt: Instant? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
    override val savedAt: Instant = Clock.System.now(),
    override val deletedAt: Instant? = null,
    override val broadcastedAt: Instant? = null,
): TimestampedEntity, LocalStoreEntity, BroadcastableEntity, SoftDeletableEntity {
    companion object {
        fun fromMlsGroup(
            mlsGroup: MlsGroup,
            nostrGroupId: HexKey,
            relayHint: String?
        ): List<Participant> {
            return mlsGroup.members().mapNotNull { (leafIndex, leafNode) ->
                val pubkey = when (val cred = leafNode.credential) {
                    is Credential.Basic -> cred.identity.toHexKey()
                    else -> null
                }

                if (pubkey != null) {
                    Participant(
                        participantPublicKey = pubkey,
                        chatRoomId = nostrGroupId,
                        relayHint = mlsGroup.roomRelays().firstOrNull() ?: relayHint
                    )
                } else {
                    null
                }
            }
        }
    }
}
