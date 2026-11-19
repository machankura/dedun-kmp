package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.machankura.dedun.compose.database.model.types.ShareStanding

/**
 * Who holds participant id [participantId] at one generation of a key, and
 * whether they can sign with it. One row per id per [KeyShareGeneration].
 *
 * Ids are fixed for the life of the key: they are the positions ChillDKG gave
 * its participants, and FROST places every share by them. What can change is
 * who is behind one (a member re-enrolled under a new identity) and whether it
 * holds a share of this generation at all ([standing]).
 */
@Entity(
    primaryKeys = ["dkgSessionId", "generation", "participantId"],
    foreignKeys = [
        ForeignKey(
            entity = KeyShareGeneration::class,
            parentColumns = ["dkgSessionId", "generation"],
            childColumns = ["dkgSessionId", "generation"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("dkgSessionId", "generation"),
    ],
)
data class KeyShareMember(
    val dkgSessionId: String,
    val generation: Int,
    val participantId: Int,

    /** The member's nostr key: who signs as this id. */
    val memberPublicKey: HexKey,

    /** Their ChillDKG host key, which refresh and repair messages are encrypted to. */
    val hostPublicKey: HexKey,

    val standing: ShareStanding,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
) : TimestampedEntity, LocalStoreEntity
