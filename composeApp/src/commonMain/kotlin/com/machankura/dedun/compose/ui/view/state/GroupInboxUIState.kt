package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.GroupInboxRepository

/** The group's inbox screen (docs/group-inbox.md, Phase 7). */
sealed interface GroupInboxUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val conversations: List<GroupInboxRepository.ConversationSummary>,
        /** Wraps addressed to the group that no key this device holds opens (D8). */
        val unopenable: Int,
        val status: GroupInboxRepository.Status,
    ): GroupInboxUIState

    data class Error(val message: String): GroupInboxUIState

    data object Loading: GroupInboxUIState
}

/** One conversation in the group's inbox (docs/group-inbox.md, Phase 7 and 8). */
sealed interface GroupInboxConversationUIState {
    data class Line(
        val rumorId: String,
        val text: String,
        val createdAt: Long,
        val outgoing: Boolean,
        val senderName: String,
    )

    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val names: List<String>,
        val lines: List<Line>,
    ): GroupInboxConversationUIState

    data class Error(val message: String): GroupInboxConversationUIState

    data object Loading: GroupInboxConversationUIState
}

/** The group's inbox key and its ceremony (docs/group-inbox.md, Phase 7). */
sealed interface GroupInboxSettingsUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val status: GroupInboxRepository.Status,
        /** Whether a request this screen made is still on its way. */
        val busy: Boolean = false,
    ): GroupInboxSettingsUIState

    data class Error(val message: String): GroupInboxSettingsUIState

    data object Loading: GroupInboxSettingsUIState
}
