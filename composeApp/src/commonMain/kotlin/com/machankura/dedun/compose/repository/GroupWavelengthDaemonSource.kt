package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.managers.GroupWavelengthDaemonStatus
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What a unilateral exit would involve: nothing here has run on any network, and the screen says so. */
data class GroupWavelengthExitPlan(val coins: List<String>, val canStart: Boolean, val fundingShortfallSat: Long, val underWay: List<Stage>) {
    data class Stage(val outpoint: String, val status: String, val amountSat: Long, val estimatedFeeSat: Long, val estimatedNetSat: Long)
}

/** This device's daemon for a group's Wavelength wallet, as the wallet screen reads it. Kept apart from [GroupWavelengthRepository], which reads only the database. */
interface GroupWavelengthDaemonSource {
    fun observe(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupWavelengthDaemonStatus>

    /** What leaving without the Ark operator would involve, on the device that runs the wallet, or null on any other. */
    suspend fun exitPlan(chatRoomId: String, chain: Chain, wallet: Long): GroupWavelengthExitPlan? = null

    /** Starts leaving without the Ark operator, for every coin. */
    suspend fun forceExit(chatRoomId: String, chain: Chain, wallet: Long) = Unit

    /** Has the daemon look for the wallet's coins again, as its first open did: true on the device that runs it, which then restarts it. */
    suspend fun rescan(chatRoomId: String, chain: Chain, wallet: Long): Boolean = false

    /** A device that runs no daemon at all. */
    object None : GroupWavelengthDaemonSource {
        override fun observe(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupWavelengthDaemonStatus> = flowOf(GroupWavelengthDaemonStatus.NotOnThisDevice)
    }
}
