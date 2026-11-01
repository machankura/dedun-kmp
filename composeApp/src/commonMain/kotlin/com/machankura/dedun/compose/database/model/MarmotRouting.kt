package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * The address a room's kind:445 traffic is carried under, and the room it belongs to.
 *
 * A room's id and its routing id are two values, and were one until the current
 * profile. `ChatRoom.id` is the room's identity: for a room this app makes with a
 * threshold key it is the key derived from that key, the one the room signs as and
 * every chronicle is verified against (docs/shared-key-derivation.md). The routing
 * id is the `nostr_group_id` in the group's `0x8004` component, the `h` tag on every
 * kind:445, and v2 says of it: random, and never derived from any public key,
 * because a derivable address lets a relay link a group's traffic to the identity
 * behind it -- which here would be the key that authors the group's published
 * entries. So a current-profile room this app creates mints one at random.
 *
 * Every other room has a row whose routing id *is* its room id: legacy rooms, where
 * the `0xF2EE` extension's `nostr_group_id` is what the room was keyed on, and rooms
 * created by other clients, whose routing id is the only id they have. A room with
 * no row at all -- one from before this table -- is addressed by its own id, which
 * is what every reader falls back to. See docs/marmot-v2.md, Phase 4.
 *
 * [retiredAtEpoch] is for routing rotation, which v2 describes and nothing here
 * writes yet: a member "has to be able to map several routing ids to one group"
 * while the epochs that used a prior one are still inside the retention window.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("chatRoomId"),
    ],
)
data class MarmotRouting(
    @PrimaryKey
    val routingId: HexKey,
    val chatRoomId: HexKey,
    /** The MLS epoch this address came into use at; 0 for one a room was created with. */
    val activeFromEpoch: Long = 0,
    /** The MLS epoch this address stopped being current at, or null while it is. */
    val retiredAtEpoch: Long? = null,
) {
    val isActive: Boolean get() = retiredAtEpoch == null
}
