package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * Kinds Giza sends into rooms Dedun shares with it, that this build does not act on
 * yet. Reserved: no Dedun family may take them. docs/giza-into-dedun.md. (The share
 * refresh's 30340-30349 were here until Phase 7 brought `ShareRefreshEvents` and
 * `ShareRepairEvents`.)
 *
 * - 30370-30377, governed rooms: the group-signed statement (30370), an admin's own
 *   commit (30371), the carrier of a signed commit (30373), promotion offers (30374-30376)
 *   and stepping down (30377). 30372 was used and retired, and stays reserved. Phase 8
 *   brings them here.
 *
 * `ChatMessage` reads none of them as a line: without the code that acts on them a
 * raw-JSON bubble is all it could show, and the room's transcript fills with envelopes.
 *
 * Until K3 (docs/giza-into-dedun.md, Phase K), all eight are also the legacy numbers of
 * Dedun's Wavelength wallet kinds, 30370-30377. `ChatMessage` dispatches on a kind's current
 * number, so an event at one of them reaches the Wavelength arms first, which refuse one of
 * Giza's that does not parse and verify as theirs (`GizaKindsRefusedJvmTest`).
 */
object GizaProtocolEvents {
    val GOVERNANCE: IntRange = 30370..30377

    val ALL: Set<Kind> = GOVERNANCE.toSet()
}
