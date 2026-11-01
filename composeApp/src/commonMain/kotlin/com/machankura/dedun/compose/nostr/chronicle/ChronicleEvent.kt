package com.machankura.dedun.compose.nostr.chronicle

import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.serialization.json.jsonArray
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.chronicle.tags.ChronicleIdTag
import com.machankura.dedun.compose.nostr.chronicle.tags.ChroniclePageTag
import com.machankura.dedun.compose.nostr.chronicle.tags.ChronicleRecipientTag

/**
 * 30327
 *
 * A page of the group's signed record, sent to a member who does not have it.
 *
 * A member added after the work was done sees none of it and never will. MLS
 * gives them no history -- correctly, that is forward secrecy -- but the sharper
 * problem is that group-signed events never travel at all: `FrostSigningManager`
 * applies a finished signature locally on every device that took part, because
 * the outbound pipeline would re-author a rumor as its sender and strip the
 * group's signature off. A member who was not in the session has nothing to
 * derive the event from, and no later message carries it. This is that path.
 *
 * Each payload travels whole, keeping its own id, author and signature, so the
 * receiver checks it rather than believing it -- see [isSignedByRoom] on
 * `GroupKeyStateEvent`. The sender of a chronicle is therefore not trusted, which
 * is what lets any member answer.
 *
 * See docs/member-chronicle.md for the whole design, including the two things this
 * deliberately does not do: it never carries the chat, and it cannot make its
 * recipient able to *sign* anything.
 *
 * It carries nothing in this app at the moment -- see [KINDS].
 */
@Immutable
class ChronicleEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {

    /**
     * The signed events this page carries, or null when the content is not a
     * readable page.
     *
     * Nothing here is verified yet. Parsing says the framing is intact;
     * `ChronicleManager` is what asks whether the group signed each one, and it
     * asks per payload rather than per page.
     */
    fun payloads(): List<Event>? = decodePage(content)

    /** Which chronicle this page belongs to, or null if it names none. */
    fun chronicleId(): String? = tags.firstNotNullOfOrNull(ChronicleIdTag::parse)?.chronicleId

    /** Where this page sits in that chronicle, or null if it does not say. */
    fun page(): ChroniclePageTag? = tags.firstNotNullOfOrNull(ChroniclePageTag::parse)

    /** Who should act on this page. A hint -- see [ChronicleRecipientTag]. */
    fun recipient(): HexKey? = tags.firstNotNullOfOrNull(ChronicleRecipientTag::parse)?.pubKey

