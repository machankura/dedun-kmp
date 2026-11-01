package com.machankura.dedun.compose.network.sockets

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

interface NostrSocketClient {
    val socketUrl: String

    val incomingMessages: SharedFlow<NostrIncomingMessage>

    /**
     * Whether a socket that drops should be re-opened on its own, with backoff.
     *
     * Off by default, and owned by the pool rather than the socket: reconnecting a
     * socket nobody is subscribed on is pure battery cost, so the pool turns this on
     * for exactly as long as it is retaining at least one subscription for the relay.
     */
    var autoReconnect: Boolean

    suspend fun close()

    @Throws(
        Exception::class, // TODO: Use NetworkExtension
        CancellationException::class,
    )
    suspend fun ensureSocketConnectionOrThrow()

    suspend fun sendAUTH(signedEvent: JsonObject)

    suspend fun sendCLOSE(subscriptionId: String)

    suspend fun sendCOUNT(data: JsonObject): String

    suspend fun sendEVENT(signedEvent: JsonObject)

    suspend fun sendREQ(subscriptionId: String, data: JsonObject)

    suspend fun sendMESSAGE(text: String, ensureSessionBeforeSend: Boolean = true)
}
