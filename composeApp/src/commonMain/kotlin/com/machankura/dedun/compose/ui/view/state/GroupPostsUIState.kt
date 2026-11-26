package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.GroupNostrProfile
import com.machankura.dedun.compose.nostr.GroupPost

sealed interface GroupPostsUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * What the group has said publicly, as itself, newest first. Empty in
         * a group that has said nothing, which is every group until somebody
         * proposes a post.
         */
        val groupPosts: List<GroupPost> = emptyList(),
        /**
         * The group's profile, for the byline on every post.
         *
         * Read alongside the posts because a post is the one thing here a
         * reader might otherwise attribute to whichever member proposed it,
         * and the group's own name and picture are what say it was the group.
         * Null where the group has not described itself, and the byline falls
         * back to the key.
         */
        val groupNostrProfile: GroupNostrProfile? = null,
        /**
         * Whether this device holds a share of the group's key.
         *
         * False leaves the posts readable and hides the way into the composer,
         * for the reason `GroupNostrProfileUIState.Loaded.canSign` gives.
         */
        val canSign: Boolean = false,
    ): GroupPostsUIState

    data class Error(
        val message: String
    ): GroupPostsUIState

    data object Loading: GroupPostsUIState
}
