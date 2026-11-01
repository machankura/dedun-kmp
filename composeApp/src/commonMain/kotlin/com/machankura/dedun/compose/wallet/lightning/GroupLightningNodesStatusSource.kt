package com.machankura.dedun.compose.wallet.lightning

import com.machankura.dedun.compose.managers.GroupLightningNodeStatus
import com.machankura.dedun.compose.repository.GroupNodeStatusSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** [GroupNodeStatusSource] over the identity's current [GroupLightningNodes], or none while there are none. */
class GroupLightningNodesStatusSource(private val nodes: StateFlow<GroupLightningNodes?>) : GroupNodeStatusSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(chatRoomId: String, wallet: Long): Flow<GroupLightningNodeStatus> =
        nodes.flatMapLatest { it?.statusOf(chatRoomId, wallet) ?: flowOf(GroupLightningNodeStatus.NotOnThisDevice) }

    override fun reconnectNow(chatRoomId: String, wallet: Long) {
        nodes.value?.nodeFor(chatRoomId, wallet)?.reconnectNow()
    }
}
