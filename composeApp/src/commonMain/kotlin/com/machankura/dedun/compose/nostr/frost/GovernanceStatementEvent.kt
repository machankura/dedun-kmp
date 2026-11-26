package com.machankura.dedun.compose.nostr.frost

import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.marmot.appComponents.AdminPolicyV1
import com.vitorpamplona.mls.group.GroupView
import com.vitorpamplona.mls.group.PendingProposal
import com.vitorpamplona.mls.messages.Proposal
import com.vitorpamplona.mls.tree.Credential
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.machankura.dedun.compose.extensions.toHex

/**
 * What a keyed room's quorum signs before anything about the room may change: one
 * statement, naming one epoch, saying exactly what the commit made at that epoch
 * will do. docs/member-admin-promotion.md, R2 and Phase 3.
 *
 * ```
 * ["d", <room id>]
 * ["epoch", <E>]                      -- the epoch the commit is built at
 * ["add", <account>, <KeyPackage ref>]  -- one per member added
 * ["remove", <account>]                -- one per member removed
 * ["admins", <account>, ...]           -- the whole admin list after, when it changes
 * ["component", <id>, <sha256 of data> | "remove"]  -- any other GroupContext component
 * ["proposer", <account>]
 * ```
 *
 * Readable on its face, because a member approving it is approving those lines:
 * "make Bob an admin", not a hash of a commit. Bound to one epoch, because a
 * statement good at any epoch could be replayed -- a demotion signed once and
 * applied could be applied again after the member was promoted back. And the
 * whole change, not a summary of it: [authorises] holds a commit to *exactly*
 * these lines, so an extra Add folded in beside a signed promotion is refused.
 *
 * Signed by the room itself. A keyed room's id is the group's key walked to the
 * room's path, so the key that must have signed is the room's id and nothing has
 * to be looked up -- [GroupKeyStateEvent.isSignedByRoom].
 *
 * Kind 30370: past the last of the wallet kinds Dedun retired
 * (`RetiredKinds.GROUP_LIGHTNING`). Like every kind in the signing family it only ever travels
 * inside an encryption a relay cannot open.
 */
object GovernanceStatementEvent {
    val KIND: Kind = 30370

    const val EPOCH_TAG = "epoch"
    const val ADD_TAG = "add"
    const val REMOVE_TAG = "remove"
    const val ADMINS_TAG = "admins"
    const val COMPONENT_TAG = "component"
    const val PROPOSER_TAG = "proposer"
    const val REMOVED = "remove"

    fun isGovernanceStatementKind(kind: Kind): Boolean = kind == KIND

    /** The tags of a statement that [change] be made to [chatRoomId] at [epoch]. */
    fun assembleTags(chatRoomId: HexKey, epoch: Long, change: GovernanceChange, proposer: HexKey): Array<Array<String>> =
        arrayOf(DTag.assemble(chatRoomId), arrayOf(EPOCH_TAG, epoch.toString())) +
            change.adds.sortedBy { it.account }.map { arrayOf(ADD_TAG, it.account, it.keyPackageRef) } +
            change.removes.sorted().map { arrayOf(REMOVE_TAG, it) } +
            listOfNotNull(change.admins?.let { arrayOf(ADMINS_TAG, *it.sorted().toTypedArray()) }) +
            change.components.toSortedMap().map { (id, digest) -> arrayOf(COMPONENT_TAG, id.toString(), digest) } +
            arrayOf(arrayOf(PROPOSER_TAG, proposer))

    /** A statement as its tags say it, or null when they do not make one. */
    fun parse(event: Event): GovernanceStatement? = runCatching {
        if (event.kind != KIND) return null
        val tags = event.tags
        val room = tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val epoch = tags.single { it.size == 2 && it[0] == EPOCH_TAG }[1].toLong()
        val adminTags = tags.filter { it.isNotEmpty() && it[0] == ADMINS_TAG }
        if (adminTags.size > 1) return null
        GovernanceStatement(
            chatRoomId = room,
            epoch = epoch,
            proposer = tags.firstOrNull { it.size >= 2 && it[0] == PROPOSER_TAG }?.get(1),
            change = GovernanceChange(
                adds = tags.filter { it.isNotEmpty() && it[0] == ADD_TAG }.map { AddedMember(it[1], it[2]) }.toSet(),
                removes = tags.filter { it.isNotEmpty() && it[0] == REMOVE_TAG }.map { it[1] }.toSet(),
                admins = adminTags.singleOrNull()?.drop(1)?.toSet(),
                components = tags.filter { it.isNotEmpty() && it[0] == COMPONENT_TAG }.associate { it[1].toInt() to it[2] },
            ),
        )
    }.getOrNull()

