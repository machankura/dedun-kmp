package com.machankura.dedun.compose.database.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * When a request line in a transcript stops asking for something.
 *
 * The transcript renders a request with a tint and a "Review" affordance, and
 * that is a promise: tapping it leads to a decision still there to be made.
 * Keeping the promise means knowing when the decision has gone, and the rows are
 * all there is to know it from -- a line rendered days later has no session
 * loaded, and the room may have signed several things since.
 *
 * A signing line says which session it belongs to, so those are read exactly. A
 * ceremony line, and any line written before that column existed, is read by the
 * clock, which is right for the one ritual a room runs at a time.
 *
 * Two ways for a request to be over, and they are not the same. Answering it
 * leaves a line of the reader's own and earns the tick. A session ending
 * underneath it leaves nothing of theirs at all: a member who declined published
 * nothing, and a quorum that signed without them wanted nothing. Both must drop
 * the summons; neither may claim the member signed.
 */
class TranscriptRequestStateTest {
    private val user = "u".repeat(64)
    private val other = "o".repeat(64)

    private var lastId = 0L

    /** One transcript row, with only the five fields either rule reads. */
    private fun line(
        type: String,
        at: Long,
        sender: String = user,
        session: String? = null
    ) = ChatMessage(
        id = ++lastId,
        senderPublicKey = sender,
        isUserMessage = sender == user,
        giftWrapPayloadId = null,
        marmotGroupEventId = null,
        marmotInnerEventId = null,
        chatRoomId = "room",
        content = "",
        messageType = type,
        frostSigningSessionId = session,
        createdAt = Instant.fromEpochSeconds(at)
    )

    @Test
    fun `a signing request nobody has acted on is still asking`() {
        val request = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 10)
        val transcript = listOf(line(ChatMessage.TYPE_FROST_STARTED, at = 9, sender = other), request)

