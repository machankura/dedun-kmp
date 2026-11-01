package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.utils.RandomInstance
import com.vitorpamplona.quartz.utils.TimeUtils
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.extensions.toHex
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.nostr.chronicle.ChronicleEvent
import com.machankura.dedun.compose.nostr.chronicle.ChronicleRequestEvent
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.chronicle.ChronicleKinds

/**
 * Building the group's signed record into pages a member who lacks it can apply.
 *
 * See docs/member-chronicle.md. The short of it: a member added after the work was
 * done has none of it and never will, because a group-signed event is applied
 * locally by each device that took part and never goes on the wire. This is how
 * it gets to them.
 *
 * ### Carrying nothing, for now
 *
 * What a chronicle may carry is [ChronicleEvent.KINDS], and that is empty since
 * the document kinds it was built for were removed (docs/ceremony-creation.md,
 * Phase 7). Every entry point here takes the kinds as a parameter defaulting to
 * that list, and with it empty the chronicle is quiet: [requestIfEmpty] asks for
 * nothing, [sendTo] sends nothing, and a page from an older build applies
 * nothing. The machinery is kept, and tested with a list of the tests' own, for
 * the next kind that should join.
 *
 * ### The events are the chronicle
 *
 * `GroupSignedEvent` holds what the group signed, as it signed it, so
 * [signedEventsOf] reads it: no rebuild, no round-trip risk, and nothing that
 * depends on a row having kept every field of the event it came from.
 *
 * **The allowlist does real work on the way out.** The table holds everything
 * the group has ever signed, `GroupKeyStateEvent` included. So [signedEventsOf]
 * filters on the kinds before anything else, which is the same rule [applyPage]
 * applies on the way in. Without it `ChronicleEvent.build` would refuse the page
 * and take the whole chronicle down with it. What the filter is really stopping
 * is a member replaying any group-signed statement about the record as though it
 * were work, and [applyPage] refuses the same kinds coming the other way. The two
 * are a pair and neither is safe to drop on the strength of the other.
 *
 * ### Nothing unverifiable leaves
 *
 * Every event is checked with [GroupKeyStateEvent.isSignedByRoom] before it is
 * packed, against the same room id the recipient will check it with. That is not
 * politeness towards the receiver, who checks anyway. It is what keeps a
 * chronicle honest about its own size: dropping a payload here rather than
 * letting the recipient drop it means the page count says what will actually
 * arrive.
 */
object ChronicleManager {
    private const val TAG = "ChronicleManager"

    private val logger = Logger.withTag(TAG)

    /**
     * Every group-signed event this device holds for [chatRoomId], paged and
     * addressed to [recipient].
     *
     * Empty when there is nothing to send -- a room with no shared key, a room
     * whose work is all member rumors, or a device that is itself behind. An
     * empty result is not an error and the caller should not report one: the
     * honest answer to "send them the history" in a room with no signed history
     * is nothing.
     */
    suspend fun assemble(
        database: DedunDatabase,
        chatRoomId: String,
        recipient: HexKey,
        chronicleId: String = RandomInstance.bytes(32).toHex(),
        createdAt: Long = TimeUtils.now(),
        kinds: ChronicleKinds = ChronicleEvent.KINDS,
    ): List<EventTemplate<ChronicleEvent>> {
        val held = signedEventsOf(database, chatRoomId, kinds)

        // Nothing leaves that the recipient could not check for themselves. A
        // drop here is a row that has drifted from the event it recorded, which
        // nothing in this app does on purpose.
        val verified = held.filter { GroupKeyStateEvent.isSignedByRoom(it, chatRoomId) }
        if (verified.size != held.size) {
            logger.d(
                "Leaving ${held.size - verified.size} of ${held.size} event(s) out of " +
                    "$chatRoomId's chronicle: nothing verifiably signed by the room"
            )
        }

        val pages = paginate(kinds.inApplyOrder(verified))
        if (pages.isEmpty()) {
            logger.i("Nothing signed to chronicle for room $chatRoomId")
            return emptyList()
        }

        logger.i(
            "Chronicling ${verified.size} event(s) for $chatRoomId as $chronicleId, " +
                "${pages.size} page(s) for ${recipient.take(8)}"
        )

        return pages.mapIndexed { index, payloads ->
            ChronicleEvent.build(
                payloads = payloads,
                chronicleId = chronicleId,
                index = index,
                count = pages.size,
                recipient = recipient,
                createdAt = createdAt,
                kinds = kinds,
            )
        }
    }

