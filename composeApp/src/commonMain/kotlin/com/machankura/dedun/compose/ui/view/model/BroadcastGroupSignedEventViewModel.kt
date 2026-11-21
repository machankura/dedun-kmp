package com.machankura.dedun.compose.ui.view.model

import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.exceptions.NostrPublishException
import com.machankura.dedun.compose.network.dto.RelayDTO
import com.machankura.dedun.compose.network.relays.EventPublishTransport
import com.machankura.dedun.compose.network.relays.NostrPublishResult
import com.machankura.dedun.compose.network.relays.RelayPool
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.nostr.RelayUrlInput
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.ui.view.state.BroadcastGroupSignedEventUIState
import kotlin.time.Duration.Companion.milliseconds

/**
 * One event the group signed, sent to the relays a member picks.
 *
 * Signing and sending are two acts here, on purpose. `FrostSigningManager.complete`
 * files what a quorum signed and applies it locally, and nothing leaves the device;
 * this is the second act, taken by a member, one event at a time, to relays they
 * can see and change before pressing the button. What goes on the wire is the
 * event exactly as the group signed it -- the signature is the group's, so any
 * member holding the event can send it and no share of the key is needed.
 *
 * ### Why not the broadcast queue
 *
 * Everything the app publishes as itself goes through `BroadcastNostrEventRequest`,
 * which is durable, retried and drained in the background. It is not used here
 * because it hangs off a `NostrEvent` row, and a row for the group's event is
 * more than a queue entry: the home feed selects by kind, so the group's post
 * would appear in it as if it were anybody's; and the sync loop offers every
 * local event id to the relays it reconciles with, so the event would reach
 * relays this screen never named. `GroupSignedEvent` explains at length why it is
 * not a `NostrEvent`; this keeps that true. The cost is that a broadcast is only
 * as durable as the button press -- what each relay said is shown, and a relay
 * that did not answer is sent to again by pressing again.
 *
 * ### Where the relays come from
 *
 * [defaultRelaysFor]: the app's publish set. Curare seeds from the group's own
 * relay lists where it has agreed them; Dedun did not bring those back
 * (docs/group-nostr-profile-and-posts.md, D1), so the member adds and removes
 * before sending, and the seeded list is a suggestion rather than a rule.
 */
