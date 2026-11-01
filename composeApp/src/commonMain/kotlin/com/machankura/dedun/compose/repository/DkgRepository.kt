package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Reads, starts and approves ChillDKG rituals.
 *
 * Advancing one is the inbound path's job, with one exception: a ritual waiting on
 * this device's owner does not advance until [approve] is called, because it will
 * not publish on their behalf until they say so.
 */
interface DkgRepository {
    /**
     * The room's current ritual for one purpose — the group's own when
     * [parentChatRoomId] is null, or that parent's subgroup when it is not.
     * Abandoned attempts are superseded by the newest.
     *
     * A room routinely holds several: a parent's room hosts the ceremony of every
     * subgroup the group makes. See `DkgSessionDao.getLatestSessionFor`.
     */
    fun observeLatestSessionForChatRoom(
        chatRoomId: String,
        parentChatRoomId: String? = null,
    ): Flow<DkgSession?>

    fun observeMessages(sessionId: String): Flow<List<DkgParticipantMessage>>

    /**
     * One named ceremony, however many the room holds.
     *
     * What a transcript line opens: the line knows which ceremony it is about,
     * and the room does not.
     */
    fun observeSessionById(sessionId: String): Flow<DkgSession?>

    /**
     * Every ceremony a room holds, newest first.
     *
     * For the transcript's standing notice, which has to say what a room owes
     * this member without knowing in advance how many ceremonies are in it.
     */
    fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<DkgSession>>

    /**
     * The room a finished ceremony's key derives, if this device does not hold it
     * yet -- or null once it does, or while there is no key.
     *
     * What tells the coordinator they still have work. A ceremony reaching
     * COMPLETE is not the end of anything on its own: the group has a key and
     * somebody still has to get its state signed and make the room. Once that
     * room exists there is nothing left to do, and asking whether it exists is
     * how this knows without keeping a flag that could be wrong.
     */
    suspend fun roomAwaitingCreation(session: DkgSession): String?

    suspend fun getLatestSessionForChatRoom(chatRoomId: String): DkgSession?

    /**
     * Opens a ritual in [localChatRoom], making this device the coordinator.
     *
     * [parentChatRoomId] says what the ceremony is for when it is for a subgroup.
     * It rides on the proposal and is believed by nobody -- see
     * `DkgSession.parentChatRoomId`.
     *
     * [participantPublicKeys] and [subject] are what a subgroup needs and a
     * group's own ceremony does not: the room it runs in is the *parent's*, so
     * the room can say neither who the ceremony is with nor what the room it
     * makes is to be called. Both default to the room, which is the right answer
     * for a group opening its own.
     */
    suspend fun proposeRitual(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
        threshold: Int,
        parentChatRoomId: HexKey? = null,
        participantPublicKeys: Set<HexKey>? = null,
        subject: String? = null,
        walletPolicy: SubgroupWalletPolicy? = null,
        guardianDelay: Long? = null
    ): DkgSession?

    /** What the ritual is waiting on this device's owner for, if anything. */
    suspend fun pendingApproval(session: DkgSession): DkgApprovalStep?

    /** Agrees to one step, letting the ritual publish it and run on. */
    suspend fun approve(
        localChatRoom: LocalChatRoom,
        sessionId: String,
        step: DkgApprovalStep,
        nostrPrivateKey: ByteArray
    )

    /**
     * Asks the group to sign a statement of which ceremony's key its admin
     * room will sign with, before that room is created.
     *
     * Called once by the ceremony's coordinator, in the room the ceremony ran
     * in. What comes back is the signing session, not the state: the state
     * exists when a quorum has signed, which is the point of proposing it rather
     * than announcing it. Null if the ritual has produced no key yet, or if this
     * room has no standing to propose one -- both of which mean there is nothing
     * true to propose.
     */
    /**
     * Ends a ceremony this device is in, for everyone in it -- see
     * `ChillDkgRitualManager.abandon`. Returns whether it was ended.
     */
    suspend fun abandon(localChatRoom: LocalChatRoom, sessionId: String): Boolean

    suspend fun proposeGroupKeyState(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        session: DkgSession
    ): FrostSigningSession?

    /**
     * The signing sessions open in a room, newest first, with what each signs.
     *
     * Read by the ritual screen to follow the session the group's key state is
     * agreed in. The items travel with the session because "which session is
     * this" is answered by the kind of the event it is signing, not by the row --
     * which is what lets the same reading work in a parent's room, where a
     * subgroup's key state is one session among however many the room is running.
     */
    fun observeSigningSessions(chatRoomId: String): Flow<List<LocalFrostSigningSession>>

