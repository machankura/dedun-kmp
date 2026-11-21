package com.machankura.dedun.compose.ui.view.model

import com.vitorpamplona.marmot.groups.currentGroupState
import kotlinx.coroutines.CancellationException
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.MarmotProfile

/**
 * Whether a room is one the group can still speak as on nostr: put a profile or a post
 * to its quorum (docs/group-nostr-profile-and-posts.md, D4).
 *
 * The share of the key is the other half, and the callers ask it themselves -- the
 * detail screen already holds the answer and should not walk the ceremonies twice.
 * This is the room's half:
 *
 *  - **a Marmot room**: a NIP-17 room has no key of its own to sign as, so it has no
 *    nostr identity to describe;
 *  - **not left**: a member who left can no longer send the proposal into the group;
 *  - **not disbanded**: a group that has ended and signs a profile after it says
 *    something false.
 *
 * No admin check, as in Curare: proposing costs nothing but the quorum's attention, and
 * the quorum decides. Nor a governance one: a profile is a signed event, not a commit.
 */
internal object GroupStatementCapability {
    fun roomIsOpen(localChatRoom: LocalChatRoom): Boolean {
        val chatRoom = localChatRoom.chatRoom
        if (chatRoom.mlsGroupState == null || chatRoom.leftGroupAt != null) return false

        val isDisbanded = try {
            chatRoom.takeIf { it.marmotProfile() == MarmotProfile.CURRENT }
                ?.toMlsGroup()
                ?.currentGroupState()
                ?.isDisbanded == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A group state that will not load is not evidence the group ended. The
            // proposal itself would fail on such a room, and say so.
            false
        }
        return !isDisbanded
    }
}