class BroadcastGroupSignedEventViewModel(
    val chatRoomId: String,
    val signedEventId: HexKey,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialBroadcastGroupSignedEventUIState: BroadcastGroupSignedEventUIState,
    val chatRepository: ChatRepository,
    val publishTransport: EventPublishTransport,
): ViewModel() {

    var broadcastGroupSignedEventUIState: BroadcastGroupSignedEventUIState by mutableStateOf(
        initialBroadcastGroupSignedEventUIState
    )
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The relays the event will be sent to, in the order they are shown.
     *
     * Held on the view model rather than in the composition, like the relay
     * editor's working copy, so a rotation does not empty the list.
     */
    val relays = mutableStateListOf<NormalizedRelayUrl>()

    /**
     * What each relay said the last time the button was pressed.
     *
     * Keyed by relay rather than kept as a list, because a relay removed after it
     * answered takes its answer with it, and one added afterwards has none until
     * the next press.
     */
    val outcomes = mutableStateMapOf<NormalizedRelayUrl, RelayOutcome>()

    /**
     * Why the last add did not happen, or null. Cleared by the next keystroke.
     *
     * The same two refusals as the relay editor's, for the same reason: an add
     * button that does nothing for a URL it refuses is indistinguishable from a
     * missed tap.
     */
    var addFailure: AddFailure? by mutableStateOf(null)
        private set

    /**
     * How the last press went, or null before the first one.
     *
     * Shown on the screen rather than flashed in a snackbar, because it is the
     * answer the member came for and it should still be there after the rows
     * under it have been read. Cleared when the next press starts.
     */
    var lastBroadcast: BroadcastSummary? by mutableStateOf(null)
        private set

    private var isSeeded = false

    /** [accepted] of [asked] relays said OK. */
    data class BroadcastSummary(val accepted: Int, val asked: Int)

    enum class AddFailure {
        /** Not a `wss://` URL, or one pointing at this machine. */
        NotARelay,

        /** Already in the list, which is a no-op rather than a mistake. */
        AlreadyListed,
    }

    /** One relay's answer to one press of the button. */
    sealed interface RelayOutcome {
        /** Asked, and not yet answered. */
        data object Sending : RelayOutcome

        /** The relay's OK: it holds the event now, or already did. */
        data object Accepted : RelayOutcome

        /** The relay answered and said no, in its own words. */
        data class Rejected(val reason: String) : RelayOutcome

        /** The relay could not be asked: no connection, or one that dropped. */
        data class Failed(val reason: String) : RelayOutcome

        /** Asked, and still unanswered when the whole broadcast ran out of time. */
        data object NoAnswer : RelayOutcome
    }

    fun initiateBroadcastGroupSignedEvent() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            val signedEvent = chatRepository.groupSignedEvent(chatRoomId, signedEventId)

            broadcastGroupSignedEventUIState = when {
                localChatRoom == null ->
                    BroadcastGroupSignedEventUIState.Error("Couldn't find the chat room")

                signedEvent == null ->
                    BroadcastGroupSignedEventUIState.Error(
                        "This device doesn't hold that signed event"
                    )

                // Only what the group says publicly: its profile and its posts. The
                // id arrives as a navigation argument, and this device still holds
                // events of other kinds the group signed -- a Dedun-era wallet's,
                // kept by the wallet removal, whose xpub on a public relay would be
                // the group's whole history on chain to anyone. Curare's screen
                // refused the wallet's kind; this refuses everything that is not
                // one of the two. docs/group-nostr-profile-and-posts.md, D7.
                signedEvent.kind !in BROADCASTABLE_KINDS ->
                    BroadcastGroupSignedEventUIState.Error(
                        "Only the group's profile and its posts are sent from here"
                    )

                else -> BroadcastGroupSignedEventUIState.Loaded(
                    localChatRoom = localChatRoom,
                    signedEvent = signedEvent,
                )
            }
        }
    }

    /**
     * Fills the list from what the group has said, once.
     *
     * Called from the screen rather than from the loader, for the reason the
     * relay editor's `seedWorkingCopy` gives: a preview and a layout test hand a
     * loaded state straight in. Once and only once, so a member who emptied the
     * list on purpose does not find it refilled by a state the view model re-emits.
     */
    fun seedRelays(kind: Kind) {
        if (isSeeded) return
        isSeeded = true

        relays.addAll(defaultRelaysFor(kind))
    }

    fun clearAddFailure() {
        addFailure = null
    }

    /**
     * Adds [url] to the list, or says why it did not.
     *
     * True when the row appeared, which is the caller's cue to clear the field.
     */
    fun addRelay(url: String): Boolean {
        val relayUrl = RelayUrlInput.relayUrlOrNull(url) ?: run {
            addFailure = AddFailure.NotARelay
            return false
        }

        if (relayUrl in relays) {
            addFailure = AddFailure.AlreadyListed
            return false
        }

        relays.add(relayUrl)
        addFailure = null
        return true
    }

    fun removeRelay(relayUrl: NormalizedRelayUrl) {
        relays.remove(relayUrl)
        outcomes.remove(relayUrl)
    }

    /**
     * Sends [signedEvent] to every relay in the list and records what each said.
     *
     * The send itself is [sendToRelays]; what is this screen's is the state
     * around it. Each answer lands in [outcomes] as it arrives, so the rows fill in one
     * by one rather than all at the end, and [lastBroadcast] sums it up once
     * every row has settled.
     *
     * [onNoRelays] instead of a send to nobody: an empty list is a question
     * rather than a mistake, and the pool would otherwise fall back to the
     * *member's* relays, which is not what an empty list on this screen means.
     *
     * Returns the send, for a caller that has to wait for it, and null when
     * nothing was sent.
     */
    fun broadcast(
        signedEvent: GroupSignedEvent,
        onNoRelays: () -> Unit,
    ): Job? {
        // Guard against double submits. A second press while the first is out
        // would answer each relay's OK twice and count it twice.
        if (isActionPending.value) return null

        val targets = relays.toList()
        if (targets.isEmpty()) {
            onNoRelays.invoke()
            return null
        }

        isActionPending.value = true
        lastBroadcast = null
        outcomes.clear()
        targets.forEach { outcomes[it] = RelayOutcome.Sending }

        return viewModelScope.launch(Dispatchers.IO) {
            val settled = sendToRelays(publishTransport, signedEvent, targets) { relay, outcome ->
                outcomes[relay] = outcome
            }

            lastBroadcast = BroadcastSummary(
                accepted = settled.count { it.value == RelayOutcome.Accepted },
                asked = targets.size,
            )

            isActionPending.value = false
        }
    }

    companion object {
        private const val TAG = "BroadcastGroupSignedEventViewModel"

        private val logger = Logger.withTag(TAG)

        /**
         * A ceiling on the whole broadcast, past the pool's own per-relay wait,
         * so a socket that opens and then says nothing cannot hold the button
         * down forever. The same bound the queue drainer puts on one attempt.
         */
        val BROADCAST_TIMEOUT = (RelayPool.PUBLISH_TIMEOUT * 2).milliseconds

        /** The kinds this screen will put on a relay: the group's profile and posts. */
        val BROADCASTABLE_KINDS: Set<Kind> = setOf(MetadataEvent.KIND, TextNoteEvent.KIND)

        /**
         * Sends [signedEvent] to [targets] and says what each relay answered.
         *
         * One call to the pool for the whole list, which opens a socket to any
         * relay it does not hold and answers once per relay -- an OK, a
         * rejection with the relay's reason, or the error that stopped it being
         * asked. Each answer goes to [onOutcome] as it arrives, so a caller
         * showing rows can fill them in one by one rather than all at the end;
         * a relay that has not answered when the whole thing runs out of time
         * is marked so, which is different from having been refused, and one
         * the pool refused to ask at all is marked failed with the pool's reason.
         *
         * Returns once every relay has settled, with the answers in the order
         * [targets] gave them.
         */
        suspend fun sendToRelays(
            publishTransport: EventPublishTransport,
            signedEvent: GroupSignedEvent,
            targets: List<NormalizedRelayUrl>,
            onOutcome: (NormalizedRelayUrl, RelayOutcome) -> Unit = { _, _ -> },
        ): Map<NormalizedRelayUrl, RelayOutcome> {
            val outcomes = mutableMapOf<NormalizedRelayUrl, RelayOutcome>()

            fun settle(relay: NormalizedRelayUrl, outcome: RelayOutcome) {
                outcomes[relay] = outcome
                onOutcome(relay, outcome)
            }

            try {
                withTimeout(BROADCAST_TIMEOUT) {
                    publishTransport.publishEvent(
                        nostrEvent = wireEventOf(signedEvent),
                        relays = targets.map { RelayDTO(url = it.url, read = false, write = true) }.toSet(),
                    )
                        // One answer per relay is all the pool ever gives, and the
                        // flow underneath never completes on its own.
                        .take(targets.size)
                        .collect { result ->
                            outcomeOf(targets, result)?.let { (relay, outcome) -> settle(relay, outcome) }
                        }
                }
            } catch (e: TimeoutCancellationException) {
                // Ordinary: a relay that never answers is what the timeout is
                // for. The relays still unanswered are settled below.
                logger.w("Broadcast of ${signedEvent.id} ran out of time")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The pool refused the whole call rather than one relay's part of
                // it, so every relay that was waiting gets the same answer.
                logger.e("Broadcast of ${signedEvent.id} failed before any relay answered", e)
                targets.forEach { relay ->
                    if (relay !in outcomes) settle(relay, RelayOutcome.Failed(e.message ?: "Could not send"))
                }
            }

            targets.forEach { relay ->
                if (relay !in outcomes) settle(relay, RelayOutcome.NoAnswer)
            }

            return targets.associateWith { outcomes.getValue(it) }
        }

        /**
         * One relay's answer, against the relay that was asked.
         *
         * Matched by host rather than by string, because the pool names a relay
         * by the URL it first opened a socket with, and that may carry a
         * trailing slash the caller's spelling does not. Null for an answer
         * from a relay nobody asked, which is logged and dropped.
         */
        private fun outcomeOf(
            targets: List<NormalizedRelayUrl>,
            result: NostrPublishResult,
        ): Pair<NormalizedRelayUrl, RelayOutcome>? {
            val answered = NormalizedRelayUrl(result.relayUrl).displayUrl()
            val relay = targets.firstOrNull { it.displayUrl() == answered } ?: run {
                logger.w("Answer from ${result.relayUrl}, which was not asked")
                return null
            }

            val error = result.error
            val ok = result.result as? NostrIncomingMessage.OkMessage

            return relay to when {
                // The pool wraps a relay's ["OK", id, false, reason] in this one
                // exception type and nothing else in it, so the message is the
                // relay's reason in the relay's words.
                error is NostrPublishException -> RelayOutcome.Rejected(error.message ?: "Rejected")
                error != null -> RelayOutcome.Failed(error.message ?: "Could not send")
                ok?.success == true -> RelayOutcome.Accepted
                else -> RelayOutcome.Failed("No OK from the relay")
            }
        }

        /**
         * The event as the pool wants it, and nothing more.
         *
         * A `NostrEvent` built to be serialised and dropped -- it is never
         * written to the table, which is the whole point of this screen sending
         * directly rather than through the queue. The seven fields are the ones
         * the id was hashed over, so what goes out is what the group signed.
         */
        fun wireEventOf(signedEvent: GroupSignedEvent): NostrEvent = NostrEvent(
            id = signedEvent.id,
            pubKey = signedEvent.publicKey,
            kind = signedEvent.kind,
            tags = signedEvent.tags,
            content = signedEvent.content,
            sig = signedEvent.signature,
            createdAt = signedEvent.createdAt,
        )

        /**
         * Where an event of [kind] should go, before the member has said otherwise:
         * the app's publish set, since a list of nothing would make the button a
         * no-op until somebody typed a relay.
         *
         * A function of the event rather than a constant so the group's own relay
         * lists, or a rule per kind, can come back without touching the screen
         * (docs/group-nostr-profile-and-posts.md, D1). Curare's version reads the
         * group's General list, adds the indexers for a relay list, and drops what
         * the group blocked.
         */
        @Suppress("UNUSED_PARAMETER")
        fun defaultRelaysFor(kind: Kind): List<NormalizedRelayUrl> =
            Relays.eventPublishRelaySet.toList()

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            signedEventId: HexKey,
            initialBroadcastGroupSignedEventUIState: BroadcastGroupSignedEventUIState =
                BroadcastGroupSignedEventUIState.Loading,
            chatRepository: ChatRepository,
            publishTransport: EventPublishTransport,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BroadcastGroupSignedEventViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    signedEventId = signedEventId,
                    initialBroadcastGroupSignedEventUIState = initialBroadcastGroupSignedEventUIState,
                    chatRepository = chatRepository,
                    publishTransport = publishTransport,
                )
            }
        }
    }
}