    companion object {
        /**
         * Sits past `GroupKeyStateEvent` at 30326, in the Marmot inner-event
         * space it shares with the FROST signing family.
         *
         * 30313 is not used, on `FrostSigningEvents`' own advice: the DKG's
         * 30310-30316 sit there, and once collided with document kinds that
         * shared their numbers. This family took numbers nobody else uses
         * instead.
         */
        const val KIND: Kind = 30327

        const val ALT_DESCRIPTION = "Group chronicle"

        /**
         * The most JSON one page may carry.
         *
         * A byte cap rather than a count alone, because the payloads vary by two
         * orders of magnitude -- a paragraph of text against a single URL.
         * Sized so the finished kind:445 clears the tightest relay limits with
         * room for the MLS framing and the NIP-44 expansion that sit outside it;
         * measure it against a real one rather than trusting the arithmetic.
         *
         * Under 64 KiB with room to spare, because that is the bound a conformant
         * Marmot decoder puts on the *whole* app payload -- `MarmotJson.MAX_INPUT_BYTES`
         * -- and this page rides inside one as its `content`, JSON-escaped and
         * wrapped in the six members of the envelope. The packer measures the page
         * as [escapedByteSize] -- the bytes it will occupy inside the envelope,
         * which for dense JSON content is half again the raw page -- against this
         * cap, so both the receiver's raw-bytes check and the decoder's bound hold
         * by construction. At 64 KiB the raw page alone was over the bound, and an
         * MDK peer dropped it without a word. See docs/marmot-v2.md, Phase 2.
         */
        const val MAX_PAGE_BYTES = 48 * 1024

        /**
         * The most payloads one page may carry.
         *
         * The byte cap bounds the transport; this bounds the receiver's *work*,
         * and they are not the same limit -- 64 KB of one-line events is a lot
         * more signature verifies than 64 KB of long ones.
         *
         * **Sized under what the byte cap allows, or it would never fire.** An
         * event carries a 64-character id, a 64-character pubkey and a
         * 128-character signature before it says anything at all, so the floor is
         * around 370 bytes and [MAX_PAGE_BYTES] cannot hold much past 130 of
         * them. A count cap above that ceiling is a check that cannot fail --
         * which is how this was first written, and a test that asserts a page at
         * exactly the cap decodes is what caught it. At 96 both caps bind
         * something: the count stops a page of many small payloads, the bytes
         * stop a page of few large ones.
         *
         * The two therefore have to be re-sized together. Raising [MAX_PAGE_BYTES]
         * without raising this narrows the count cap's job; raising this without
         * raising that retires it.
         *
         * Larger than `FrostSigningManager.MAX_BATCH_SIZE`, deliberately: a batch
         * item costs nonce generation, a FROST session and a native sign, where
         * a chronicle payload costs a signature verify and an upsert.
         */
        const val MAX_PAGE_EVENTS = 96

        /**
         * The kinds a chronicle may carry in this app: **none, at the moment.**
         *
         * It was built to hand a late member the group's signed documents --
         * dialects, artifacts, chapters and their translations -- and those kinds
         * were removed with the screens that made them (docs/ceremony-creation.md,
         * Phase 7). Everything else the group signs is left out on purpose:
         *
         *  - a `GroupKeyStateEvent` or a subgroup's birth certificate is a validly
         *    signed statement about the group that anyone holding a copy could
         *    replay -- see [ChronicleKinds];
         *  - wallet statements have their own way to a member who joins late.
         *
         * So the machinery stayed and carried nothing: with an empty list a device
         * asks for no chronicle, sends none, and applies nothing from one that an
         * older build sends -- see `ChronicleManager`. Adding a kind back is a
         * decision that belongs here, with the reason it is safe to replay.
         *
         * Two are back, for `docs/member-deposits.md`: a member who joins late has to
         * find every depositor's branch, a former member's included, or their coins go
         * missing from that member's balance, and every signed attribution, or their
         * tallies disagree with everybody else's. Both are safe to replay. A depositor
         * statement is write-once and the first per member counts, so an old copy says
         * what the newest does; an attribution is newest-wins by the group's own
         * timestamp, so an old one replayed never overrides a later correction. The
         * statement first: an attribution names depositors.
         *
         * A third for `docs/payouts-only.md`: every coin assignment, or a late joiner can't tell
         * whose a legacy or change coin is, and refuses a payout everybody else signs. Newest-wins
         * as an attribution is, so as safe to replay; after the statements, whose depositors it names.
         */
        val KINDS: ChronicleKinds = ChronicleKinds(listOf(DepositorStatementEvent.KIND, DepositAttributionEvent.KIND, CoinAssignmentEvent.KIND))

        /** Every kind a chronicle may carry in this app. */
        val CHRONICLABLE_KINDS: Set<Kind> = KINDS.all

        fun isChroniclable(kind: Kind): Boolean = KINDS.isChroniclable(kind)

        /**
         * The bytes [json] occupies once it is the `content` of a Marmot app event --
         * that is, once it is inside a JSON string.
         *
         * A page is JSON that travels as JSON: every quote in it becomes `\\"`, every
         * backslash `\\\\`, and the payloads' own content, already escaped once inside
         * the page, escapes again. Dense JSON content grows by half; content that is
         * nothing but quotes doubles. The decoder's 64 KiB bound is on the *escaped*
         * form, so the packer has to measure this and not the raw page -- see
         * [MAX_PAGE_BYTES]. The escape set is NIP-01's, which is what
         * `MarmotAppEvent.toJson` writes.
         */
        fun escapedByteSize(json: String): Int {
            var size = 0
            for (ch in json) {
                size += when {
                    ch == '"' || ch == '\\' || ch == '\n' || ch == '\r' || ch == '\t' ||
                        ch == '\u0008' || ch == '\u000C' -> 2
                    ch < ' ' -> 6
                    ch.code < 0x80 -> 1
                    ch.code < 0x800 -> 2
                    ch.isSurrogate() -> 2 // each half of a pair is two of the four bytes
                    else -> 3
                }
            }
            return size
        }

        /**
         * One page's payloads as its content: a JSON array of whole events.
         *
         * Always an array, even for one. `FrostSigningEvents.encodeProposal` has
         * a bare-object form only because a build predating batches has to be
         * able to read a single proposal, and there is no such build here -- both
         * kinds are new. Do not copy that shape.
         */
        fun encodePage(events: List<Event>): String =
            events.joinToString(separator = ",", prefix = "[", postfix = "]") { it.toJson() }

        /**
         * The other half, with both caps enforced.
         *
         * **All-or-nothing, and that is not in tension with dropping forged
         * payloads one at a time.** They are different questions. A page that
         * will not parse has lost its framing, so nothing in it can be trusted to
         * be what the sender wrote -- and a page silently shortened by one
         * element would leave a receiver believing it holds a complete chronicle
         * when the page count says so and the contents do not. A payload whose
         * signature does not verify is a well-framed page containing one bad
         * event, and costing its honest neighbours would let one forged payload
         * deny an entire chronicle.
         *
         * Both caps are checked here rather than at the call site, because this
         * is the boundary a remote party's page crosses. A chronicle is the second
         * place in this protocol where somebody else decides how much work this
         * device does; the first grew a cap on the way in for the same reason.
         */
        fun decodePage(content: String): List<Event>? {
            if (content.encodeToByteArray().size > MAX_PAGE_BYTES) return null
            if (!content.trimStart().startsWith("[")) return null

            val elements = runCatching { CommonJson.parseToJsonElement(content).jsonArray }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_PAGE_EVENTS }
                ?: return null

            return elements.map { element -> Event.fromJsonOrNull(element.toString()) ?: return null }
        }

