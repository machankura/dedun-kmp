package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.managers.ChillDkgRitualManager

sealed interface DkgRitualUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * The ceremony this screen was named. Never absent once loaded: the screen
         * shows a ceremony and does not offer to start one, so there is no state
         * in which it has none to show -- see docs/ceremony-creation.md.
         */
        val session: DkgSession,
        /**
         * Who has published each round's message, for the progress ladder.
         *
         * Members rather than counts, because the one thing this screen exists to
         * answer is which member the ceremony is waiting on — a DKG cannot finish
         * until every one of them has taken part, and "2 of 3" does not tell anyone
         * whose door to knock on.
         */
        val hostKeyParticipants: Set<HexKey> = emptySet(),
        val round1Participants: Set<HexKey> = emptySet(),
        val round2Participants: Set<HexKey> = emptySet(),
        /**
         * What the ritual is waiting on this device's owner for, if anything.
         *
         * Null covers two very different situations that look the same from here —
         * waiting on somebody else, and nothing left to wait for — because the
         * screen already distinguishes them from [session]'s stage. What it cannot
         * derive is this, which needs the stored messages as well as the row.
         */
        val pendingApproval: DkgApprovalStep? = null,
        /**
         * Members whose key package the coordinator could not find, and so who stop
         * the admin room being created at all.
         *
         * Names rather than keys, because the answer to this is to go and ask that
         * person to open the app. Empty when nothing is blocked, which is also the
         * state a retry starts from.
         */
        val adminGroupBlockedOn: List<String> = emptyList(),
        /**
         * The session in which the group is agreeing what its admin room will
         * sign with, once somebody has opened one.
         *
         * A ceremony's room holds exactly one of these and nothing else, because
         * a NIP-17 room signs nothing else -- see `GroupKeyStateManager.propose`.
         * Null before it is proposed, and it stays on the screen afterwards so
         * the group can see how far the signing has got.
         */
        val keyStateSession: FrostSigningSession? = null,
        /**
         * Whether the group has finished signing that statement.
         *
         * Read from the signed event rather than from the [GroupKeyState] table,
         * because the room the state is about does not exist yet -- being able
         * to say this before there is a room is the whole reason the state is
         * signed first. It is what gates creating the room.
         */
        val isKeyStateSigned: Boolean = false,
        /**
         * The group this ceremony is making a subgroup of, or null for an
         * ordinary one.
         *
         * A claim off the route and the proposal, not evidence. It decides which
         * ladder the screen draws and nothing else -- what makes a lineage real
         * is [isCertified] below, which is a signature by that group's quorum.
         */
        val parentChatRoomId: HexKey? = null,
        /** Whether the parent has certified the room this ceremony's key derives. */
        val isCertified: Boolean = false,
        /**
         * The session asking the parent to certify, while it is running.
         *
         * Runs in the *parent's* room rather than this one, so it is watched
         * separately from [keyStateSession] and cannot be read off this room's
         * sessions at all.
         */
        val certificateSession: FrostSigningSession? = null,
    ): DkgRitualUIState {
        val hostKeyCount: Int get() = hostKeyParticipants.size
        val round1Count: Int get() = round1Participants.size
        val round2Count: Int get() = round2Participants.size

        /**
         * Everyone the ceremony involves, as far as this device can tell.
         *
         * The ceremony's own participants, plus anyone who has actually taken
         * part. The union matters because the two can disagree: `n` is fixed from
         * the proposal's p-tags, and a participant who has published a host key is
         * in the ceremony whatever any local row says.
         *
         * The **ceremony's** set, not the room's, and the difference is the whole
         * ladder. A subgroup's ceremony runs in the parent's room, so the room's
         * roster would draw a row per parent member and report the ceremony
         * waiting on people it was never with. `participantsOf` falls back to the
         * room for a ceremony recorded before that set was stored, which is right
         * for those: they ran in a room whose members were exactly their
         * participants.
         *
         * Profiles still come off the room, since the room is where the rows with
         * names in them are -- for a subgroup that is the parent, which holds
         * every admin the ceremony can have.
         */
        val ritualMembers: List<LocalParticipant> get() {
            val roomMembers = localChatRoom.localParticipants
                .distinctBy { it.participant.participantPublicKey }
                .associateBy { it.participant.participantPublicKey }

            val participants = ChillDkgRitualManager.participantsOf(session, localChatRoom)

            return (participants + hostKeyParticipants + round1Participants + round2Participants)
                .map { publicKey ->
                    roomMembers[publicKey] ?: LocalParticipant(
                        Participant(
                            participantPublicKey = publicKey,
                            chatRoomId = localChatRoom.chatRoom.id,
                            relayHint = null
                        ),
                        null
                    )
                }
        }

        /**
         * Whether this member can end the ceremony for everyone: while it is still
         * running, and only if they are in it. See `ChillDkgRitualManager.abandon`.
         */
        val canAbandon: Boolean
            get() = session.stage != DkgRitualStage.COMPLETE &&
                session.stage != DkgRitualStage.FAILED &&
                session.userPublicKey in ChillDkgRitualManager.participantsOf(session, localChatRoom)

        /**
         * The `n` of the t-of-n, as the session has it, because that is the count
         * every device agreed to run the ritual on.
         */
        val participantCount: Int
            get() = session.participantCount
    }

    data class Error(
        val message: String
    ): DkgRitualUIState

    data object Loading: DkgRitualUIState
}
