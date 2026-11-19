package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.KeyShareGeneration
import com.machankura.dedun.compose.database.model.types.ShareGenerationState
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents

/**
 * The one door signing reads a share through (docs/frost-share-refresh.md,
 * Phase 6).
 *
 * A key's shares can be refreshed: every member given a new share of the same
 * key, on a new polynomial, so that the old ones stop combining with the new.
 * Which share signs, which public shares it is checked against, and who can
 * sign at all then depend on the *generation*. Generation 0 is what ChillDKG
 * made and lives on [DkgSession]; every later one is a [KeyShareGeneration]
 * row. Nothing outside this object should read `DkgSession.secretShare` to
 * sign: the day a key has been refreshed, that column is the share the group
 * has moved away from, and then nothing at all.
 */
object ShareGenerations {

    /**
     * One generation of a key's shares as this device holds it.
     *
     * [signerIds] is who can sign at this generation -- every participant whose
     * share is of it -- keyed by their nostr key. [participantIds] is everyone
     * who has an id at all, including a member withheld from the refresh that
     * made it: the ids are the ceremony's and do not move.
     */
    class Shares(
        val generation: Int,
        val secretShare: PrivateKey?,
        val publicShares: List<PublicKey>?,
        val signerIds: Map<HexKey, Int>,
        val participantIds: Map<HexKey, Int>,
    ) {
        override fun toString(): String =
            "Shares(generation=$generation, secretShare=${if (secretShare == null) "null" else "<secret>"}, " +
                "signers=${signerIds.size}/${participantIds.size})"
    }

    /**
     * The generation the group signs with, by this device's account: the newest
     * active one, or the ceremony's while there has been no refresh.
     *
     * A device withheld from the newest refresh gets that generation back with
     * no secret share, which is right: the group has moved on, and the share it
     * still holds signs nothing the group will aggregate.
     */
    suspend fun active(database: DedunDatabase, key: DkgSession): Shares =
        database.keyShareDao().getActive(key.id)?.let { row -> fromRow(database, row) }
            ?: ceremony(database, key)

    /**
     * Generation [generation] of [key], or null when this device knows nothing of
     * it -- a refresh it never heard about -- or knows it only as pending, which
     * nothing signs with.
     */
    suspend fun at(database: DedunDatabase, key: DkgSession, generation: Int): Shares? {
        if (generation == 0) return ceremony(database, key)
        if (generation < 0) return null

        val row = database.keyShareDao().getGeneration(key.id, generation) ?: return null
        if (row.state == ShareGenerationState.PENDING) return null

        return fromRow(database, row)
    }

    /** Whether this device can sign with [key] today: it holds a share of the generation the group signs with. */
    suspend fun canSign(database: DedunDatabase, key: DkgSession): Boolean =
        active(database, key).secretShare != null

    /** [member]'s participant id in [key], whatever their standing. */
    suspend fun participantIdOf(database: DedunDatabase, key: DkgSession, member: HexKey): Int? =
        active(database, key).participantIds[member] ?: ceremonyIds(database, key)[member]

    /**
     * Generation 0: the ceremony's own output, read where the ceremony wrote it.
     *
     * The ids are each member's index in the ceremony's participant order, the
     * bytewise sort of the host public keys -- the same ordering ChillDKG hashed
     * into the session identity, and so the same one the public shares are in.
     * Deriving them rather than storing them means signing cannot disagree with
     * the ceremony that made the key.
     */
    private suspend fun ceremony(database: DedunDatabase, key: DkgSession): Shares {
        val ids = ceremonyIds(database, key)
        return Shares(
            generation = 0,
            secretShare = key.secretShare?.let { PrivateKey(ByteVector32(it)) },
            publicShares = key.publicShareList(),
            signerIds = ids,
            participantIds = ids,
        )
    }

    private suspend fun ceremonyIds(database: DedunDatabase, key: DkgSession): Map<HexKey, Int> {
        val hostKeys = database.dkgSessionDao()
            .getMessagesByKind(key.id, DkgRitualEvents.HOST_KEY)
            .associate { it.participantPublicKey to it.payload.lowercase() }

        val order = hostKeys.values.sorted()

        return hostKeys.mapNotNull { (member, hostKey) ->
            order.indexOf(hostKey).takeIf { it >= 0 }?.let { member to it }
        }.toMap()
    }

    private suspend fun fromRow(database: DedunDatabase, row: KeyShareGeneration): Shares {
        val members = database.keyShareDao().getMembers(row.dkgSessionId, row.generation)
        return Shares(
            generation = row.generation,
            secretShare = row.secretShare
                ?.takeIf { row.state == ShareGenerationState.ACTIVE || row.state == ShareGenerationState.RETIRING }
                ?.let { PrivateKey(ByteVector32(it)) },
            publicShares = row.publicShareList(),
            signerIds = members
                .filter { it.standing == ShareStanding.LIVE }
                .associate { it.memberPublicKey to it.participantId },
            participantIds = members
                .filter { it.standing != ShareStanding.RETIRED }
                .associate { it.memberPublicKey to it.participantId },
        )
    }
}
