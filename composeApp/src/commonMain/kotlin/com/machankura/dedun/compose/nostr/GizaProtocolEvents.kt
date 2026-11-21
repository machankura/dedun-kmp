package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * Kinds Giza sends into rooms Dedun shares with it, that this build does not act on
 * yet. Reserved: no Dedun family may take them. docs/giza-into-dedun.md and
 * docs/giza-second-batch.md, and the register in docs/kinds.md. (The share refresh's
 * 30340-30349 were here until Phase 7 brought `ShareRefreshEvents` and `ShareRepairEvents`,
 * governed rooms' 30370-30377 until Phase 8 brought `GovernanceEvents`, and the inbox key
 * ceremony's 30410-30416 until the second batch's Phase 6 brought `InboxKeyRitualEvents`.)
 *
 * - 30379-30388, a confederation's key ceremony, and 30390-30399, a confederation's signing.
 *   Each group's admins carry both between the group's room and its inbox. Dedun takes no
 *   confederations (docs/giza-second-batch.md, D12), so these stay here for good.
 * - 30400-30401, a group's direct messages by threshold ECDH, run among a group's admins in
 *   its room. docs/giza-second-batch.md, Phase 6, brings them here.
 *
 * 30390-30412 were briefly Dedun's own new numbers, before Giza took them and Dedun moved to
 * 30420-30442 (docs/giza-second-batch.md, Phase 4). No release wrote them, so nothing Dedun
 * holds is filed under one.
 *
 * `ChatMessage` reads none of them as a line: without the code that acts on them a
 * raw-JSON bubble is all it could show, and the room's transcript fills with envelopes.
 *
 * Until K3 (docs/giza-into-dedun.md, Phase K), 30370-30382 are also the legacy numbers of
 * Dedun's Wavelength wallet kinds. `ChatMessage` dispatches on a kind's current number, so an
 * event at one of them reaches the Wavelength arms first, which refuse one of Giza's that does
 * not parse and verify as theirs (`GizaKindsRefusedJvmTest`). That covers the confederation
 * ceremony's 30379, 30381 and 30382. Governance at 30370-30377 is told apart by its shape
 * before those arms (`GovernanceEvents.isGovernance`).
 */
object GizaProtocolEvents {
    /** A confederation's key ceremony: carried, host keys, dealings, authorisations, acknowledgments, failure, and the retired and reserved numbers between. */
    val CONFEDERATION_CEREMONY: IntRange = 30379..30388

    /** A confederation's signing: proposal, availability, session, nonces, confirmations, partials, voucher, carried, failure. 30397 is unused. */
    val CONFEDERATION_SIGNING: IntRange = 30390..30399

    /** A group's direct messages by threshold ECDH: partials and failure. */
    val GROUP_DM: IntRange = 30400..30401

    val ALL: Set<Kind> = (CONFEDERATION_CEREMONY + CONFEDERATION_SIGNING + GROUP_DM).toSet()
}