    // ---- Asking, and answering -------------------------------------------

    /**
     * Ask the group for its signed history, if this device holds none of it.
     *
     * Called on entering a room. Does nothing while [kinds] is empty, which it
     * is in this app at the moment -- see the class comment.
     *
     * The condition is deliberately crude -- nothing of a chroniclable kind on
     * file -- because the three cases it has to catch look identical from inside
     * the database and should not be told apart:
     *
     *  - a member added after the work was done, whose invite may be recent;
     *  - a reinstall, whose invite is long past;
     *  - a second device, which was never invited at all.
     *
     * Returns whether a request went out.
     *
     * ### Why a device asks rather than being sent one
     *
     * A push from the inviter is an application message in the epoch the add
     * created, and one that overtakes the Welcome is dropped and not deferred --
     * silently, while the inviter sees a success. See docs/marmot-membership.md.
     * Sending a request cannot lose that race, because being able to send it is
     * the proof the race was won: a device that can put an application message
     * into the room has processed its Welcome.
     */
    suspend fun requestIfEmpty(
        database: DedunDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        kinds: ChronicleKinds = ChronicleEvent.KINDS,
    ): Boolean {
        // Quiet while a chronicle may carry nothing: there is no answer worth
        // asking for, and asking would put a request on the wire and a line in
        // the transcript on every room the member opens.
        if (kinds.isEmpty()) return false

        val chatRoom = database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom ?: return false
        if (chatRoom.chronicleRequestedAt != null) return false

        val holdsWork = signedEventsOf(database, chatRoomId, kinds).isNotEmpty()

        if (holdsWork) return false

        queue(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = userPublicKey,
            kind = ChronicleRequestEvent.KIND,
            tags = ChronicleRequestEvent.build().tags,
            content = "",
        )

        database.chatRoomDao().upsert(chatRoom.copy(chronicleRequestedAt = Clock.System.now()))

        announce(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = userPublicKey,
            messageType = ChatMessage.TYPE_CHRONICLE_REQUESTED,
            content = "Asked this group for its signed work",
        )

        logger.i("Asked $chatRoomId for its signed history")

        return true
    }

    /**
     * Send [recipient] everything this device can prove about [chatRoomId].
     *
     * Both ways a chronicle goes out are this one call: answering a request, and
     * the push behind a Welcome. Named for what it does rather than for either
     * occasion, because the two differ only in who decided.
     *
     * Any member may send one and none is elected to. A duplicate costs bandwidth
     * and nothing else -- pages are idempotent and every member who is not the
     * recipient ignores them -- so it is waste rather than damage, and the
     * stand-down that would avoid it is an optimisation to add on top rather than
     * a correctness gap to close first.
     *
     * A device with nothing signed sends nothing. Silence is the honest reply
     * from a member who is themselves still catching up, and an empty chronicle
     * would look like an answer.
     */
    suspend fun sendTo(
        database: DedunDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        recipient: HexKey,
        kinds: ChronicleKinds = ChronicleEvent.KINDS,
    ): Int {
        if (recipient.equals(userPublicKey, ignoreCase = true)) return 0

        val pages = assemble(database, chatRoomId, recipient, kinds = kinds)
        if (pages.isEmpty()) {
            logger.i("Nothing signed here to send ${recipient.take(8)} for $chatRoomId")
            return 0
        }

        pages.forEach { page ->
            queue(
                database = database,
                chatRoomId = chatRoomId,
                userPublicKey = userPublicKey,
                kind = page.kind,
                tags = page.tags,
                content = page.content,
                createdAt = Instant.fromEpochSeconds(page.createdAt),
            )
        }

        val name = database.profileDao().getProfileByPublicKey(recipient)
            ?.humanReadableNameOrPubkey()
            ?: recipient.take(8)

        announce(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = userPublicKey,
            messageType = ChatMessage.TYPE_CHRONICLE_SENT,
            content = "Sent this group's signed work to $name",
        )

        logger.i("Sending ${recipient.take(8)} ${pages.size} chronicle page(s) for $chatRoomId")

        return pages.size
    }

