package com.machankura.dedun.compose.nostr.wavelength

import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * The kinds of a group's Wavelength wallet (`docs/group-wavelength-wallet.md`). Every one is an MLS inner event
 * and none is ever broadcast. The key ceremony isn't here: it is the Lightning wallet's ceremony with a purpose on
 * it.
 *
 * They were 30370-30382, from just past the Lightning family, until Giza and Bennu took 30370-30377 for governed
 * rooms. The whole family moved to 30400-30412 and then 30430-30442, keeping its order (docs/giza-into-dedun.md, Phase K, and docs/giza-second-batch.md, Phase 4), and each old
 * number is its kind's legacy number: an event signed or sent under it keeps it, and every reader takes either
 * through [com.machankura.dedun.compose.nostr.MovedKinds]. Every set below holds the new numbers; dispatch on
 * `MovedKinds.current(kind)`.
 */
object GroupWavelengthEvents {
    val WALLET_STATEMENT: Kind = 30430
    val OPERATOR_STATEMENT: Kind = 30431
    val AUTH_SEED: Kind = 30432

    val SIGNING_REQUEST: Kind = 30433
    val ECHO: Kind = 30434
    val PROVISION: Kind = 30435
    val SIGNATURE_SHARES: Kind = 30436
    val HIGH_WATER: Kind = 30437

    val SNAPSHOT: Kind = 30438
    val AUTHORISATION: Kind = 30439
    val DEPOSIT_REQUEST: Kind = 30440
    val DEPOSIT_ANSWER: Kind = 30441
    val STOPPED: Kind = 30442

    /** The co-signing protocol's kinds, for routing to the co-signer. */
    val CO_SIGNING: Set<Kind> = setOf(SIGNING_REQUEST, ECHO, PROVISION, SIGNATURE_SHARES, HIGH_WATER)

    /** Everything the operator and the members tell each other outside co-signing. */
    val WALLET_TRAFFIC: Set<Kind> = setOf(AUTH_SEED, SNAPSHOT, DEPOSIT_REQUEST, DEPOSIT_ANSWER, STOPPED)

    /** The three kinds the group signs with its FROST key, filed as `GroupSignedEvent`s. */
    val GROUP_SIGNED: Set<Kind> = setOf(WALLET_STATEMENT, OPERATOR_STATEMENT, AUTHORISATION)

    /** What members' devices say to each other: never a chat line as it arrives. */
    val PROTOCOL: Set<Kind> = CO_SIGNING + WALLET_TRAFFIC

    val ALL: Set<Kind> = PROTOCOL + GROUP_SIGNED
}
