package com.machankura.dedun.compose.network.sockets

sealed class NostrIncomingMessage {

    data class EventMessage(
        val subscriptionId: String,
        val nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent? = null,
    ) : NostrIncomingMessage()

    data class EoseMessage(
        val subscriptionId: String,
    ) : NostrIncomingMessage()

    data class OkMessage(
        val eventId: String,
        val success: Boolean,
        val message: String? = null,
    ) : NostrIncomingMessage()

    data class NoticeMessage(
        val subscriptionId: String? = null,
        val message: String? = null,
    ) : NostrIncomingMessage()

    /**
     * `["CLOSED", <subId>, <reason>]` — the relay has ended the subscription on its side
     * (auth-required, rate-limited, unsupported filter). Terminal: no EOSE will follow.
     */
    data class ClosedMessage(
        val subscriptionId: String,
        val message: String? = null,
    ) : NostrIncomingMessage()

    data class AuthMessage(
        val challenge: String,
    ) : NostrIncomingMessage()

    data class CountMessage(
        val subscriptionId: String,
        val count: Int,
    ) : NostrIncomingMessage()

    data class EventsMessage(
        val subscriptionId: String,
        val nostrEvents: List<com.machankura.dedun.compose.database.model.NostrEvent> = emptyList(),
    ) : NostrIncomingMessage()

    data class NegentropyMessage(
        val subscriptionId: String,
        val negentropyMessage: String,
    ) : NostrIncomingMessage()

    data class NegentropyError(
        val subscriptionId: String,
        val negentropyReason: String,
    ) : NostrIncomingMessage()
}

fun NostrIncomingMessage?.verifyOrThrow(subscriptionId: String) {
    if (this == null) {
        throw com.machankura.dedun.compose.exceptions.NetworkException("No messages received.")
    }

    if (this is NostrIncomingMessage.NoticeMessage) {
        throw com.machankura.dedun.compose.exceptions.NostrNoticeException(
            reason = this.message,
            subscriptionId = subscriptionId,
        )
    }
}
