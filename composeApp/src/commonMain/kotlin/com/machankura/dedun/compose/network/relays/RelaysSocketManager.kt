package com.machankura.dedun.compose.network.relays

import com.machankura.dedun.compose.network.dto.mapToRelayDTO
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.CloseCmd
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.ReqCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegCloseCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegMsgCmd
import com.vitorpamplona.quartz.nip77Negentropy.NegOpenCmd
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/**
 * As Seen in Primal
 *
 * https://github.com/PrimalHQ/primal-android-app/blob/6db2e6862239335dede4162338d7c4f10ad35031/app/src/main/kotlin/net/primal/android/networking/relays/RelaysSocketManager.kt
 */
class RelaysSocketManager(
    private val activeIdentityStateFlow: StateFlow<Identity?>,
    private val nostrSocketClientFactory: com.machankura.dedun.compose.network.sockets.NostrSocketClientFactory,
    private val cachingImportRepository: com.machankura.dedun.compose.repository.CachingImportRepository,
    private val relayRepository: com.machankura.dedun.compose.repository.RelayRepository,
    /** Injectable so that a test can drive the identity observer on a dispatcher it controls. */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : LiveSubscriptionTransport {
    val logger = Logger.withTag("RelaysSocketManager")
    private val relayPoolsMutex = Mutex()

    /**
     * Concrete relay pool
     */
    private val relayPool =  RelayPool(
        nostrSocketClientFactory = nostrSocketClientFactory,
        cachingImportRepository = cachingImportRepository,
    )

    /**
     * Logical relayPool
     */
    private val userRelays = mutableSetOf<com.machankura.dedun.compose.network.dto.RelayDTO>()

    val userRelayPoolStatus = relayPool.relayPoolStatus

    init {
        observeActiveUserId()
    }

    /**
     * Follows the active identity's relay list into the pool, as a child of `collectLatest`
     * so that the next identity cancels it -- the shape the pumps and the notary already
     * have.
     *
     * It used to be a job per pubkey on [scope], replaced only when the *same* pubkey came
     * round again, which meant a switch left the previous identity's observer running:
     * two observers feeding [updateRelayPools], and the one pool following whichever relay
     * list emitted last. A null identity closes nothing -- the pool is shared by pumps a
     * null identity has already cancelled -- and the next identity's list replaces it
     * through `changeRelays`, as it always did.
     */
    private fun observeActiveUserId() =
        scope.launch {
            activeIdentityStateFlow.collectLatest { identity ->
                val publicKey = identity?.nostrPublicKey ?: return@collectLatest
                try {
                    relayRepository.observePublicKeyRelays(publicKey = publicKey).collect { relays ->
                        val userRelays = relays.filter { it.type == "user" }.map { it.mapToRelayDTO() }
                        updateRelayPools(regularRelays = userRelays)
                    }
                } catch (error: CancellationException) {
                    logger.d { "Relay observation for $publicKey cancelled" }
                    throw error
                }
            }
        }

    /** The urls the pool currently holds. For the switch test; nothing in the app reads it. */
    val relayUrls: Set<String> get() = relayPool.relays.map { it.url }.toSet()

    private suspend fun updateRelayPools(regularRelays: List<com.machankura.dedun.compose.network.dto.RelayDTO>?) {
        relayPoolsMutex.withLock {
            val userRelaysChanged = relayPool.relays != regularRelays
            if (userRelaysChanged && !regularRelays.isNullOrEmpty()) {
                relayPool.changeRelays(relays = regularRelays)
            }
        }
    }

    private suspend fun clearRelayPools() =
        relayPoolsMutex.withLock {
            relayPool.closePool()
        }

    @Throws(com.machankura.dedun.compose.exceptions.NostrPublishException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun publishEvent(nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent): Flow<NostrPublishResult> {
        return if (userRelays.isNotEmpty()) {
            relayPool.publishEvent(
                nostrEvent = nostrEvent,
                relays = userRelays
            )
        } else {
            relayPool.publishEvent(
                nostrEvent = nostrEvent,
                relays = FALLBACK_RELAYS
            )
        }
    }

    @Throws(com.machankura.dedun.compose.exceptions.NostrPublishException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun publishEvent(nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent, relays: Set<com.machankura.dedun.compose.network.dto.RelayDTO>): Flow<NostrPublishResult> {
        logger.d("publishEvent: $nostrEvent")
        return relayPool.publishEvent(
            nostrEvent = nostrEvent,
            relays = relays
        )
    }

    override suspend fun reconnectAll() = relayPool.reconnectAll()

    fun tryConnectingToAllRelays() {
        relayPool.relays.forEach {
            scope.launch {
                relayPool.tryConnectingToRelay(it.url)
            }
        }
    }

//    suspend fun tryConnectingToUserRelay(url: String) = relayPool.tryConnectingToRelay(url)

    suspend fun query(reqCommand: ReqCmd, relayUrl: String): Flow<com.machankura.dedun.compose.network.sockets.NostrIncomingMessage> {
        return relayPool.query(
            reqCommand = reqCommand,
            relayUrl = relayUrl
        )
    }


    override suspend fun openLiveSubscription(reqCommand: ReqCmd, relayUrl: String): Flow<com.machankura.dedun.compose.network.sockets.NostrIncomingMessage> {
        return relayPool.openLiveSubscription(
            reqCommand = reqCommand,
            relayUrl = relayUrl
        )
    }

    override suspend fun updateLiveSubscription(reqCommand: ReqCmd, relayUrl: String) {
        return relayPool.updateLiveSubscription(
            reqCommand = reqCommand,
            relayUrl = relayUrl
        )
    }

    override suspend fun closeLiveSubscription(subId: String, relayUrl: String) {
        return relayPool.closeLiveSubscription(
            subId = subId,
            relayUrl = relayUrl
        )
    }

    suspend fun closeQuery(closeCmd: CloseCmd, relayUrl: String) {
        return relayPool.closeQuery(
            closeCmd = closeCmd,
            relayUrl = relayUrl
        )
    }

    suspend fun negentropySync(negOpenCmd: NegOpenCmd, relayUrl: String): Flow<com.machankura.dedun.compose.network.sockets.NostrIncomingMessage> {
        return relayPool.negentropySync(
            negOpenCmd = negOpenCmd,
            relayUrl = relayUrl
        )
    }

    suspend fun sendNegentropyMessage(negMsgCmd: NegMsgCmd, relayUrl: String) {
        return relayPool.sendNegentropyMessage(
            negMsgCmd = negMsgCmd,
            relayUrl = relayUrl
        )
    }

    suspend fun closeNegentropySync(negCloseCmd: NegCloseCmd, relayUrl: String) {
        return relayPool.closeNegentropySync(
            negCloseCmd = negCloseCmd,
            relayUrl = relayUrl
        )
    }
}
