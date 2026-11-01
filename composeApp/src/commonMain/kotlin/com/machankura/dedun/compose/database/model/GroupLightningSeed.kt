package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The seed a group Lightning wallet's node runs from, as this device received or made it
 * (`docs/group-lightning-wallet.md`, decision D4 and Phase 6).
 *
 * Every Iceberg participant holds it, so that a handover keeps the channels and a lost operator phone
 * does not lose them. It is keyed by the node id it produces, which this device computed itself on
 * receipt: two members proposing a wallet at once each send a seed, and the statement the group signs
 * names the node, which picks between them.
 *
 * Plaintext, like the shares beside it, and with the same cost stated where the wallet is opened.
 */
@Entity(
    primaryKeys = ["chatRoomId", "nodeId"],
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId")],
)
data class GroupLightningSeed(
    val chatRoomId: String,
    /** The node id [seed] produces on [chain], compressed hex: computed here, never taken from the sender. */
    val nodeId: HexKey,
    val chain: String,
    val wallet: Long,
    /** 32 bytes, hex. Secret. */
    val seed: HexKey,
    val sentBy: HexKey,
    val receivedAt: Instant = Clock.System.now(),
) {
    override fun toString(): String = "GroupLightningSeed(chatRoomId=$chatRoomId, nodeId=$nodeId, chain=$chain, wallet=$wallet)"
}
