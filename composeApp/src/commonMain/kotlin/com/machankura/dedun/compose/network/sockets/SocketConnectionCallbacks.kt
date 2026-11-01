package com.machankura.dedun.compose.network.sockets

typealias SocketConnectionOpenedCallback = (url: String) -> Unit
typealias SocketConnectionClosedCallback = (url: String, error: Throwable?) -> Unit

/**
 * Fired when a session is established on a socket that had already been connected
 * once before — i.e. after a drop, not on the first connect.
 *
 * Deliberately separate from [SocketConnectionOpenedCallback]. The pool replays the
 * subscriptions a socket was carrying when it comes back, and on a FIRST connect
 * that replay would double-send the very REQ whose `sendMESSAGE` opened the socket.
 */
typealias SocketConnectionReopenedCallback = (url: String) -> Unit
