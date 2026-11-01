package com.machankura.dedun.compose.nostr.subgroup.tags

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * The group a subgroup is a child of, named by its room id.
 *
 * Rides on two events and means something different on each, which is worth
 * knowing before reading either.
 *
 * On a `SubgroupBirthCertificateEvent` it is part of what the parent signed, so
 * it is the parent naming itself and cannot be anything else -- the signature
 * verifies against this very id.
 *
 * On a `GroupKeyStateEvent` it is an *index* into the certificate travelling
 * beside it, which already carries the same value here and as its author. It is
 * kept so a reader can answer "whose child is this" without parsing an event out
 * of a tag value, and it is not a second source of truth: a state carrying this
 * without a certificate, or one whose two copies disagree, is dropped whole. See
 * `GroupKeyStateManager.stateFrom`.
 *
 * A room id is a 32-byte x-only public key -- see `docs/shared-key-derivation.md`
 * -- but nothing is checked here beyond the tag having a value. What makes a
 * parent claim mean anything is the certificate's signature, and a shape check
 * in front of it would only decide which of two rejections a bad value gets.
 */
class SubgroupParentTag(
    val parentChatRoomId: HexKey,
) {
    fun toTagArray() = assemble(parentChatRoomId = parentChatRoomId)

    companion object {
        const val TAG_NAME = "parent_group"

        fun parse(tag: Array<String>): SubgroupParentTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            val parentChatRoomId = tag[1].ifBlank { return null }

            return SubgroupParentTag(parentChatRoomId = parentChatRoomId)
        }

        fun assemble(parentChatRoomId: HexKey): Array<String> =
            arrayOf(TAG_NAME, parentChatRoomId)

        fun assemble(subgroupParentTag: SubgroupParentTag) =
            assemble(parentChatRoomId = subgroupParentTag.parentChatRoomId)
    }
}
