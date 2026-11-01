package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.managers.FrostSigningManager

sealed interface FrostSigningUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,

        /** Null on the first emission, before the session has been collected. */
        val session: FrostSigningSession? = null,

        /**
         * The events the session signs, in the order its proposal fixed. Empty
         * on the first emission, and for a session whose proposal has not landed.
         */
        val items: List<FrostSigningItem> = emptyList(),

        /** Who has offered a nonce, so the screen can name who it is waiting on. */
        val offeredNonce: Set<HexKey> = emptySet(),

        /** Who has signed their part. */
        val signed: Set<HexKey> = emptySet(),

        /**
         * Who has refused. Named for the same reason the other two are: a
         * t-of-n session survives some number of refusals and not one more, so
         * which members those are is the difference between a session that is
         * one refusal from ending and one that is nowhere near.
         */
        val declined: Set<HexKey> = emptySet(),

        /**
         * Who holds a share of the key the session signs with -- the ceremony's
         * participants, read off the ceremony rather than the room. Empty until
         * the session has been collected, and so until [members] has anyone in it.
         */
        val shareHolders: Set<HexKey> = emptySet(),

        /**
         * The share holders the session is still waiting on, and so whose door
         * there is any point knocking on.
         *
         * Asked of the manager rather than worked out here, the way
         * [FrostSigningManager.isAwaitingApproval] is. It decides what a resend
         * does, and what counts as an answer changes with the stage -- a nonce
         * settles the question while nonces are being collected and not after --
         * so a second reading of it here is a second thing to keep in step.
         */
        val silent: Set<HexKey> = emptySet(),

        /**
         * For a session over a transaction, the spend as [com.machankura.dedun.compose.wallet.GroupSpend.check]
         * vouched for it -- every output, which are change, and the fee. Null for
         * an event session, and for a spend this device could not check, which
         * the screen refuses to let anybody sign.
         */
        val spend: GroupSpend.Checked? = null,
        /** The sweep a sweep session signs, as this device checked it; null for anything else. */
        val sweep: GroupSweep.Checked? = null,
        /**
         * The members whose payout address one of [spend]'s outputs pays, by the output's script
         * as hex: read off the members' own signed destinations on this device, so the name is
         * checked rather than the proposer's word. `docs/group-payout-destination.md`, Phase 7.
         */
        val payees: Map<String, String> = emptyMap(),
    ): FrostSigningUIState {
        /**
         * The members the session can be answered by, as rows to draw.
         *
         * The share holders and nobody else. The room's roster is the wrong
         * list twice over: a room can hold members added after its key was
         * made, who hold no share and cannot sign or refuse, and a subgroup's
         * ceremony room is the parent, which holds everybody the subgroup is
         * not. The list is whose door to knock on, and a door that cannot
         * answer does not belong on it.
         *
         * Profiles still come off the room, since that is where the rows with
         * names in them are, in the order the room keeps them. A share holder
         * the room no longer holds -- one who left after the ceremony -- still
         * has a share and a place in the count, so they get a placeholder row
         * rather than being dropped.
         */
        val members: List<LocalParticipant> get() {
            val roomMembers = localChatRoom.localParticipants
                .distinctBy { it.participant.participantPublicKey }
                .associateBy { it.participant.participantPublicKey }

            val inRoom = roomMembers.values.filter { it.participant.participantPublicKey in shareHolders }
            val elsewhere = (shareHolders - roomMembers.keys).sorted().map { publicKey ->
                LocalParticipant(
                    Participant(
                        participantPublicKey = publicKey,
                        chatRoomId = localChatRoom.chatRoom.id,
                        relayHint = null
                    ),
                    null
                )
            }

            return inRoom + elsewhere
        }

        /**
         * Whether this device may ask the quiet members to take another look.
         *
         * The coordinator's, and nobody else's: `acceptProposal` takes a
         * session's coordinator from the author of the proposal that opened it,
         * so a resend from anybody else would have a member who was missing the
         * session believe the resender coordinates -- and then drop the real
         * coordinator's signer set. See `FrostSigningManager.resend`.
         *
         * An abandoned session is not resent either. Its nonce seeds have been
         * published against an aggregate already, so the retry is a fresh
         * proposal, which is what the status line says.
         *
         * The cooldown is deliberately not read here. A control that vanishes
         * after a tap reads as one that failed, so the section stays and the
         * button inside it is what goes quiet -- see [resendAvailableAt].
         */
        val canResend: Boolean get() {
            val session = session ?: return false

            return session.isCoordinator() &&
                session.stage != FrostSigningStage.FAILED &&
                items.isNotEmpty() &&
                silent.isNotEmpty()
        }

        /** When the next resend may go out, or null when one may go out now. */
        val resendAvailableAt: Instant? get() = session?.resentAt
            ?.plus(FrostSigningManager.RESEND_COOLDOWN)
            ?.takeIf { it > Clock.System.now() }
    }

    data class Error(
        val message: String
    ): FrostSigningUIState

    data object Loading: FrostSigningUIState
}
