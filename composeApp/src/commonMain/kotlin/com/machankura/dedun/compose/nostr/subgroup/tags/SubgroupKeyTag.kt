package com.machankura.dedun.compose.nostr.subgroup.tags

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent

/**
 * The subgroup's own ChillDKG threshold public key, 33-byte compressed hex.
 *
 * On a birth certificate this is what makes the thing checkable rather than
 * opaque. Without it a parent admin is asked to put the group's signature to 32
 * bytes produced by a ceremony most of them were not in; with it, their device
 * can walk `SharedKeyDerivation.marmotGroupId(key, path)` and see for itself that
 * the id being certified really is that key's room.
 *
 * Nothing secret is in here. A threshold public key is what signatures verify
 * against, and the shares behind it never leave the devices that generated them.
 *
 * Shape only, and borrowed rather than restated: `GroupKeyStateEvent` already
 * decides what a threshold key looks like off the wire, and two readers of one
 * value that disagreed about its shape would be a bug nobody would find. Whether
 * it is *the* key for the room is settled by rederiving the room from it, not by
 * looking at it.
 */
class SubgroupKeyTag(
    val thresholdPublicKey: HexKey,
) {
    fun toTagArray() = assemble(thresholdPublicKey = thresholdPublicKey)

    companion object {
        const val TAG_NAME = "subgroup_key"

        fun parse(tag: Array<String>): SubgroupKeyTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            val thresholdPublicKey =
                GroupKeyStateEvent.parseThresholdPublicKey(tag[1]) ?: return null

            return SubgroupKeyTag(thresholdPublicKey = thresholdPublicKey)
        }

        fun assemble(thresholdPublicKey: HexKey): Array<String> =
            arrayOf(TAG_NAME, thresholdPublicKey)

        fun assemble(subgroupKeyTag: SubgroupKeyTag) =
            assemble(thresholdPublicKey = subgroupKeyTag.thresholdPublicKey)
    }
}
