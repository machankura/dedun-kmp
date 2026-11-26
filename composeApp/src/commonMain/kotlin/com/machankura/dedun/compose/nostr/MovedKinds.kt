package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * Dedun's kinds that moved out of Giza's way: docs/giza-into-dedun.md, "The kinds collide,
 * and Dedun's move", and Phase K.
 *
 * Dedun's deposit attribution, coin assignment and two transfer kinds were 30340-30343, and
 * its Wavelength wallet's thirteen were 30370-30382. Giza and Bennu use 30340-30349 for a
 * share refresh and 30370-30377 for governed rooms. Each Dedun kind's `KIND` constant is now
 * its new number -- 30420-30423 and 30430-30442 -- and the old one is its **legacy** number,
 * kept here.
 *
 * The new numbers were first 30390-30393 and 30400-30412. Giza then took 30379-30416 for
 * confederations, a group's inbox and its direct messages, and since no release had written
 * them, the targets moved up by 30 before K2 (docs/giza-second-batch.md, Phase 4). Those
 * interim numbers are not legacy numbers: no device holds an event signed under them, and
 * they are Giza's now (`GizaProtocolEvents`).
 *
 * The legacy number cannot simply be forgotten. A group-signed event's id hashes its kind,
 * so a deposit attribution signed at 30340 stays 30340 for good, and the member chronicle
 * hands such events to members who join long after. So every reader works on [current] --
 * the new number, whichever one the event arrived with -- while the event itself keeps the
 * number it was signed under.
 *
 * The move ships in three releases. This is K1: every device reads both numbers, and writes
 * the legacy one ([writesNew] is false), so a device that has not upgraded keeps hearing
 * everything. K2 flips [writesNew]. K3 stops reading a live legacy number, at which point
 * 30340-30343 and 30370-30377 are Giza's.
 */
object MovedKinds {
    /** Legacy number to new number. */
    val LEGACY_TO_NEW: Map<Kind, Kind> =
        (30340..30343).associateWith { it + 80 } + (30370..30382).associateWith { it + 60 }

    /** New number to legacy number. */
    val NEW_TO_LEGACY: Map<Kind, Kind> = LEGACY_TO_NEW.entries.associate { (legacy, new) -> new to legacy }

    /**
     * Whether a writer puts the new number on what it sends: false in K1, so that devices
     * still on a build from before the move hear every statement. Read only through
     * [written]. A test may set it, and must set it back.
     */
    internal var writesNew: Boolean = false

    /** The number a reader dispatches on: [kind]'s new number when it is a legacy one. */
    fun current(kind: Kind): Kind = LEGACY_TO_NEW[kind] ?: kind

    /** Whether [kind] is [newKind] under either of its numbers. */
    fun isKind(kind: Kind, newKind: Kind): Boolean = current(kind) == newKind

    /** [newKind] and its legacy number, for a query over stored rows. */
    fun both(newKind: Kind): List<Kind> = listOfNotNull(newKind, NEW_TO_LEGACY[newKind])

    /** Every number of every kind in [newKinds], for a query over stored rows. */
    fun both(newKinds: Collection<Kind>): List<Kind> = newKinds.flatMap { both(it) }

    /** The number a writer puts on a new event of [newKind]. */
    fun written(newKind: Kind): Kind = if (writesNew) newKind else NEW_TO_LEGACY[newKind] ?: newKind
}
