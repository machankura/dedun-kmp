package com.machankura.dedun.compose.extensions

import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/** Enough of a public key to tell two members apart when neither has a profile yet. */
private const val SHORTENED_PUBLIC_KEY_LENGTH = 8

/** A public key cut to something readable, for where no profile has arrived yet. */
fun HexKey.shortened(): String = take(SHORTENED_PUBLIC_KEY_LENGTH)

/**
 * A long hex value in groups of eight, which is what makes it readable and what
 * lets it wrap at all.
 *
 * Both halves matter. A 64-character key is compared against another member's
 * screen a character at a time, and the eye loses its place in an undivided run
 * of hex -- the same reason a fingerprint or an account number is grouped.
 *
 * And a run of hex has no space in it, so Compose has nowhere to break the line:
 * it lays the whole thing out on one and lets it run off the side of the sheet,
 * where the end of the key cannot be read at all. The spaces are the break
 * opportunities. Nothing is copied from these -- the copy button takes the
 * signed event, which is not grouped -- so the display can be shaped for reading
 * without a paste losing the value.
 */
fun HexKey.inComparableGroups(): String = chunked(8).joinToString(" ")

/**
 * What to call the member behind this public key on screen.
 *
 * Resolved from the profiles joined onto the room rather than stored anywhere, so
 * it follows a rename and is never stuck on the "LOADING..." placeholder a member
 * is given the moment they are first seen. Falls back to a short key, which is at
 * least stable and distinguishing, when there is no profile at all yet.
 */
fun HexKey.memberName(
    members: List<LocalParticipant>,
    activeUserPublicKey: HexKey
): String = if (this == activeUserPublicKey) {
    "You"
} else {
    members.firstOrNull { it.participant.participantPublicKey == this }
        ?.profile
        ?.humanReadableNameOrPubkey()
        ?: shortened()
}
