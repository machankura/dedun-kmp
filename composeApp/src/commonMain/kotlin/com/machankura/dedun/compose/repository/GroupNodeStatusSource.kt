package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.managers.GroupLightningNodeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * This device's group Lightning nodes, as the wallet screen reads them (`docs/lsp-connection-status.md`,
 * D7). Kept apart from [GroupLightningRepository], which reads the database and is built before the nodes
 * exist; the nodes are built again whenever the chain clients change, and this follows whichever is current.
 */
interface GroupNodeStatusSource {
    /** What this device's node for [chatRoomId]'s wallet [wallet] is doing. Asked only on the operator's device. */
    fun observe(chatRoomId: String, wallet: Long): Flow<GroupLightningNodeStatus>

    /** Dials the LSP now, if that node's last attempt did not hold. */
    fun reconnectNow(chatRoomId: String, wallet: Long)

    /** A device that runs no group nodes at all. */
    object None : GroupNodeStatusSource {
        override fun observe(chatRoomId: String, wallet: Long): Flow<GroupLightningNodeStatus> = flowOf(GroupLightningNodeStatus.NotOnThisDevice)
        override fun reconnectNow(chatRoomId: String, wallet: Long) = Unit
    }
}