    /**
     * Whether [event] is the room's own signature on [change] at [epoch]: its kind,
     * the room's signature on it, the room and epoch it names, and a change equal to
     * [change] -- nothing more and nothing less. Every input is off the wire, so
     * anything malformed is a no.
     */
    fun authorises(event: Event, chatRoomId: HexKey, epoch: Long, change: GovernanceChange): Boolean {
        val statement = parse(event) ?: return false
        return statement.chatRoomId.equals(chatRoomId, ignoreCase = true) &&
            statement.epoch == epoch &&
            statement.change == change &&
            GroupKeyStateEvent.isSignedByRoom(event, chatRoomId)
    }
}

/** A member a commit adds: their account, and the exact KeyPackage that adds them. */
data class AddedMember(val account: HexKey, val keyPackageRef: HexKey)

/** A signed statement, read. */
data class GovernanceStatement(
    val chatRoomId: HexKey,
    val epoch: Long,
    val proposer: HexKey?,
    val change: GovernanceChange,
)

/**
 * What a commit does to a room, in a statement's terms: who it adds and removes,
 * the admin list it leaves (null when it leaves it alone), and every other
 * component it changes, by id and the hash of the new value (or [GovernanceStatementEvent.REMOVED]).
 *
 * A member's own leave rides along without appearing here; it needs nobody's
 * signature (D6), so [of] leaves it out rather than refusing it.
 */
data class GovernanceChange(
    val adds: Set<AddedMember> = emptySet(),
    val removes: Set<HexKey> = emptySet(),
    val admins: Set<HexKey>? = null,
    val components: Map<Int, String> = emptyMap(),
) {
    companion object {
        /**
         * What [proposals] would do to [group], or null when they do something a
         * statement cannot describe -- another member's Update, a GroupContext
         * rewrite, a pre-shared key, a re-init -- which is therefore never
         * authorised. [proposals] is the commit's whole list, exempt ones included.
         */
        fun of(group: GroupView, proposals: List<PendingProposal>): GovernanceChange? {
            val adds = mutableSetOf<AddedMember>()
            val removes = mutableSetOf<HexKey>()
            var admins: Set<HexKey>? = null
            val components = mutableMapOf<Int, String>()
            for (pending in proposals) {
                when (val proposal = pending.proposal) {
                    is Proposal.SelfRemove -> Unit
                    is Proposal.Add -> {
                        val account = (proposal.keyPackage.leafNode.credential as? Credential.Basic)?.identity?.toHexKey() ?: return null
                        adds += AddedMember(account, proposal.keyPackage.reference().toHex())
                    }
                    is Proposal.Remove -> removes += group.memberIdentityHex(proposal.removedLeafIndex) ?: return null
                    is Proposal.AppDataUpdate -> {
                        val operation = proposal.operation
                        if (proposal.componentId == AdminPolicyV1.COMPONENT_ID) {
                            if (operation !is Proposal.AppDataUpdate.Operation.Update) return null
                            admins = AdminPolicyV1.decode(operation.data).adminHexKeys.toSet()
                        } else {
                            components[proposal.componentId] = when (operation) {
                                is Proposal.AppDataUpdate.Operation.Update -> sha256(operation.data).toHex()
                                Proposal.AppDataUpdate.Operation.Remove -> GovernanceStatementEvent.REMOVED
                            }
                        }
                    }
                    else -> return null
                }
            }
            return GovernanceChange(adds, removes, admins, components)
        }

        /**
         * Whether a commit needs nobody's signature (D6): one made only of members'
         * own SelfRemoves, or a committer's own key update with no proposals at all.
         * MIP-03 lets any member make exactly these, and neither changes who governs
         * the room or who is in it beyond the leaver.
         */
        fun isExempt(proposals: List<PendingProposal>): Boolean =
            proposals.all { it.proposal is Proposal.SelfRemove }
    }
}
