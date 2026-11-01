package com.machankura.dedun.compose.network

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * The engine every Ktor client in the app is built on. CIO serves android and the desktop
 * jvm; on apple targets its TLS layer does not exist (`TLS sessions are not supported on
 * Native platform`), so every wss/https call fails at the handshake and ios uses Darwin
 * (NSURLSession) instead.
 */
internal expect fun platformHttpClientEngineFactory(): HttpClientEngineFactory<*>