    /**
     * One line in the room's transcript.
     *
     * Written by the device it happened on, for itself. None of these travels --
     * a chronicle is not an event in the group's life, it is one member being
     * caught up -- so a member watching from the side sees nothing, correctly.
     *
     * Content is a whole sentence rather than a predicate, so these stay out of
     * the AUTHORED sets and nothing prefixes a name to them. See
     * [ChatMessage.CHRONICLE_TYPES].
     */
    private suspend fun announce(
        database: DedunDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        messageType: String,
        content: String,
    ) {
        database.chatMessageDao().upsert(
            ChatMessage(
                content = content,
                messageType = messageType,
                chatRoomId = chatRoomId,
                senderPublicKey = userPublicKey,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
            )
        )
    }

    /**
     * Put one event on the room's outbound queue.
     *
     * The same shape `FrostSigningManager.broadcast` uses: a `MarmotInnerEvent`
     * with no group event yet, which the notary picks up and encrypts into a
     * kind:445 for the room. The id is the rumor id the outbound pipeline
     * recomputes from these fields when it assembles the event to encrypt.
     *
     * **No `ChatMessage`.** Broadcast does not depend on one -- it is
     * unconditional in `encryptAndSendMarmotInnerEvent`, which is what let the
     * signing sessions travel with no transcript line -- and a chronicle that
     * filed one per page would put a row of envelopes in the room's history. One
     * line per chronicle is right, and it is not writable from here, because the
     * pages of a chronicle are indistinguishable from each other at this point.
     */
    private suspend fun queue(
        database: DedunDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        kind: Int,
        tags: TagArray,
        content: String,
        createdAt: Instant = Clock.System.now(),
    ) {
        database.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = EventHasher.hashId(
                    pubKey = userPublicKey,
                    createdAt = createdAt.epochSeconds,
                    tags = tags,
                    content = content,
                    kind = kind
                ),
                publicKey = userPublicKey,
                kind = kind,
                createdAt = createdAt,
                tags = tags,
                content = content,
                chatRoomId = chatRoomId,
            )
        )
    }

    // ---- Receiving ------------------------------------------------------

    /**
     * What one pass over a room's stored pages did.
     *
     * [skipped] and [failed] are kept apart because only one of them is worth
     * trying again. A payload the allowlist or the signature refused will be
     * refused identically forever; a payload that threw was probably missing a
     * row it depends on, and the next page may bring it.
     */
    data class Outcome(
        val applied: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
    ) {
        operator fun plus(other: Outcome) = Outcome(
            applied = applied + other.applied,
            skipped = skipped + other.skipped,
            failed = failed + other.failed,
        )
    }

    /**
     * Take delivery of a chronicle page for [userPublicKey].
     *
     * The page is already on disk by the time this runs -- the inbound path
     * stores every inner event it decrypts before dispatching on kind -- so this
     * does not apply the arriving page as such. It sweeps every page the room
     * has, which covers the new one and any that arrived before the rows they
     * depend on.
     *
     * **A device that is not the named recipient does nothing.** The page is an
     * ordinary group message and it can read it; it has no reason to. It already
     * holds the work, and re-applying would rewrite every one of its rows to
     * point at a chronicle page rather than at the event that introduced it. That
     * is also what keeps the sweep bounded: only the member being caught up ever
     * builds the list.
     */
    suspend fun receive(
        database: DedunDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        page: Event,
        kinds: ChronicleKinds = ChronicleEvent.KINDS,
    ): Outcome {
        val recipient = ChronicleEvent(
            id = page.id,
            pubKey = page.pubKey,
            createdAt = page.createdAt,
            tags = page.tags,
            content = page.content,
            sig = page.sig,
        ).recipient()

        if (!recipient.equals(userPublicKey, ignoreCase = true)) {
            logger.d("Chronicle page ${page.id.take(8)} is for ${recipient?.take(8)}; not applying")
            return Outcome()
        }

        return sweep(database, chatRoomId, userPublicKey, kinds)
    }

    /**
     * Apply every chronicle page this room holds for [userPublicKey], repeatedly,
     * until a pass stops making progress.
     *
     * Pages arrive over relays in no order, so page 3 can land before page 2 and
     * its chunks have no chapter to hang off yet. Those payloads throw a foreign
     * key violation and would be lost -- unless something runs them again.
     *
     * **No table is needed for that**, because the inbound path already stores
     * every inner event it decrypts. This is the same shape
     * `FrostSigningManager.replayStoredMessages` has, and for the same reason:
     * nothing was actually lost, it just had nowhere to go at the time.
     *
     * **Progress is measured by failures falling, not by rows written.** Every
     * write here is an upsert keyed on the event id, so "applied something" is
     * true on every pass forever and would not terminate. A pass that fails fewer
     * payloads than the last one learned something; a pass that does not is as
     * far as this chronicle gets, and the rest is a hole to be filled by another
     * page or another chronicle.
     *
     * **The answer is the last pass, not the sum of them.** Accumulating would
     * count a payload once per pass it survived and report failures the next pass
     * went on to fix, so `failed > 0` would stop meaning "still missing". The
     * final pass is the settled state: what these pages can apply, what they will
     * never apply, and what is still waiting on something that has not arrived.
     */
    suspend fun sweep(
        database: DedunDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        kinds: ChronicleKinds = ChronicleEvent.KINDS,
    ): Outcome {
        val pages = database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId, listOf(ChronicleEvent.KIND))
            .filter { addressedTo(it, userPublicKey) }

        if (pages.isEmpty()) return Outcome()

        var pass = Outcome()
        var previousFailures = Int.MAX_VALUE

        while (true) {
            pass = Outcome()
            pages.forEach { pass += applyPage(database, chatRoomId, it, kinds) }

            if (pass.failed == 0 || pass.failed >= previousFailures) {
                if (pass.failed > 0) {
                    logger.w(
                        "Stopped sweeping $chatRoomId with ${pass.failed} payload(s) still " +
                            "unapplied: nothing in the pages it holds satisfies them"
                    )
                }
                break
            }

            previousFailures = pass.failed
        }

        logger.i(
            "Swept ${pages.size} chronicle page(s) for $chatRoomId: " +
                "${pass.applied} applied, ${pass.skipped} skipped, ${pass.failed} left"
        )

        // An answer arrived, so the room may ask again if it turns out to be a
        // partial one. Cleared on anything applied rather than on the chronicle
        // reporting itself complete: a page count is the sender's claim about the
        // transfer, not about the group's record, and a member who left work out
        // would otherwise have the last word.
        if (pass.applied > 0) {
            database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.let { chatRoom ->
                if (chatRoom.chronicleRequestedAt != null) {
                    database.chatRoomDao().upsert(chatRoom.copy(chronicleRequestedAt = null))

                    // One line per answered request, which is as close to one per
                    // chronicle as this can get: the pages of a chronicle are not
                    // distinguishable from each other here, and clearing the stamp
                    // is exactly the moment a catch-up stops being pending.
                    //
                    // A push behind a Welcome writes none, because the room was
                    // never asked. That is right: it lands before the member has
                    // opened the room, and "caught up on work you have not seen
                    // yet" is a line about nothing.
                    announce(
                        database = database,
                        chatRoomId = chatRoomId,
                        userPublicKey = userPublicKey,
                        messageType = ChatMessage.TYPE_CHRONICLE_RECEIVED,
                        content = "Caught up on ${pass.applied} item(s) of this group's signed work",
                    )
                }
            }
        }

        return pass
    }

    private fun addressedTo(stored: MarmotInnerEvent, userPublicKey: HexKey): Boolean =
        ChronicleEvent(
            id = stored.id,
            pubKey = stored.publicKey,
            createdAt = stored.createdAt.epochSeconds,
            tags = stored.tags,
            content = stored.content,
            sig = "",
        ).recipient().equals(userPublicKey, ignoreCase = true)

    /**
     * One page, checked payload by payload and applied in dependency order.
     *
     * **Per payload, not per page.** A forged payload sitting beside honest ones
     * costs itself and nothing else -- the same rule `MarmotInboundManager` uses
     * for a forged direct message, and for the same reason: this runs inside the
     * inbound transaction, and one bad event must not take the room down with it.
     * Refusing the whole page would also let a single forgery deny an entire
     * chronicle.
     *
     * The page's own framing is still all-or-nothing; see
     * [ChronicleEvent.decodePage] for why those two are not in tension.
     */
    private suspend fun applyPage(
        database: DedunDatabase,
        chatRoomId: String,
        stored: MarmotInnerEvent,
        kinds: ChronicleKinds,
    ): Outcome {
        val payloads = ChronicleEvent.decodePage(stored.content)
        if (payloads == null) {
            logger.w("Chronicle page ${stored.id.take(8)} does not read as a page; dropping it")
            return Outcome()
        }

        // Read once for the page rather than per payload: every event in an
        // chronicle was signed by the room it is arriving in, so they all share
        // its path. Null when the recipient has no key state yet -- which is
        // the normal case for the member a chronicle exists for, and why
        // `GroupSignedEvent.derivationPath` is nullable and `record` fills it
        // in later rather than overwriting it with null.
        val derivationPath = GroupKeyStateManager.keyStateFor(database, chatRoomId)?.derivationPath

        var outcome = Outcome()

        kinds.inApplyOrder(payloads).forEach { payload ->
            // The allowlist first, and it is not a formality. Verification admits
            // an event to the apply path on the strength of the group's
            // signature, which makes every kind the group has ever signed
            // replayable by any member at any time -- a GroupKeyStateEvent from
            // an earlier epoch passes the signature check perfectly.
            if (!kinds.isChroniclable(payload.kind)) {
                logger.w(
                    "Chronicle page ${stored.id.take(8)} carries kind ${payload.kind}, " +
                        "which a chronicle may not deliver; dropping ${payload.id.take(8)}"
                )
                outcome += Outcome(skipped = 1)
                return@forEach
            }

            if (!GroupKeyStateEvent.isSignedByRoom(payload, chatRoomId)) {
                logger.w(
                    "Chronicle page ${stored.id.take(8)} carries ${payload.id.take(8)}, " +
                        "which room $chatRoomId did not sign; dropping it"
                )
                outcome += Outcome(skipped = 1)
                return@forEach
            }

            outcome += try {
                // Kept as the group signed it, before it is turned into rows.
                // The check above is what earns it that: a payload reaching here
                // is one the room signed, which is the same standard
                // `FrostSigningManager` records its own batches on. Without this
                // a member who was handed their history would hold the rows and
                // none of the events, and could never build a chronicle of their
                // own to hand on.
                database.groupSignedEventDao().record(
                    GroupSignedEvent.fromEvent(
                        event = payload,
                        chatRoomId = chatRoomId,
                        derivationPath = derivationPath,
                    )
                )

                // The chat line this returns is deliberately dropped rather than
                // filed. ChatMessage has an autoGenerate primary key, so there is
                // no id to dedupe on and every applied payload would mint a new
                // row -- giving the recipient a synthetic transcript dated now,
                // and another one on every pass of the sweep. The chronicle
                // restores the work; the conversation is forward secret and stays
                // gone.
                ChatMessage.applyInnerEvent(
                    database = database,
                    groupId = chatRoomId,
                    event = payload,
                    marmotGroupEventId = stored.marmotGroupEventId,
                    marmotInnerEventId = stored.id,
                    senderPublicKey = payload.pubKey,
                    isUserMessage = false,
                    createdAt = stored.createdAt,
                    groupSignedEventId = payload.id,
                )
                Outcome(applied = 1)
            } catch (error: Throwable) {
                // Almost always a foreign key: this payload names a row that is
                // in a page which has not arrived yet. Retryable, which is what
                // the sweep is for, so it is counted rather than logged loudly.
                logger.d(
                    "Could not apply ${payload.id.take(8)} from chronicle page " +
                        "${stored.id.take(8)} yet: ${error.message}"
                )
                Outcome(failed = 1)
            }
        }

        return outcome
    }

    /**
     * Every chroniclable event this device holds for the room, as the group
     * signed it.
     *
     * Order does not matter at this point; [ChronicleKinds.inApplyOrder] settles
     * it afterwards.
     */
    private suspend fun signedEventsOf(
        database: DedunDatabase,
        chatRoomId: String,
        kinds: ChronicleKinds,
    ): List<Event> = database.groupSignedEventDao()
        .getByChatRoomId(chatRoomId)
        // The allowlist, applied to the source that can actually trip it --
        // see the class comment. A key state on file is the room's own, and
        // sending it would be handing every member a validly signed
        // statement about what the room signs with, replayable forever.
        .filter { kinds.isChroniclable(it.kind) }
        .map { it.toEvent() }

    /**
     * [events] cut into pages that fit, keeping the order they arrive in.
     *
     * Greedy: fill a page until the next event would cross either cap. Both are
     * checked because they bind different chronicles -- a room of one-line events
     * hits the count first and a room of long ones hits the bytes.
     *
     * The bytes are the page's *escaped* bytes -- what it will occupy as the
     * `content` of the Marmot app event that carries it, which is what a
     * conformant decoder bounds. Dense JSON payloads grow by half on the way in,
     * and a page measured raw was over that bound while under this one. See
     * [ChronicleEvent.escapedByteSize].
     *
     * An event too large to share a page with anything is given one of its own.
     * One too large for even that is dropped with a log rather than failing the
     * chronicle: an event nobody can chronicle is a hole, and a member who gets
     * nothing at all is a bigger one.
     */
    private fun paginate(events: List<Event>): List<List<Event>> {
        val pages = mutableListOf<List<Event>>()
        var page = mutableListOf<Event>()
        // The brackets an empty page already costs.
        var bytes = 2

        events.forEach { event ->
            // Plus the comma this event needs if it is not first on its page.
            val size = ChronicleEvent.escapedByteSize(event.toJson()) + 1

            if (2 + size > ChronicleEvent.MAX_PAGE_BYTES) {
                logger.w(
                    "Event ${event.id} is $size bytes and will not fit a " +
                        "${ChronicleEvent.MAX_PAGE_BYTES}-byte page; leaving it out of the chronicle"
                )
                return@forEach
            }

            val full = page.isNotEmpty() &&
                (bytes + size > ChronicleEvent.MAX_PAGE_BYTES || page.size >= ChronicleEvent.MAX_PAGE_EVENTS)

            if (full) {
                pages.add(page)
                page = mutableListOf()
                bytes = 2
            }

            page.add(event)
            bytes += size
        }

        if (page.isNotEmpty()) pages.add(page)

        return pages
    }
}
