package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.managers.GroupWavelengthDaemonStatus
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** This device's daemon for a group's Wavelength wallet, as the wallet screen reads it. Kept apart from [GroupWavelengthRepository], which reads only the database. */
interface GroupWavelengthDaemonSource {
    fun observe(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupWavelengthDaemonStatus>

    /** A device that runs no daemon at all. */
    object None : GroupWavelengthDaemonSource {
        override fun observe(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupWavelengthDaemonStatus> = flowOf(GroupWavelengthDaemonStatus.NotOnThisDevice)
    }
}