        assertEquals(emptySet(), ChatMessage.answeredRequests(transcript))
        assertEquals(emptySet(), ChatMessage.settledRequests(transcript))
    }

    @Test
    fun `publishing the nonce answers the request that asked for it`() {
        val request = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 10)
        val transcript = listOf(request, line(ChatMessage.TYPE_FROST_NONCE, at = 11))

        assertEquals(setOf(request.id), ChatMessage.answeredRequests(transcript))
    }

    @Test
    fun `somebody else's nonce answers nothing`() {
        // The fulfilment has to be this device's own: a transcript is full of other
        // members taking the step this reader has yet to take.
        val request = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 10)
        val transcript = listOf(request, line(ChatMessage.TYPE_FROST_NONCE, at = 11, sender = other))

        assertEquals(emptySet(), ChatMessage.answeredRequests(transcript))
    }

    @Test
    fun `a refusal that abandons the session settles the request without claiming it was signed`() {
        // Declining publishes no nonce, so there is no fulfilment to find. When
        // the refusal is the one that leaves too few members to sign, the failure
        // it writes is the only trace, and it has to be enough -- otherwise the
        // line goes on offering a decision already made.
        val request = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 10)
        val transcript = listOf(request, line(ChatMessage.TYPE_FROST_FAILED, at = 11))

        assertEquals(setOf(request.id), ChatMessage.settledRequests(transcript))
        assertEquals(emptySet(), ChatMessage.answeredRequests(transcript))
    }

    @Test
    fun `a refusal the session survives settles the refuser's request and nothing else`() {
        // A t-of-n key can do without this member, so their refusal ends nothing
        // -- the session is still open and the others may sign it. What has gone
        // is this reader's decision, so their line stops asking, and it earns no
        // tick: nothing of theirs is in whatever the group goes on to sign.
        val request = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 10)
        val transcript = listOf(request, line(ChatMessage.TYPE_FROST_DECLINED, at = 11))

        assertEquals(setOf(request.id), ChatMessage.settledRequests(transcript))
        assertEquals(emptySet(), ChatMessage.answeredRequests(transcript))
    }

    @Test
    fun `somebody else's refusal leaves the request asking`() {
        // Another member declining takes one of the n away and changes nothing
        // about what this reader is being asked -- if anything the group needs
        // their answer more.
        val request = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 10)
        val transcript = listOf(request, line(ChatMessage.TYPE_FROST_DECLINED, at = 11, sender = other))

        assertEquals(emptySet(), ChatMessage.settledRequests(transcript))
        assertEquals(emptySet(), ChatMessage.answeredRequests(transcript))
    }

    @Test
    fun `a group that signs without this member settles their request`() {
        // A t-of-n key does not need everybody. Nothing of this member's is in the
        // signature and nothing of theirs was ever published, so answered stays
        // empty -- but there is no longer anything for them to decide.
        val request = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 10)
        val transcript = listOf(request, line(ChatMessage.TYPE_FROST_COMPLETE, at = 12, sender = other))

        assertEquals(setOf(request.id), ChatMessage.settledRequests(transcript))
        assertEquals(emptySet(), ChatMessage.answeredRequests(transcript))
    }

    @Test
    fun `an earlier session's ending does not close a later request`() {
        // Rooms sign more than once, and the previous session's last line sits
        // above this one's first.
        val request = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 20)
        val transcript = listOf(line(ChatMessage.TYPE_FROST_COMPLETE, at = 9, sender = other), request)

        assertEquals(emptySet(), ChatMessage.settledRequests(transcript))
    }

    @Test
    fun `a nonce answers its own session's request and not the other's`() {
        // The case this column exists for. A chapter and the translation that
        // depends on it are proposed as two sessions at once, so the transcript
        // holds two open requests, and answering one leaves the other asking.
        val chapter = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 10, session = "chapter")
        val translation = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 11, session = "translation")

        val transcript = listOf(
            chapter,
            translation,
            line(ChatMessage.TYPE_FROST_NONCE, at = 12, session = "chapter")
        )

        assertEquals(setOf(chapter.id), ChatMessage.answeredRequests(transcript))
        assertEquals(emptySet(), ChatMessage.settledRequests(transcript))
    }

    @Test
    fun `one session finishing settles nothing in the other`() {
        // Same two proposals, ended the other way: a quorum signs the chapter
        // without this member. Their translation request is untouched by it --
        // the group is still waiting on them for that one.
        val chapter = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 10, session = "chapter")
        val translation = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 11, session = "translation")

        val transcript = listOf(
            chapter,
            translation,
            line(ChatMessage.TYPE_FROST_COMPLETE, at = 12, sender = other, session = "chapter")
        )

        assertEquals(setOf(chapter.id), ChatMessage.settledRequests(transcript))
        assertEquals(emptySet(), ChatMessage.answeredRequests(transcript))
    }

    @Test
    fun `a line that names no session is still read by the clock`() {
        // Rows written before the column existed carry null, and there is nothing
        // else to read them by. Rooms that old could only sign one thing at a
        // time, so the clock is not a guess there -- it is the whole truth.
        val request = line(ChatMessage.TYPE_FROST_APPROVAL_NEEDED, at = 10, session = "chapter")

        val transcript = listOf(request, line(ChatMessage.TYPE_FROST_NONCE, at = 11))

        assertEquals(setOf(request.id), ChatMessage.answeredRequests(transcript))
    }

    @Test
    fun `a ceremony step is settled by nothing`() {
        // Signing is the one thing a member can refuse, so it is the only place a
        // request can be over without them having answered it. A ceremony step is
        // either taken or still waited on, and reading either ending as the end of
        // one would drop a summons the ritual is still stalled on.
        val request = line(ChatMessage.TYPE_DKG_APPROVAL_NEEDED_ROUND_1, at = 10)
        val transcript = listOf(
            request,
            line(ChatMessage.TYPE_FROST_FAILED, at = 11),
            line(ChatMessage.TYPE_DKG_FAILED, at = 12, sender = other)
        )

        assertEquals(emptySet(), ChatMessage.settledRequests(transcript))
    }

    @Test
    fun `each ceremony step is answered only by its own`() {
        val hostKey = line(ChatMessage.TYPE_DKG_APPROVAL_NEEDED_HOST_KEY, at = 10)
        val roundOne = line(ChatMessage.TYPE_DKG_APPROVAL_NEEDED_ROUND_1, at = 12)
        val transcript = listOf(hostKey, line(ChatMessage.TYPE_DKG_HOST_KEY, at = 11), roundOne)

        assertEquals(setOf(hostKey.id), ChatMessage.answeredRequests(transcript))
    }
}
