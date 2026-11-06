package com.machankura.dedun.compose.nostr.wavelength

import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * The kinds of a group's Wavelength wallet (`docs/group-wavelength-wallet.md`), from 30370, past the Lightning
 * family and with room after it. Every one is an MLS inner event and none is ever broadcast. The key ceremony
 * isn't here: it is the Lightning wallet's ceremony with a purpose on it.
 */
object GroupWavelengthEvents {
    val WALLET_STATEMENT: Kind = 30370
    val OPERATOR_STATEMENT: Kind = 30371
    val AUTH_SEED: Kind = 30372

    val SIGNING_REQUEST: Kind = 30373
    val ECHO: Kind = 30374
    val PROVISION: Kind = 30375
    val SIGNATURE_SHARES: Kind = 30376
    val HIGH_WATER: Kind = 30377

    val SNAPSHOT: Kind = 30378
    val AUTHORISATION: Kind = 30379
    val DEPOSIT_REQUEST: Kind = 30380
    val DEPOSIT_ANSWER: Kind = 30381
    val STOPPED: Kind = 30382

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
