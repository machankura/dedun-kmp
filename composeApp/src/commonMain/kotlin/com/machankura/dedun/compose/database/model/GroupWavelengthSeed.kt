package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The authentication seed of a group's Wavelength wallet, as this device received or made it
 * (`docs/group-wavelength-wallet.md`, D6).
 *
 * Every Iceberg participant holds it, so that a handover keeps the wallet's identity. It is keyed by the identity it
 * produces, which this device computed on receipt: two members proposing a wallet at once each send a seed, and the
 * statement the group signs names the identity, which picks between them. It moves no coins.
 */
@Entity(
    primaryKeys = ["chatRoomId", "identity"],
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId")],
)
data class GroupWavelengthSeed(
    val chatRoomId: String,
    /** The identity [seed] produces on [chain], compressed hex: computed here, never taken from the sender. */
    val identity: HexKey,
    val chain: String,
    val wallet: Long,
    /** 32 bytes, hex. Secret. */
    val seed: HexKey,
    val sentBy: HexKey,
    val receivedAt: Instant = Clock.System.now(),
) {
    override fun toString(): String = "GroupWavelengthSeed(chatRoomId=$chatRoomId, identity=$identity, chain=$chain, wallet=$wallet)"
}
