package com.machankura.dedun.compose.managers

import com.vitorpamplona.marmot.groups.MarmotGroupPolicy
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.marmot.mip03GroupMessages.GroupEventEncryption
import com.vitorpamplona.mls.codec.TlsReader
import com.vitorpamplona.mls.framing.ContentType
import com.vitorpamplona.mls.framing.MlsMessage
import com.vitorpamplona.mls.framing.PrivateMessage
import com.vitorpamplona.mls.framing.PublicMessage
import com.vitorpamplona.mls.framing.WireFormat
import com.vitorpamplona.mls.group.GroupView
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.group.MlsGroupPolicy
import com.vitorpamplona.mls.group.PendingProposal
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.extensions.exporterSecret
import com.machankura.dedun.compose.nostr.frost.GovernanceChange
import com.machankura.dedun.compose.nostr.frost.GovernanceEvents
import com.machankura.dedun.compose.nostr.frost.GovernanceStatementEvent
import com.machankura.dedun.compose.nostr.frost.OwnCommitEvent

/**
 * The door every commit into a governed room comes through. docs/member-admin-promotion.md,
 * R2, the headline and Phase 4a.
 *
 * A room is governed when its state says so ([GovernanceMarker]). In one, a commit
 * sent bare is dropped before the engine sees it ([isBareCommit]), and a commit
 * arrives only inside an envelope ([admit]): the group's signature on a statement of
 * what it does, or -- for the commits that need nobody's (D6) -- its committer's own.
 *
 * Every verdict here is a function of the bytes and the group's state, nothing a
 * device happens to have seen, so every device reaches the same one. That is the
 * property the rule cannot do without: a device that applied a commit another
 * refused would be in a different group from then on.
 */
object GovernanceGate {

    /** The floor a governed room's state sets, or null when the room is not governed. */
    fun minimumAdmins(group: MlsGroup): Int? =
        if (AdminGovernance.isKeyedRoom(group)) GovernanceMarker.minimumAdmins(group.roomDescription()) else null

    fun isGoverned(group: MlsGroup): Boolean = minimumAdmins(group) != null

    /**
     * Whether [event] is a commit sent bare, read without processing it: the outer
     * layer peeled with any of [outerKeys] -- the current epoch's, then the retained
     * ones, because convergence can adopt a commit for an epoch already passed -- and
     * the MLS content type read from the header. An event no key opens is not
     * judged here; the engine cannot apply it either.
     */
    fun isBareCommit(event: GroupEvent, outerKeys: List<ByteArray>): Boolean {
        val mlsBytes = outerKeys.firstNotNullOfOrNull { key ->
            runCatching { GroupEventEncryption.decrypt(event.encryptedContent(), key) }.getOrNull()
        } ?: return false
        return runCatching {
            val message = MlsMessage.decodeTls(TlsReader(mlsBytes))
            when (message.wireFormat) {
                WireFormat.PUBLIC_MESSAGE -> PublicMessage.decodeTls(TlsReader(message.payload)).contentType == ContentType.COMMIT
                WireFormat.PRIVATE_MESSAGE -> PrivateMessage.decodeTls(TlsReader(message.payload)).contentType == ContentType.COMMIT
                else -> false
            }
        }.getOrDefault(false)
    }

    /** What an envelope's commit turned out to be, run on a copy of the group. */
    class Inspection(
        val before: GroupView,
        val proposals: List<PendingProposal>,
        val committer: HexKey?,
        val after: MlsGroup,
    )

    /**
     * Runs [inner]'s commit on a throwaway copy of [group] and says what it did, or
     * null when the copy refuses it. The copy is given a policy that delegates to
     * Marmot's and records what it is shown, which is the commit's real list of
     * proposals and its committer. Only at the group's current epoch: an envelope
     * is good there or nowhere (D5).
     */
    fun inspect(group: MlsGroup, inner: GroupEvent): Inspection? = runCatching {
        val mlsBytes = GroupEventEncryption.decrypt(inner.encryptedContent(), group.exporterSecret())
        val recorder = RecordingPolicy()
        val copy = MlsGroup.restore(group.saveState(), recorder)
        copy.processFramedCommit(mlsBytes)
        val seen = recorder.seen ?: return null
        Inspection(seen.first, seen.second, seen.first.memberIdentityHex(seen.third), copy)
    }.getOrNull()