        /**
         * A page of [payloads], page [index] of [count] in chronicle [chronicleId],
         * for [recipient].
         *
         * The caller sorts and pages; this only writes what it is handed. Both
         * caps are checked anyway, because a page built over either of them is a
         * page nobody can read back and it is better to fail where it was made.
         */
        fun build(
            payloads: List<Event>,
            chronicleId: String,
            index: Int,
            count: Int,
            recipient: HexKey,
            createdAt: Long = TimeUtils.now(),
            kinds: ChronicleKinds = KINDS,
            initializer: TagArrayBuilder<ChronicleEvent>.() -> Unit = {},
        ): EventTemplate<ChronicleEvent> {
            require(payloads.isNotEmpty()) { "A chronicle page carries at least one event" }
            require(payloads.size <= MAX_PAGE_EVENTS) {
                "A chronicle page carries at most $MAX_PAGE_EVENTS events, not ${payloads.size}"
            }
            payloads.forEach {
                require(kinds.isChroniclable(it.kind)) { "A chronicle may not carry kind ${it.kind}" }
            }

            val content = encodePage(payloads)
            require(content.encodeToByteArray().size <= MAX_PAGE_BYTES) {
                "A chronicle page carries at most $MAX_PAGE_BYTES bytes"
            }

            return eventTemplate<ChronicleEvent>(KIND, content, createdAt) {
                alt(ALT_DESCRIPTION)
                addUnique(ChronicleIdTag.assemble(chronicleId))
                addUnique(ChroniclePageTag.assemble(index, count))
                addUnique(ChronicleRecipientTag.assemble(recipient))
                initializer()
            }
        }
    }
}
