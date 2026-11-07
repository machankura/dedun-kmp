package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.managers.GroupWavelengthDaemon
import com.machankura.dedun.compose.managers.GroupWavelengthDaemonStatus
import com.machankura.dedun.compose.repository.GroupWavelengthDaemonSource
import com.machankura.dedun.compose.repository.GroupWavelengthExitPlan
import com.machankura.dedun.compose.wallet.wavelength.WavelengthPlatform
import com.machankura.dedun.compose.wallet.wavelength.WavelengthProcess
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import fr.acinq.phoenix.utils.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one [GroupWavelengthRunner] in the process. Process-wide for [WavelengthProcess]'s reason: an activity is destroyed on a rotation,
 * and a holder in the composition would stop a daemon, possibly in the middle of a round, every time.
 */
object GroupWavelengthProcess : GroupWavelengthDaemonSource {
    private val mutex = Mutex()
    private val runner = MutableStateFlow<GroupWavelengthRunner?>(null)
    private var owner: HexKey? = null

    /** Runs the wallets [userPublicKey] is elected to, in place of any other profile's. Does nothing with no daemon on this platform. */
    suspend fun activate(context: PlatformContext, database: DedunDatabase, userPublicKey: HexKey?) = mutex.withLock {
        if (owner == userPublicKey) return@withLock
        runner.value?.stop()
        runner.value = null
        owner = userPublicKey
        val client = WavelengthPlatform.client
        val root = WavelengthPlatform.dataRoot(context)
        if (userPublicKey == null || client == null || root == null || !GroupWavelengthRollout.ENABLED) return@withLock
        runner.value = GroupWavelengthRunner(
            database = database,
            userPublicKey = userPublicKey,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            slot = WavelengthProcess.holder(context),
            daemon = GroupWavelengthDaemon(client, root),
            onHolding = { WavelengthPlatform.holdForGroup(context, it) },
        ).also { it.start() }
    }

    override suspend fun exitPlan(chatRoomId: String, chain: Chain, wallet: Long): GroupWavelengthExitPlan? =
        runner.value?.exitPlan(chatRoomId, chain, wallet)

    override suspend fun forceExit(chatRoomId: String, chain: Chain, wallet: Long) {
        runner.value?.forceExit(chatRoomId, chain, wallet)
    }

    override suspend fun rescan(chatRoomId: String, chain: Chain, wallet: Long): Boolean =
        runner.value?.rescan(chatRoomId, chain, wallet) ?: false

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(chatRoomId: String, chain: Chain, wallet: Long): Flow<GroupWavelengthDaemonStatus> =
        runner.flatMapLatest { it?.observe(chatRoomId, chain, wallet) ?: flowOf(GroupWavelengthDaemonStatus.NotOnThisDevice) }
}
