package com.machankura.dedun.compose.nostr.chronicle

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * 30328
 *
 * "I hold none of this group's work. Send it."
 *
 * The reliable half of handing a new member the group's history, and the reason
 * [ChronicleEvent] is not simply pushed at them when they are invited.
 *
 * A push from the inviter is an application message in the epoch the add
 * created. If it reaches the invitee before their Welcome does -- different
 * transports, no ordering guarantee -- `MarmotInboundManager` drops it outright,
 * because a message from an epoch ahead of the local one is dropped and not
 * deferred. Nothing is retried and the inviter sees a success. That is the
 * failure mode described in docs/marmot-membership.md, arriving here in a new
 * costume.
 *
 * A request cannot lose that race, because sending one *is* the proof it was
 * won: a device that can put an application message into the room has processed
 * its Welcome and is at the group's epoch.
 *
 * It also covers three cases no invite-time push can reach, all of which look
 * identical from inside the database and are therefore answered by one rule:
 *
 *  - a reinstall, whose rows are gone and whose invite is long past;
 *  - a second device, which was never invited at all;
 *  - a chronicle that was sent and lost.
 *
 * Carries nothing. The room is the envelope, the asker is the MLS sender, and
 * what they are missing is "all of it" -- a cursor would have to be a position
 * in a dependency graph rather than a timestamp, and is deliberately left out of
 * the first version.
 */
@Immutable
class ChronicleRequestEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {

    companion object {
        /** Beside [ChronicleEvent] at 30327 -- see the note on its own kind. */
        const val KIND: Kind = 30328

        const val ALT_DESCRIPTION = "Group chronicle request"

        fun build(
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ChronicleRequestEvent>.() -> Unit = {},
        ): EventTemplate<ChronicleRequestEvent> =
            eventTemplate(KIND, "", createdAt) {
                alt(ALT_DESCRIPTION)
                initializer()
            }
    }
}