    /**
     * The key state the group has signed for the admin room this room's
     * ceremony will make, whether or not that room exists yet.
     *
     * The gate on creating it. Null means the group has not finished agreeing
     * what it will sign with, and a room created then would be one whose
     * founding fact was never settled.
     *
     * A flow over the signed events rather than a read, because the moment it
     * changes is the moment there is a room to make -- and the state it is
     * about does not exist as a row until somebody makes one.
     */
    /**
     * Whether the group has signed a key state for the room [dkgSessionId]'s key
     * derives.
     *
     * Named by ceremony rather than by room, because the room a ceremony runs in
     * can hold more than one and re-deriving "the room's ceremony" here would
     * answer about a different key than the screen is showing. That is what left
     * a finished subgroup with its key state signed, its certificate signed, and
     * no way to create the room -- the lookup found no ceremony at all.
     */
    fun observeSignedGroupKeyState(dkgSessionId: String): Flow<GroupKeyState?>

    /** Files the state the group signed for a room that now exists. */
    suspend fun adoptGroupKeyState(chatRoomId: String): GroupKeyState?

    /**
     * Asks the parent's quorum to certify the subgroup this ceremony produced.
     *
     * Signed by the parent's key and authored by the parent's own room id -- the
     * one thing a lineage can be checked against. The ceremony runs in that room
     * too now, so this is no longer a change of venue, only of whose quorum is
     * being asked. See `SubgroupManager.proposeBirthCertificate`.
     */
    suspend fun proposeBirthCertificate(
        parentChatRoomId: String,
        userPublicKey: HexKey,
        session: DkgSession,
        adminPublicKeys: List<HexKey>,
        name: String,
    ): FrostSigningSession?

    /**
     * Whether the parent has certified the room this ceremony's key derives.
     *
     * Answerable before that room exists, which is the point: it is what gates
     * the key state, which is itself signed before the room is created.
     */
    fun observeBirthCertificate(
        dkgSessionId: String,
        parentChatRoomId: String,
    ): Flow<GroupSignedEvent?>

    /**
     * Asks the subgroup's own quorum to sign its key state, carrying the
     * certificate.
     *
     * Refuses without one, because every device that receives the state runs the
     * same check and drops it -- see `GroupKeyStateManager.stateFrom`.
     */
    suspend fun proposeSubgroupKeyState(
        ceremonyRoom: LocalChatRoom,
        userPublicKey: HexKey,
        session: DkgSession,
        parentChatRoomId: HexKey,
    ): FrostSigningSession?

    companion object {
        val NO_OP_DKG_REPOSITORY: DkgRepository = object : DkgRepository {
            override fun observeLatestSessionForChatRoom(
                chatRoomId: String,
                parentChatRoomId: String?,
            ): Flow<DkgSession?> = flowOf(null)

            override fun observeMessages(sessionId: String): Flow<List<DkgParticipantMessage>> = flowOf(emptyList())

            override fun observeSessionById(sessionId: String): Flow<DkgSession?> = flowOf(null)

            override fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<DkgSession>> =
                flowOf(emptyList())

            override suspend fun roomAwaitingCreation(session: DkgSession): String? = null

            override suspend fun getLatestSessionForChatRoom(chatRoomId: String): DkgSession? = null

            override suspend fun proposeRitual(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                nostrPrivateKey: ByteArray,
                threshold: Int,
                parentChatRoomId: HexKey?,
                participantPublicKeys: Set<HexKey>?,
                subject: String?,
                walletPolicy: SubgroupWalletPolicy?,
                guardianDelay: Long?
            ): DkgSession? = null

            override suspend fun pendingApproval(session: DkgSession): DkgApprovalStep? = null

            override suspend fun approve(
                localChatRoom: LocalChatRoom,
                sessionId: String,
                step: DkgApprovalStep,
                nostrPrivateKey: ByteArray
            ) = Unit

            override suspend fun abandon(localChatRoom: LocalChatRoom, sessionId: String): Boolean = false

            override suspend fun proposeGroupKeyState(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                session: DkgSession
            ): FrostSigningSession? = null

            override fun observeSigningSessions(
                chatRoomId: String
            ): Flow<List<LocalFrostSigningSession>> = flowOf(emptyList())

            override fun observeSignedGroupKeyState(dkgSessionId: String): Flow<GroupKeyState?> =
                flowOf(null)

            override suspend fun adoptGroupKeyState(chatRoomId: String): GroupKeyState? = null

            override suspend fun proposeBirthCertificate(
                parentChatRoomId: String,
                userPublicKey: HexKey,
                session: DkgSession,
                adminPublicKeys: List<HexKey>,
                name: String,
            ): FrostSigningSession? = null

            override fun observeBirthCertificate(
                dkgSessionId: String,
                parentChatRoomId: String,
            ): Flow<GroupSignedEvent?> = flowOf(null)

            override suspend fun proposeSubgroupKeyState(
                ceremonyRoom: LocalChatRoom,
                userPublicKey: HexKey,
                session: DkgSession,
                parentChatRoomId: HexKey,
            ): FrostSigningSession? = null
        }
    }
}
