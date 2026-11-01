package com.machankura.dedun.compose.ui.view.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.navigation.routes.SelectChatRoomMembersRoute
import com.machankura.dedun.compose.ui.view.state.form.ChatRoomCreationFormState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * First step of group creation: collect what the group is called and what it is for.
 * The group itself is only minted once members have been picked, in
 * [SelectChatRoomMembersViewModel].
 */
class ChatRoomCreationViewModel(
    val chatRoomCreationFormState: ChatRoomCreationFormState = ChatRoomCreationFormState(),
): ViewModel() {

    private val logger = Logger.withTag(TAG)

    fun validateInput(): Boolean {
        if (chatRoomCreationFormState.nameField.textFieldState.text.isBlank()) {
            chatRoomCreationFormState.nameField.errorMessage.value = "Please input a name for the chat"
            return false
        } else {
            chatRoomCreationFormState.nameField.errorMessage.value = null
        }

        return true
    }

    fun selectMembers(
        activeUserPublicKey: HexKey,
        onNavigateToRoute: (Route) -> Unit
    ) {
        if (!validateInput()) return

        val description = chatRoomCreationFormState.descriptionField.textFieldState.text.toString()

        logger.d("selectMembers for new chat room")

        onNavigateToRoute.invoke(
            SelectChatRoomMembersRoute(
                activeUserPublicKey = activeUserPublicKey,
                name = chatRoomCreationFormState.nameField.textFieldState.text.toString(),
                // Keep empty text out of the route: a blank path argument is not something
                // navigation round-trips reliably.
                description = description.ifBlank { null }
            )
        )
    }

    companion object {
        private const val TAG = "ChatRoomCreationViewModel"

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatRoomCreationViewModel()
            }
        }
    }
}
