package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergApprovalStep
import com.vitorpamplona.quartz.nip01Core.core.HexKey

sealed class IcebergCeremonyUIState {
    data object Loading : IcebergCeremonyUIState()

    data class Error(val message: String) : IcebergCeremonyUIState()

    /**
     * @param session null when the room has no ceremony yet, which is the screen's empty state.
     * @param progress for each participant, how many of the three rounds this device has from them.
     * @param thresholds what a new ceremony could be opened at; empty when the room cannot have one.
     */
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val session: IcebergKeySession?,
        val progress: Map<HexKey, Int>,
        val pending: IcebergApprovalStep?,
        val thresholds: List<Int>,
        val holderCount: Int,
        val hasOnchainWallet: Boolean,
    ) : IcebergCeremonyUIState()
}
