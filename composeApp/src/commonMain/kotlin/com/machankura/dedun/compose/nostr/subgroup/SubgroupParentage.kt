package com.machankura.dedun.compose.nostr.subgroup

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * A group's parent, and the parent's signature saying so.
 *
 * The two travel together everywhere, and this type is what makes that a fact
 * the compiler holds rather than a rule two nullable parameters have to be
 * trusted to keep. A parent named without a certificate is a claim with the
 * checkable part removed, and a certificate with no parent named beside it has
 * nothing to be an index of.
 *
 * [certificate] is the claim; [parentChatRoomId] is an index into it, since the
 * certificate already carries the same value in its own `parent_group` tag and
 * as its author. The index is kept so a reader can answer "whose child is this"
 * without parsing an event out of a tag value, and it is never a second source
 * of truth -- see `GroupKeyStateManager.stateFrom`, where the two disagreeing
 * drops the state rather than picking one.
 *
 * Holding one of these is not evidence of anything. Nothing here is checked;
 * `SubgroupBirthCertificateEvent.certifies` is where a parentage is believed or
 * refused, and it is asked on every read rather than once at the door.
 */
data class SubgroupParentage(
    val parentChatRoomId: HexKey,
    val certificate: Event,
)
