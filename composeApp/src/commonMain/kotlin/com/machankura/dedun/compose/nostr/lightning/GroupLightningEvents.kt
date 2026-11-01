package com.machankura.dedun.compose.nostr.lightning

import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * The nostr kinds a group Lightning wallet is carried on: its Iceberg key
 * ceremony, its two group-signed statements, and the traffic between the
 * operator's node and the members' co-signers. See
 * `docs/group-lightning-wallet.md`.
 *
 * These are **rumor** kinds, like every other family here: each one only ever
 * exists as a Marmot inner event, MLS-encrypted to the room, and none is ever
 * broadcast to a relay. The replaceable semantics the 3xxxx range implies never
 * apply.
 *
 * ```
 *  proposer    --[ 30350 ceremony proposal ]-> everyone   "let's make a t-of-n Iceberg key"
 *  participant --[ 30351 DKG round 1       ]-> everyone   commitments to seed contributions
 *  participant --[ 30352 DKG round 2       ]-> everyone   contributions, each encrypted to its holder
 *  participant --[ 30353 DKG round 3       ]-> everyone   public share + transcript signature
 *  anyone      --[ 30354 ceremony failure  ]-> everyone   abort + blame
 *  group       --[ 30355 wallet statement  ]   FROST-signed, never broadcast
 *  group       --[ 30356 operator statement]   FROST-signed, never broadcast
 *  opener      --[ 30357 node seed         ]-> each participant, encrypted to them
 *  operator    --[ 30358 signing request   ]-> everyone
 *  participant --[ 30359 echo              ]-> everyone   (label, message hash)
 *  participant --[ 30360 nonce batch       ]-> everyone   round-one contributions
 *  participant --[ 30361 signature share   ]-> everyone   round two
 *  operator    --[ 30362 snapshot          ]-> everyone   balance, channels, payments
 *  group       --[ 30363 authorisation     ]   FROST-signed: pay, close, force-close, splice-out
 *  member      --[ 30364 invoice request   ]-> everyone
 *  operator    --[ 30365 invoice           ]-> everyone
 *  old operator--[ 30366 stopped           ]-> everyone   its last commitment numbers
 * ```
 *
 * ### Why 30350
 *
 * This family was first reserved at 30333-30349, the run after the highest
 * kinds then in use. The guarded-wallet family took 30333-30337 on the main
 * branch while this one was being built, and the two met at the merge, where
 * nothing would have failed: each reads the other's events by kind. Rumor kinds
 * are never published and nothing had shipped, so this family moved to a fresh
 * run past the guarded wallets' with room before it. The note on
 * `FrostSigningEvents` asks the next family not to rely on routing to keep it
 * apart from the others; `GroupLightningEventsTest` asserts both that none of it
 * overlaps a kind the app uses and that it starts past all of them.
 */
object GroupLightningEvents {
    val CEREMONY_PROPOSAL: Kind = 30350
    val DKG_ROUND_1: Kind = 30351
    val DKG_ROUND_2: Kind = 30352
    val DKG_ROUND_3: Kind = 30353
    val CEREMONY_FAILURE: Kind = 30354

    val WALLET_STATEMENT: Kind = 30355
    val OPERATOR_STATEMENT: Kind = 30356
    val NODE_SEED: Kind = 30357

    val SIGNING_REQUEST: Kind = 30358
    val ECHO: Kind = 30359
    val NONCE_BATCH: Kind = 30360
    val SIGNATURE_SHARE: Kind = 30361

    val SNAPSHOT: Kind = 30362
    val AUTHORISATION: Kind = 30363
    val INVOICE_REQUEST: Kind = 30364
    val INVOICE: Kind = 30365
    val STOPPED: Kind = 30366

    /** The Iceberg ceremony's kinds, for routing to its ritual manager. */
    val CEREMONY: Set<Kind> = setOf(CEREMONY_PROPOSAL, DKG_ROUND_1, DKG_ROUND_2, DKG_ROUND_3, CEREMONY_FAILURE)

    /** The co-signing protocol's kinds, for routing to the co-signer. */
    val CO_SIGNING: Set<Kind> = setOf(SIGNING_REQUEST, ECHO, NONCE_BATCH, SIGNATURE_SHARE)

    /** Everything the operator and the members tell each other outside the two protocols. */
    val WALLET_TRAFFIC: Set<Kind> = setOf(NODE_SEED, SNAPSHOT, INVOICE_REQUEST, INVOICE, STOPPED)

    /** The three kinds the group signs with its FROST key, filed as `GroupSignedEvent`s. */
    val GROUP_SIGNED: Set<Kind> = setOf(WALLET_STATEMENT, OPERATOR_STATEMENT, AUTHORISATION)

    /**
     * What members' devices say to each other, as opposed to what the group signs. Never a chat
     * line as it arrives: the manager that acts on one writes the line, if it is worth one
     * (`docs/group-lightning-transcript.md`).
     */
    val PROTOCOL: Set<Kind> = CEREMONY + CO_SIGNING + WALLET_TRAFFIC

    val ALL: Set<Kind> = PROTOCOL + GROUP_SIGNED
}
