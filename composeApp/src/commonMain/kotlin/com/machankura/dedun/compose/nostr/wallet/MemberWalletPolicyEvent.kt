package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlin.time.Clock

/**
 * A group saying, with a quorum, whether it guards its members' wallets and at
 * what delay -- `docs/member-wallet-delay.md`.
 *
 * ```
 * open:    ["d", "member-wallets"], ["guardian_delay", "<blocks>"]
 * closed:  ["d", "member-wallets"], ["closed"]
 * ```
 *
 * Signed by the group's own quorum in its own room, about its own leaf: the
 * delay on the group's leaf in every wallet a member asks it to guard from now
 * on. Every group above keeps the delay it chose for the group, as it does in a
 * subgroup's wallet.
 *
 * ### The group's opt-in
 *
 * No policy means the group does not guard members' wallets. A closed one means
 * it has stopped: no new member wallets until a newer open one. Closing never
 * releases a wallet already guarded -- the group's leaf is in every one of its
 * addresses -- and a later policy never changes one, since each wallet's delay
 * is fixed in its certificate.
 *
 * ### Not the birth certificate
 *
 * The certificate is the parent's statement about the group, signed once at its
 * birth, and a root group has none. This is the group's decision about its own
 * leaf, which the group can change. See the note's *Where the delay should live*.
 *
 * ### Why 30337
 *
 * After the key notice (30336), the last of the member-wallet kinds.
 */
object MemberWalletPolicyEvent {
    val KIND: Kind = 30337

    /** The `d` tag: one policy about members' wallets per group, the newest winning. */
    const val IDENTIFIER = "member-wallets"

    private const val D_TAG = "d"
    private const val DELAY_TAG = "guardian_delay"
    private const val CLOSED_TAG = "closed"

    /** The group guards members' wallets, its own leaf at [delay] blocks. */
    fun open(delay: Long, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> {
        require(delay in GuardianDerivation.DELAY_RANGE) {
            "a guardian's delay is ${GuardianDerivation.MIN_DELAY} to ${GuardianDerivation.MAX_DELAY} blocks, not $delay"
        }
        return EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(arrayOf(D_TAG, IDENTIFIER), arrayOf(DELAY_TAG, delay.toString())),
            content = "",
        )
    }

    /** The group takes no new members' wallets. */
    fun closed(createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> = EventTemplate<Nothing>(
        createdAt = createdAt,
        kind = KIND,
        tags = arrayOf(arrayOf(D_TAG, IDENTIFIER), arrayOf(CLOSED_TAG)),
        content = "",
    )

    /** What a policy says, as its group signed it. */
    sealed interface Policy {
        val event: Event
        val createdAt: Long get() = event.createdAt

        /** The group guards members' wallets, its own leaf at [delay] blocks. */
        data class Open(val delay: Long, override val event: Event) : Policy

        /** The group has stopped taking new members' wallets. */
        data class Closed(override val event: Event) : Policy
    }

    /**
     * [event] as [chatRoomId]'s policy, or null when it is not one:
     *
     *  1. the kind, and the `d` tag;
     *  2. exactly one of a `guardian_delay` that reads and is in
     *     `GuardianDerivation.DELAY_RANGE`, or a bare `closed` -- a policy that
     *     says both, or neither, says nothing a device can act on;
     *  3. signed by [chatRoomId] itself, which any device can check holding the
     *     room's id alone.
     */
    fun verify(event: Event, chatRoomId: String): Policy? = runCatching {
        val policy = read(event) ?: return null
        if (!GroupKeyStateEvent.isSignedByRoom(event, chatRoomId)) return null
        policy
    }.getOrNull()

    /**
     * What [event] says, by its shape alone -- checks 1 and 2 of [verify], and
     * not the signature. For a proposal the group has not signed yet, which a
     * signer reads before agreeing to it; anything that acts on a policy goes
     * through [verify].
     */
    fun read(event: Event): Policy? = runCatching {
        if (event.kind != KIND) return null
        if (event.tags.count { it.size >= 2 && it[0] == D_TAG && it[1] == IDENTIFIER } != 1) return null
        if (event.tags.any { it.isNotEmpty() && it[0] == D_TAG && (it.size < 2 || it[1] != IDENTIFIER) }) return null

        val delays = event.tags.filter { it.isNotEmpty() && it[0] == DELAY_TAG }
        val closed = event.tags.filter { it.isNotEmpty() && it[0] == CLOSED_TAG }
        val policy = when {
            delays.size == 1 && closed.isEmpty() -> {
                val delay = delays.single().getOrNull(1)?.toLongOrNull()?.takeIf { it in GuardianDerivation.DELAY_RANGE }
                    ?: return null
                Policy.Open(delay, event)
            }
            delays.isEmpty() && closed.size == 1 -> Policy.Closed(event)
            else -> return null
        }
        policy
    }.getOrNull()

    /**
     * The newest of [events] that is [chatRoomId]'s policy, open or closed --
     * by the group's own timestamp, the id breaking a tie, so two devices that
     * read the same events in another order agree.
     */
    fun newestAmong(events: List<Event>, chatRoomId: String): Policy? =
        events.mapNotNull { verify(it, chatRoomId) }.maxWithOrNull(ORDER)

    /** The order policies are compared in: [newestAmong]'s, and the signers' staleness check. */
    val ORDER: Comparator<Policy> = compareBy<Policy>({ it.createdAt }, { it.event.id })
}
