package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.TransferRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey

sealed interface GroupTransferUIState {
    data object Loading : GroupTransferUIState

    data class Error(val message: String) : GroupTransferUIState

    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /** What the active profile can give on this rail, by this device's count. */
        val givable: TransferRepository.Givable,
        /** Every other member, those the group has opened deposits for first. */
        val receivers: List<TransferRepository.Receiver>,
        val names: Map<HexKey, String>,
        /** Whether this profile can sign a request at all. */
        val canAsk: Boolean,
    ) : GroupTransferUIState
}
