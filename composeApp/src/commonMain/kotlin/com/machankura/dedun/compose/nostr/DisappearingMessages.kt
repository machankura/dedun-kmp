package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip17Dm.files.ChatMessageEncryptedFileHeaderEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.machankura.dedun.compose.nostr.confederation.ConfederationKinds
import com.machankura.dedun.compose.nostr.confederation.ConfederationRitualEvents
import com.machankura.dedun.compose.nostr.confederation.ConfederationSigningEvents
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.frost.ShareRefreshEvents
import com.machankura.dedun.compose.nostr.frost.ShareRepairEvents
import com.machankura.dedun.compose.nostr.groupdm.GroupEcdhEvents
import com.machankura.dedun.compose.nostr.inbox.InboxKeyRitualEvents

/**
 * When a NIP-17 rumor expires, worked out once, when it is queued. See
 * docs/disappearing-messages.md.
 *
 * Every event Dedun sends in a NIP-17 wrap expires (D2). What a person writes expires after their
 * policy, which may be Off. A protocol message always expires, after the longer of the policy and
 * the floor its protocol needs, because a ceremony round that outlived its ceremony on a relay
 * would be the one exception to "everything expires".
 *
 * Pure functions of their arguments, with no database, so the rules are testable without Room.
 */
object DisappearingMessages {
    /** NIP-40's tag. */
    const val TAG = "expiration"

    /** The policy that means "what I write does not expire". Protocol messages still do. */
    const val OFF: Long = 0L

    const val MINUTE: Long = 60L
    const val HOUR: Long = 60 * MINUTE
    const val DAY: Long = 24 * HOUR

    /** Below this, two phones' clocks and a slow catch-up can deliver a message already expired (D3). */
    const val MIN_SECONDS: Long = 5 * MINUTE

    /** Long enough that "a long time" is a real choice, short enough that relays treat it sanely (D3). */
    const val MAX_SECONDS: Long = 365 * DAY

    /** The policy a new identity starts with, and an existing one is given on upgrade (D9). */
    const val DEFAULT_SECONDS: Long = DAY

    /** What the settings list offers, Off first. Anything else in bounds is a custom length. */
    val PRESETS: List<Long> = listOf(OFF, HOUR, 8 * HOUR, DAY, 7 * DAY, 28 * DAY)

    /**
     * A signing round's floor. `docs/frost-session-resend.md` already repairs a round a relay
     * dropped, so this only stops a one-hour policy from turning every signing into a race.
     */
    const val SIGNING_FLOOR: Long = DAY

    /** A key ceremony's floor: it waits for every admin, and a DKG round has no resend. */
    const val CEREMONY_FLOOR: Long = 7 * DAY

    /** What a kind nobody classified gets: it still expires, never "never" (D2). */
    const val UNCLASSIFIED_FLOOR: Long = CEREMONY_FLOOR

    /**
     * How far past the rumor's expiry the wrap's can fall, the same two days quartz adds
     * (`GiftWrapEvent.build`), so that the randomized `created_at` never expires a wrap early.
     */
    const val WRAP_SLACK: Long = 2 * DAY

    /** The kinds a person writes: their words, a reaction to them, the deletion of one (D2). */
    val WRITTEN_KINDS: Set<Kind> = setOf(
        ChatMessageEvent.KIND,
        ChatMessageEncryptedFileHeaderEvent.KIND,
        ReactionEvent.KIND,
        DeletionRequestEvent.KIND,
    )

    private val SIGNING_KINDS: Set<Kind> =
        FrostSigningEvents.ALL + ConfederationSigningEvents.ALL + GroupEcdhEvents.ALL

    private val CEREMONY_KINDS: Set<Kind> =
        DkgRitualEvents.ALL + InboxKeyRitualEvents.ALL + ShareRefreshEvents.ALL +
            ShareRepairEvents.ALL + ConfederationRitualEvents.ALL + setOf(WelcomeEvent.KIND)

    /** Confederation statements carry their own expiry, the proposal's; this is only its ceiling. */
    private val STATEMENT_KINDS: Set<Kind> = ConfederationKinds.ALL

