package com.machankura.dedun.compose.network.sockets

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformWhile

/**
 * True for a message that ends a subscription: the relay will send nothing more
 * under this id.
 *
 * NOTICE is deliberately absent. It carries no subscription id, so it is
 * delivered to every collector on the socket — treating it as terminal would
 * let one relay notice tear down every unrelated subscription at once.
 *
 * NEG-MSG is absent too, and for a sharper reason. Negentropy is a multi-round
 * protocol: the initiator opens with fingerprints over its whole set, and each
 * NEG-MSG the relay sends back resolves some ranges into id lists while
 * splitting others into finer fingerprints the client must answer with another
 * NEG-MSG. Only the ranges that come back as id lists produce have/need ids at
 * all, and a message carries 16 buckets — so one round says almost nothing about
 * a set of any size. Treating the first NEG-MSG as terminal made every sync a
 * single round and abandoned a reconciliation the relay was still in the middle
 * of. A negentropy exchange instead ends when `reconcile()` reports no further
 * message to send, which only the caller can see (see SynchronizationViewModel).
 */
fun NostrIncomingMessage.isTerminalFor(id: String): Boolean =
    (this is NostrIncomingMessage.EoseMessage && subscriptionId == id) ||
        (this is NostrIncomingMessage.ClosedMessage && subscriptionId == id) ||
        (this is NostrIncomingMessage.NegentropyError && subscriptionId == id)

/**
 * True for the message that ends a subscription meant to stay OPEN.
 *
 * Only CLOSED. EOSE emphatically does not end one: it is the boundary between the stored
 * history a relay had and the live tail it will now stream, and treating it as an end is
 * exactly what makes a subscription a poll. Compare [isTerminalFor], which is the rule for a
 * one-shot request, where EOSE is the whole point.
 *
 * No subscription-id argument, unlike [isTerminalFor]: a live collector is already filtered by
 * id before it gets here.
 */
fun NostrIncomingMessage.endsLiveSubscription(): Boolean = this is NostrIncomingMessage.ClosedMessage

/**
 * Completes the flow once the subscription is over, emitting the terminal
 * message first so callers still see the EOSE/CLOSED/NEG-ERR that ended it.
 *
 * Without this the returned flow is a filtered view of the socket's hot
 * `incomingMessages` and so never completes: every collector started for a sync
 * request stayed alive for the lifetime of the app, accumulating one per request
 * ever made. Because a NOTICE is admitted on every id, each relay notice was then
 * logged once per accumulated collector — which is what turned an occasional
 * "too many concurrent REQs" into a flooded log.
 */
fun Flow<NostrIncomingMessage>.completeOnSubscriptionEnd(id: String) =
    transformWhile { message ->
        emit(message)
        !message.isTerminalFor(id)
    }

fun Flow<NostrIncomingMessage>.filterBySubscriptionId(id: String) =
    filter {
        // Negentropy...
        (it is NostrIncomingMessage.NegentropyMessage && it.subscriptionId == id) ||
        (it is NostrIncomingMessage.NegentropyError && it.subscriptionId == id) ||
        // Events
        (it is NostrIncomingMessage.EventMessage && it.subscriptionId == id) ||
        (it is NostrIncomingMessage.EoseMessage && it.subscriptionId == id) ||
        (it is NostrIncomingMessage.CountMessage && it.subscriptionId == id) ||
        (it is NostrIncomingMessage.EventsMessage && it.subscriptionId == id) ||
        // A relay that terminates our subscription (auth-required, rate-limited,
        // unsupported filter) sends CLOSED instead of EOSE. Without it the collector
        // waits forever on a subscription the relay has already abandoned.
        (it is NostrIncomingMessage.ClosedMessage && it.subscriptionId == id) ||
        // NOTICE carries no subscription id, so it cannot be correlated. It is admitted
        // here only because it is the sole signal some relays give for "negentropy
        // disabled"; the collector treats it as advisory, never as a terminal failure.
        (it is NostrIncomingMessage.NoticeMessage)
    }

// Deliberately does NOT admit NoticeMessage: a NOTICE is not tied to an event id, and
// letting one through failed whatever publish happened to be in flight on the shared
// per-socket flow — including publishes to which the notice had nothing to do.
fun Flow<NostrIncomingMessage>.filterByEventId(id: String) =
    filter {
        it is NostrIncomingMessage.OkMessage && it.eventId == id
    }
