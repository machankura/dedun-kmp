package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.extensions.toHex

/**
 * The fields of a group's Marmot state, read from whichever profile the group is on.
 *
 * A legacy (MIP-01) group keeps its name, description, admins, relays and retention
 * in one `0xF2EE` extension, which `MlsGroup.currentMarmotData()` reads. A
 * current-profile group keeps the same facts in separate components -- profile
 * `0x8001`, admin policy `0x8003`, routing `0x8004`, retention `0x8005` -- which
 * `MlsGroup.currentGroupState()` reads, and carries no `0xF2EE` at all. Every call
 * site that read only the first saw a current-profile group as nameless, adminless
 * and without a routing id, and the Welcome path threw on the last of those.
 *
 * Each accessor tries the current profile first and the legacy extension second,
 * written once here so the order cannot be forgotten at a call site. There is no
 * third case: a group carrying neither is not a Marmot group, and these return null
 * or empty for it rather than guessing.
 *
 * See docs/marmot-v2.md, Phase 1.
 */

/**
 * The id this group's room is keyed on.
 *
 * A current-profile room this app created names its room id in its profile
 * description -- see [MarmotRoomIdentity] -- because its `nostr_group_id` is a
 * routing address, not an identity. Every other group is keyed on its
 * `nostr_group_id`, from whichever profile carries it: a legacy room's derived or
 * random id, or the only id a room made by another client has. A group with neither,
 * which only a test builds, falls back to the MLS group id as every caller did before.
 */
fun MlsGroup.roomId(): HexKey =
    MarmotRoomIdentity.parse(currentGroupState().profile?.description)
        ?: currentNostrGroupId()
        ?: groupId.toHex()

/**
 * The `h` tag this group's kind:445 traffic carries: its `nostr_group_id` from either
 * profile, which for a current-profile room is a random address and for a legacy one
 * is the room id itself. Same fallback as [roomId] for a group with no Marmot state.
 */
fun MlsGroup.routingId(): HexKey = currentNostrGroupId() ?: groupId.toHex()

/** The group's display name, or null when it has none or it is blank. */
fun MlsGroup.roomName(): String? =
    currentGroupState().profile?.name?.ifBlank { null }
        ?: currentMarmotData()?.name?.ifBlank { null }

/** The group's description, or null when it has none or it is blank. */
fun MlsGroup.roomDescription(): String? =
    currentGroupState().profile?.description?.ifBlank { null }
        ?: currentMarmotData()?.description?.ifBlank { null }

/** The relays the group's kind:445 traffic is routed to. Empty when it names none. */
fun MlsGroup.roomRelays(): List<String> =
    currentGroupState().routing?.relays
        ?: currentMarmotData()?.relays
        ?: emptyList()

/**
 * Disappearing-message retention in seconds, or null when the group has none.
 *
 * The current profile spells "none" as an absent component or an explicit zero;
 * MIP-01 spelled it as an absent field. Both read as null here, so a caller adds an
 * expiration tag only when there is a duration to add.
 */
fun MlsGroup.retentionSeconds(): Long? =
    currentGroupState().retention?.takeIf { it.isEnabled }?.disappearingMessageSecs?.toLong()
        ?: currentMarmotData()?.disappearingMessageSecs?.toLong()?.takeIf { it > 0 }