    /** Why an envelope was refused, in words for the room. */
    class Refused(val reason: String)

    /**
     * The kind:445 [envelope] carries, when [group] -- the room [chatRoomId] -- may
     * apply it; otherwise why not.
     *
     * A statement (30370) has to be the room's own signature, at the group's
     * current epoch, on exactly what the commit does, and the commit has to leave at
     * least the room's floor of admins (R3). An own commit (30371) has to be one of
     * D6's two kinds, sent by the account whose leaf committed it.
     */
    fun admit(group: MlsGroup, chatRoomId: HexKey, envelope: Event): Result<GroupEvent> =
        check(group, chatRoomId, envelope, requireSignature = true)

    /**
     * [admit]'s checks, with the room's signature on a statement optional: a member's
     * device runs them on a statement still being asked for, before it signs
     * (`GovernanceManager.refusalOf`). Everything else is the same, so nobody signs
     * what the door would then refuse.
     */
    fun check(group: MlsGroup, chatRoomId: HexKey, envelope: Event, requireSignature: Boolean): Result<GroupEvent> {
        fun refuse(reason: String) = Result.failure<GroupEvent>(IllegalStateException(reason))

        val inner = GovernanceEvents.innerCommitOf(envelope) ?: return refuse("it carries no commit")
        if (GovernanceEvents.roomOf(envelope)?.equals(chatRoomId, ignoreCase = true) != true) return refuse("it is for another group")
        if (GovernanceEvents.epochOf(envelope) != group.epoch) return refuse("it was signed for another moment in the group's history")
        val inspection = inspect(group, inner) ?: return refuse("its change is not one this group can make")

        when (envelope.kind) {
            GovernanceStatementEvent.KIND -> {
                val change = GovernanceChange.of(inspection.before, inspection.proposals)
                    ?: return refuse("its change cannot be described, so nobody could have approved it")
                val statement = GovernanceStatementEvent.parse(envelope) ?: return refuse("its statement cannot be read")
                if (statement.change != change) return refuse("it does not say what its commit does")
                if (requireSignature && !GovernanceStatementEvent.authorises(envelope, chatRoomId, group.epoch, change)) {
                    return refuse("the group's signature is not on this change")
                }
                val floor = minimumAdmins(group) ?: 1
                val admins = inspection.after.currentAdminIdentities() intersect inspection.after.currentMemberIdentities()
                if (admins.size < floor) return refuse("it would leave fewer than $floor admins")
            }
            OwnCommitEvent.KIND -> {
                if (!GovernanceChange.isExempt(inspection.proposals)) return refuse("only the group can sign a change like this one")
                // Its author is its sender, which MLS has already authenticated: the
                // inbound path holds every application message to its sender's leaf
                // (MIP-03). So the one thing left to ask is that the sender made it.
                if (!envelope.pubKey.equals(inspection.committer, ignoreCase = true)) {
                    return refuse("it was not sent by the member who made it")
                }
            }
            else -> return refuse("it is not a commit envelope")
        }
        return Result.success(inner)
    }

    /**
     * Marmot's policy, recording the first commit it is asked about. The copy is
     * thrown away; this is how the app is told what a commit does without the
     * library being changed to say.
     */
    internal class RecordingPolicy : MlsGroupPolicy by MarmotGroupPolicy {
        var seen: Triple<GroupView, List<PendingProposal>, Int>? = null

        override fun authorizeCommit(group: GroupView, proposals: List<PendingProposal>, committerLeafIndex: Int) {
            MarmotGroupPolicy.authorizeCommit(group, proposals, committerLeafIndex)
            if (seen == null) seen = Triple(group, proposals, committerLeafIndex)
        }
    }
}
