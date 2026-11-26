package com.machankura.dedun.compose.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.machankura.dedun.compose.AppLifecycle
import com.machankura.dedun.compose.repository.ChatRepository

/**
 * The room whose transcript is on screen, or null. Read by the notification policy, which posts
 * nothing for the room somebody is already looking at, and set by [MarkReadWhileVisible].
 * docs/notifications-and-background-sync.md, D7 and D8.
 *
 * A singleton for the reason `AppLifecycle` is one: its reader is an application-scoped coroutine
 * that outlives every screen.
 */
object VisibleConversation {
    private val _chatRoomId = MutableStateFlow<String?>(null)

    val chatRoomId: StateFlow<String?> = _chatRoomId.asStateFlow()

    fun entered(chatRoomId: String) {
        _chatRoomId.value = chatRoomId
    }

    /** Clears [chatRoomId] -- and only it: in a transition the next room may already have entered. */
    fun left(chatRoomId: String) {
        _chatRoomId.update { current -> if (current == chatRoomId) null else current }
    }
}

/**
 * Keeps [chatRoomId] read for as long as it is composed and the app is in front: once on entry, and
 * again each time a line arrives in it. Also tells [VisibleConversation] which room is up.
 *
 * Emits no layout, so it can sit above a screen's `ScreenStateTransition` without taking that
 * transition's branches out of their scope.
 */
@Composable
fun MarkReadWhileVisible(
    activeUserPublicKey: String,
    chatRoomId: String,
    chatRepository: ChatRepository,
) {
    val inFront by AppLifecycle.isForeground.collectAsState()

    DisposableEffect(chatRoomId) {
        VisibleConversation.entered(chatRoomId)
        onDispose { VisibleConversation.left(chatRoomId) }
    }

    LaunchedEffect(activeUserPublicKey, chatRoomId, inFront) {
        if (!inFront) return@LaunchedEffect
        chatRepository.markChatRoomRead(chatRoomId)
        chatRepository.observeUnreadCounts(activeUserPublicKey).collect { unread ->
            if ((unread[chatRoomId]?.unread ?: 0) > 0) chatRepository.markChatRoomRead(chatRoomId)
        }
    }
}
