package com.machankura.dedun.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

/** One of the group's conversations, for its list (docs/group-threshold-ecdh.md, Phase 8). */
data class GroupDmConversationSummary(
    val conversationId: HexKey,
    val participants: List<HexKey>,
    val names: List<String>,
    /** The newest message's words, or null for one the screen cannot show yet. */
    val lastLine: String?,
    val lastAt: Instant,
    val unread: Int,
)

/** What the group was sent that its admins have not opened yet, and on whom it waits. */
data class GroupDmPending(
    val waiting: Int = 0,
    val queued: Int = 0,
    /** How many more admins have to answer the newest waiting message. */
    val moreAdminsNeeded: Int = 0,
    /** The current admins who have not answered it, by name. */
    val notYetAnsweredBy: List<String> = emptyList(),
)

sealed interface GroupDmListUIState {
    data object Loading : GroupDmListUIState
    data class Error(val message: String) : GroupDmListUIState
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val conversations: List<GroupDmConversationSummary>,
        val pending: GroupDmPending,
        val isAdmin: Boolean,
    ) : GroupDmListUIState
}

/** Where a message the group sends has got to (Phase 9). */
enum class GroupDmOutgoingState { BEING_SIGNED, SIGNED, SENT, NOT_SENT, DECLINED }

/** One message in a conversation, in or out. */
data class GroupDmLine(
    val id: HexKey,
    /** The words, or null for a kind this screen cannot show yet. */
    val text: String?,
    val outgoing: Boolean,
    val senderName: String,
    val at: Instant,
    val outgoingState: GroupDmOutgoingState? = null,
    /** The signing session a message the group sent went out in, for Send again. */
    val signingSessionId: String? = null,
)

sealed interface GroupDmConversationUIState {
    data object Loading : GroupDmConversationUIState
    data class Error(val message: String) : GroupDmConversationUIState
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val participants: List<HexKey>,
        val names: List<String>,
        val lines: List<GroupDmLine>,
        val isAdmin: Boolean,
    ) : GroupDmConversationUIState
}
