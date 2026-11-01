package com.machankura.dedun.compose.network.sockets

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import kotlinx.serialization.json.Json
import com.machankura.dedun.compose.network.platformHttpClientEngineFactory

internal val defaultSocketsHttpClient by lazy {

    HttpClient(platformHttpClientEngineFactory()) {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(Json {
                isLenient = true
                ignoreUnknownKeys = true
            })
            pingIntervalMillis = 20_000
        }
    }
}


/**
 * How [com.machankura.dedun.compose.network.relays.RelayPool] gets a socket for a relay.
 *
 * An interface rather than the object directly, so the pool's subscription bookkeeping — what
 * it retains, when it replays, when it lets a socket stop reconnecting — can be exercised
 * against a fake instead of against a real websocket.
 */
interface NostrSocketClientFactory {

    fun create(
        wssUrl: String,
        incomingCompressionEnabled: Boolean = false,
        onSocketConnectionOpened: SocketConnectionOpenedCallback? = null,
        onSocketConnectionClosed: SocketConnectionClosedCallback? = null,
        onSocketConnectionReopened: SocketConnectionReopenedCallback? = null,
    ): NostrSocketClient
}

object DefaultNostrSocketClientFactory : NostrSocketClientFactory {

    fun create(
        wssUrl: String,
        httpClient: HttpClient,
        incomingCompressionEnabled: Boolean = false,
        onSocketConnectionOpened: SocketConnectionOpenedCallback? = null,
        onSocketConnectionClosed: SocketConnectionClosedCallback? = null,
        onSocketConnectionReopened: SocketConnectionReopenedCallback? = null,
    ): NostrSocketClient {
        return NostrSocketClientImpl(
            httpClient = httpClient,
            wssUrl = wssUrl,
            incomingCompressionEnabled = incomingCompressionEnabled,
            onSocketConnectionOpened = onSocketConnectionOpened,
            onSocketConnectionClosed = onSocketConnectionClosed,
            onSocketConnectionReopened = onSocketConnectionReopened,
        )
    }

    override fun create(
        wssUrl: String,
        incomingCompressionEnabled: Boolean,
        onSocketConnectionOpened: SocketConnectionOpenedCallback?,
        onSocketConnectionClosed: SocketConnectionClosedCallback?,
        onSocketConnectionReopened: SocketConnectionReopenedCallback?,
    ) = create(
        httpClient = defaultSocketsHttpClient,
        wssUrl = wssUrl,
        incomingCompressionEnabled = incomingCompressionEnabled,
        onSocketConnectionOpened = onSocketConnectionOpened,
        onSocketConnectionClosed = onSocketConnectionClosed,
        onSocketConnectionReopened = onSocketConnectionReopened,
    )
}
