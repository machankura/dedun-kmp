package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * The group saying a member deposits with it, on one network: `docs/member-deposits.md`, D2.
 *
 * ```
 * kind:    30339
 * pubkey:  <the room>
 * content: ""
 * tags:
 *   ["d",           "<chain>:<member>"]
 *   ["p",           <member>]
 *   ["chain",       "mainnet" | …]
 *   ["branch",      "<b>", "<salt>"]               b = DepositBranch.of(room, member, salt)
 *   ["destination", <the member's signed 30338, whole>]
 * ```
 *
 * ### Why a group signature, once per member
 *
 * The statement is what makes every later address and invoice the member's without
 * another signature: an address on their branch is theirs by derivation, and an invoice is
 * issued against the statement's id. Signed by the room, a device that joins later is
 * handed it with the rest of the group's signed history and finds every member's branch,
 * a former member's included.
 *
 * ### Why it carries the destination
 *
 * Nobody deposits without a way back. The member's own signed payout destination, whole,
 * proves the member asked for it and says where their money goes back to, as of when they
 * started. Payouts use their newest destination; this one is the record.
 *
 * ### Write-once
 *
 * One per member per network: a signer refuses a second, and [firstPerMember] keeps the
 * first, because a second branch for one member would split their deposits across two.
 *
 * ### Why 30339
 *
 * After the payout destination, 30338. Nothing used 30339 to 30349.
 */
object DepositorStatementEvent {
    val KIND: Kind = 30339

    private const val D_TAG = "d"
    private const val MEMBER_TAG = "p"
    private const val CHAIN_TAG = "chain"
    private const val BRANCH_TAG = "branch"
    private const val DESTINATION_TAG = "destination"

    /** The `d` of [member]'s statement on [chain]. */
    fun identifier(chain: Chain, member: HexKey): String = "${GroupWalletStatementEvent.chainName(chain)}:${member.lowercase()}"

    /** What a depositor statement says. */
    data class Depositor(
        val member: HexKey,
        val chain: Chain,
        val branch: Long,
        val salt: Int,
        /** The member's destination as it was when the group opened their deposits. */
        val destination: GroupPayoutDestination,
        val event: Event,
    ) {
        val createdAt: Long get() = event.createdAt
    }

    /** The statement opening [destination]'s member's deposits in [chatRoomId], on branch [salt]. */
    fun template(chatRoomId: String, destination: GroupPayoutDestination, salt: Int = 0, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> {
        require(destination.chatRoomId.equals(chatRoomId, ignoreCase = true)) { "the destination is another room's" }
        val branch = DepositBranch.of(chatRoomId, destination.member, salt)
        return EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, identifier(destination.chain, destination.member)),
                arrayOf(MEMBER_TAG, destination.member.lowercase()),
                arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(destination.chain)),
                arrayOf(BRANCH_TAG, branch.toString(), salt.toString()),
                arrayOf(DESTINATION_TAG, destination.event.toJson()),
            ),
            content = "",
        )
    }

    /**
     * What [event] says about [chatRoomId], by its shape and its embedded destination -- every
     * check but the room's signature, for a signer reading a proposal before the group has
     * signed it:
     *
     *  1. one each of `d`, `p`, `chain`, `branch` and `destination`, and `d` agreeing with the
     *     other two;
     *  2. the branch recomputes from the room, the member and the salt;
     *  3. the destination is a verified 30338 by that member, about this room and network --
     *     a destination, not a removal.
     */
    fun read(event: Event, chatRoomId: String): Depositor? = runCatching {
        if (event.kind != KIND) return null
        fun single(name: String): Array<String>? = event.tags.filter { it.isNotEmpty() && it[0] == name }.singleOrNull()?.takeIf { it.size >= 2 }

        val member = single(MEMBER_TAG)?.get(1)?.lowercase()?.takeIf { it.length == 64 } ?: return null
        val chain = single(CHAIN_TAG)?.get(1)?.let(GroupWalletStatementEvent::chainNamed) ?: return null
        if (single(D_TAG)?.get(1) != identifier(chain, member)) return null

        val branchTag = single(BRANCH_TAG)?.takeIf { it.size >= 3 } ?: return null
        val branch = branchTag[1].toLongOrNull() ?: return null
        val salt = branchTag[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        if (branch != DepositBranch.of(chatRoomId, member, salt)) return null

        val embedded = single(DESTINATION_TAG)?.get(1)?.let { Event.fromJsonOrNull(it) } ?: return null
        val destination = GroupPayoutDestinationEvent.verified(embedded) as? GroupPayoutDestination ?: return null
        if (destination.member != member || destination.chain != chain) return null
        if (!destination.chatRoomId.equals(chatRoomId, ignoreCase = true)) return null

        Depositor(member, chain, branch, salt, destination, event)
    }.getOrNull()

    /** [event] as [chatRoomId]'s statement: [read], and signed by the room itself. */
    fun verify(event: Event, chatRoomId: String): Depositor? {
        val depositor = read(event, chatRoomId) ?: return null
        return depositor.takeIf { GroupKeyStateEvent.isSignedByRoom(event, chatRoomId) }
    }

    /**
     * Each member's statement among [events] on [chain] -- the first the group signed, by its own
     * timestamp with the id breaking a tie, since a statement is write-once and a second would be
     * a second branch.
     */
    fun firstPerMember(events: List<Event>, chatRoomId: String, chain: Chain): Map<HexKey, Depositor> =
        events.asSequence()
            .mapNotNull { verify(it, chatRoomId) }
            .filter { it.chain == chain }
            .groupBy { it.member }
            .mapValues { (_, statements) -> statements.minWith(ORDER) }

    val ORDER: Comparator<Depositor> = compareBy<Depositor>({ it.createdAt }, { it.event.id })
}