    sealed interface ExpiryClass {
        /** Something a person wrote: it follows their policy, Off included. */
        data object Written : ExpiryClass

        /**
         * A protocol message: it always expires, after at least [floorSeconds]. [classified] is
         * false for a kind that fell through to [UNCLASSIFIED_FLOOR], which a test refuses for
         * every kind the app is known to send.
         */
        data class Protocol(val floorSeconds: Long, val classified: Boolean = true) : ExpiryClass
    }

    fun classOf(kind: Kind): ExpiryClass = when (kind) {
        in WRITTEN_KINDS -> ExpiryClass.Written
        in SIGNING_KINDS -> ExpiryClass.Protocol(SIGNING_FLOOR)
        in CEREMONY_KINDS -> ExpiryClass.Protocol(CEREMONY_FLOOR)
        in STATEMENT_KINDS -> ExpiryClass.Protocol(ConfederationKinds.MAX_EXPIRY_SECONDS)
        else -> ExpiryClass.Protocol(UNCLASSIFIED_FLOOR, classified = false)
    }

    /** Whether [seconds] is a policy a person may choose: Off, or within the bounds. */
    fun isValidPolicy(seconds: Long): Boolean = seconds == OFF || seconds in MIN_SECONDS..MAX_SECONDS

    /** The policy in force: the conversation's override when it has one, else the identity's default (D4). */
    fun policyFor(roomOverride: Long?, identityDefault: Long): Long = roomOverride ?: identityDefault

    /**
     * The rumor's expiry, or null when it has none: a written kind under the policy Off, and
     * nothing else. A rumor that already carries a valid expiry keeps it (D5): a second tag would
     * get a confederation statement refused.
     */
    fun expiryFor(kind: Kind, createdAt: Long, policy: Long, tags: Array<Array<String>>): Long? {
        expirationOf(tags, createdAt)?.let { return it }
        return when (val expiryClass = classOf(kind)) {
            ExpiryClass.Written -> if (policy == OFF) null else createdAt + policy
            is ExpiryClass.Protocol -> createdAt + maxOf(policy, expiryClass.floorSeconds)
        }
    }

    /**
     * [tags] with the rumor's expiry added, or unchanged when it already has one or should have
     * none. Call it before hashing the rumor's id: the tag is one of the fields the id covers.
     */
    fun stamp(kind: Kind, createdAt: Long, policy: Long, tags: Array<Array<String>>): Array<Array<String>> {
        if (expirationOf(tags, createdAt) != null) return tags
        val expiry = expiryFor(kind, createdAt, policy, tags) ?: return tags
        return tags + arrayOf(arrayOf(TAG, expiry.toString()))
    }

    /**
     * The wrap's expiry: quartz's formula, `wrap.created_at + (rumor's lifetime) + 2 days`. The
     * wrap's `created_at` is already up to two days in the past, so this is the rumor's expiry
     * plus a random `[0, 2 days]`: later than the sender meant, never earlier, and not an exact
     * offset from a time the randomization was there to hide. Null when the rumor has none.
     */
    fun wrapExpiration(rumorExpiration: Long?, rumorCreatedAt: Long, wrapCreatedAt: Long): Long? {
        val lifetime = (rumorExpiration ?: return null) - rumorCreatedAt
        if (lifetime <= 0) return null
        return wrapCreatedAt + lifetime + WRAP_SLACK
    }

    /**
     * The first `expiration` tag's time, or null when there is none or it is malformed: not a
     * number, or not after [createdAt]. quartz reads an expiry at or before `created_at` as no
     * lifetime at all, and so does this.
     */
    fun expirationOf(tags: Array<Array<String>>, createdAt: Long? = null): Long? {
        val value = tags.firstOrNull { it.size >= 2 && it[0] == TAG }?.get(1)?.toLongOrNull() ?: return null
        if (createdAt != null && value <= createdAt) return null
        return value
    }

    /** NIP-40: an event is expired from its expiry on. */
    fun isExpired(expiresAt: Long, now: Long): Boolean = now >= expiresAt
}
