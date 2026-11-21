package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * Kinds Giza sends into rooms Dedun shares with it, that this build does not act on
 * yet. Reserved: no Dedun family may take them. docs/giza-into-dedun.md.
 *
 * - 30340-30345, a FROST share refresh, and 30346-30349, re-enrolling a stale member
 *   (Giza's frost-share-refresh). Phase 7 brings them here.
 * - 30370-30377, governed rooms: the group-signed statement (30370), an admin's own
 *   commit (30371), the carrier of a signed commit (30373), promotion offers (30374-30376)
 *   and stepping down (30377). 30372 was used and retired, and stays reserved. Phase 8
 *   brings them here.
 *
 * `ChatMessage` reads none of them as a line: without the code that acts on them a
 * raw-JSON bubble is all it could show, and the room's transcript fills with envelopes.
 *
 * Until Dedun's own kinds move (docs/giza-into-dedun.md, Phase K), four of these numbers
 * are also Dedun's -- 30340-30343, the deposit attribution, coin assignment and the two
 * transfer kinds -- and eight are its Wavelength wallet's, 30370-30377. Dedun's readers
 * take those first, and refuse an event of Giza's that does not parse and verify as theirs
 * (`GizaKindsRefusedJvmTest`).
 */
object GizaProtocolEvents {
    val SHARE_REFRESH: IntRange = 30340..30349
    val GOVERNANCE: IntRange = 30370..30377

    val ALL: Set<Kind> = (SHARE_REFRESH + GOVERNANCE).toSet()
}
