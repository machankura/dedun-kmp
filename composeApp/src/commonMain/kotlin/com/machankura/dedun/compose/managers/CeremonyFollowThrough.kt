package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.repository.ChatRepository

/**
 * Takes a finished ceremony the rest of the way to a working room, without the
 * coordinator pressing anything.
 *
 * A ceremony reaching COMPLETE gives the group a key and nothing else. Three steps
 * follow, in an order nothing can change (docs/subgroups.md, "The order"):
 *
 * 1. a subgroup's parent certifies the room the key derives -- proposed here, and
 *    signed by the parent's own quorum, which stays their decision;
 * 2. the group signs what that room will sign with, its key state;
 * 3. the room is created and the members welcomed into it;
 * 4. a room whose founding invite never went out has it finished
 *    (docs/robust-group-admins.md, Phase 4).
 *
 * None of the three proposals asks anybody anything: each is a request to the
 * group, and the coordinator agreed to make it when they opened the ceremony. So
 * they are taken here, the same way the rounds are taken by `ChillDkgRitualManager`
 * -- from stored rows, one step at a time, repeating nothing. See
 * docs/automatic-key-ceremony.md, Phase 6.
 *
 * **Only the coordinator's device does this on its own.** Every participant could,
 * and the ladder still offers the buttons to any of them, but `n` devices each
 * proposing the same key state is `n` sessions for the group to sign.
 *
 * **It never proposes again after a failure.** A signing session that failed did
 * so because members declined or could not sign, and asking again on its own would
 * ask them again on a loop. The ladder offers that retry to a person.
 *
 * Not run under the inbound path's lock: creating a room waits on relays for key
 * packages, and the inbound path is what delivers them.
 */
object CeremonyFollowThrough {

    private const val TAG = "CeremonyFollowThrough"
    private val logger = Logger.withTag(TAG)

    /** The most steps one pass takes for one ceremony before looking again. */
    private const val MAX_STEPS_PER_PASS = 4

    /** One device follows through one step at a time, whichever trigger asked. */
    private val mutex = Mutex()

    /** What one call to [takeNextStep] did. */
    sealed interface Step {
        /** Not this device's to do, or nothing left: not the coordinator, not finished, or the room exists with its founders. */
        data object NothingToDo : Step

        /** Waiting on signatures somebody else gives. */
        data object Waiting : Step

        /** A request went out: [what] the group or its parent is now being asked to sign. */
        data class Proposed(val what: String) : Step

        /** The room exists now. */
        data class Created(val chatRoomId: String) : Step

        /**
         * The room existed without its founders, whose invite never went out, and
         * now they have been invited; [notAdded] could not be.
         */
        data class Invited(val chatRoomId: String, val notAdded: List<HexKey>) : Step

        /** The room cannot be created until these members' devices publish a key package. */
        data class BlockedOn(val missing: List<HexKey>) : Step

        /** It cannot go on by itself; the ladder offers the step to a person. */
        data class Stuck(val reason: String) : Step
    }

    /**
     * What a ceremony's room is called: the name the coordinator gave a subgroup,
     * or the name of the group an admin room administers. The bare name, as the
     * room stores it; the braces are drawn (docs/group-name-braces.md).
     */
    fun roomName(session: DkgSession): String =
        session.subject ?: if (session.parentChatRoomId != null) "Subgroup" else "Group"

    /** The human half of the room's description. */
    fun roomPurpose(session: DkgSession): String =
        if (session.parentChatRoomId != null) {
            "A subgroup of the group that certified it."
        } else {
            "Admins of ${session.subject ?: "the group"}."
        }

    /**
     * Follows every finished ceremony this device coordinated, until cancelled.
     *
     * Looks again whenever something it waits on may have changed: a ceremony
     * finishing, a certificate or key state being signed, a key package arriving
     * for a member a room was blocked on. Intermediate changes are skipped -- each
     * pass reads everything again from the database.
     */
    suspend fun observe(database: DedunDatabase, chatRepository: ChatRepository, keyPair: KeyPair) {
        val userPublicKey = keyPair.pubKey.toHexKey()

        triggers(database, userPublicKey)
            .conflate()
            .collect { sessions ->
                sessions.forEach { session ->
                    try {
                        followThrough(database, chatRepository, session, keyPair)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.e("Following ceremony ${session.id} through failed", e)
                    }
                }
            }
    }

    private fun triggers(database: DedunDatabase, userPublicKey: HexKey): Flow<List<DkgSession>> = combine(
        database.dkgSessionDao().observeCompletedSessionsCoordinatedBy(userPublicKey),
        database.groupSignedEventDao().observeByKind(GroupKeyStateEvent.KIND),
        database.groupSignedEventDao().observeByKind(SubgroupBirthCertificateEvent.KIND),
        database.marmotKeyPackageDao().observeCount(),
    ) { sessions, _, _, _ -> sessions }

    /**
     * Takes steps for [session] until one has to wait on somebody else. Returns the
     * last step taken.
     */
    suspend fun followThrough(
        database: DedunDatabase,
        chatRepository: ChatRepository,
        session: DkgSession,
        keyPair: KeyPair,
    ): Step {
        var step: Step = Step.NothingToDo
        repeat(MAX_STEPS_PER_PASS) {
            step = takeNextStep(database, chatRepository, session, keyPair)
            if (step !is Step.Proposed) return step
        }
        return step
    }

    /**
     * Takes the one step [session] is ready for, if it is this device's to take.
     *
     * Each step reads whether its output already exists rather than remembering
     * that it ran, so a step interrupted halfway, or asked for twice, does no harm.
     */
    suspend fun takeNextStep(
        database: DedunDatabase,
        chatRepository: ChatRepository,
        session: DkgSession,
        keyPair: KeyPair,
    ): Step = mutex.withLock {
        val current = database.dkgSessionDao().getSessionById(session.id) ?: return@withLock Step.NothingToDo
        if (!current.isCoordinator()) return@withLock Step.NothingToDo

        val thresholdPublicKey = current.thresholdPublicKey ?: return@withLock Step.NothingToDo
        val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)

        val ceremonyRoom = database.chatRoomDao().findChatRoomById(current.chatRoomId)
            ?: return@withLock Step.Stuck("The room ceremony ${current.id} ran in is gone")

        // 4. The room exists: finish its founding invite if it never went out.
        // Only a room still at epoch 0 has anything to finish, so nobody who
        // left is ever invited back -- see `MarmotGroupCreation.uninvitedFounders`.
        if (database.chatRoomDao().findChatRoomById(roomId) != null) {
            return@withLock when (val invite = chatRepository.finishFoundingInvite(current, ceremonyRoom)) {
                MarmotGroupCreation.FoundingInvite.NothingToDo -> Step.NothingToDo
                is MarmotGroupCreation.FoundingInvite.Sent -> {
                    logger.i("Ceremony ${current.id}: invited ${invite.invited.size} founder(s) to $roomId")
                    Step.Invited(roomId, invite.notAdded)
                }
                is MarmotGroupCreation.FoundingInvite.BlockedOn -> Step.BlockedOn(invite.missing)
                is MarmotGroupCreation.FoundingInvite.Failed -> Step.Stuck(invite.reason)
            }
        }

        val participants = ChillDkgRitualManager.participantsOf(current, ceremonyRoom)
        val parent = current.parentChatRoomId

        // 1. A subgroup's parent certifies the room the key derives.
        if (parent != null && !isCertified(database, roomId, parent)) {
            val asked = latestSessionCarrying(database, parent, SubgroupBirthCertificateEvent.KIND) {
                SubgroupBirthCertificateEvent.parseThresholdPublicKey(it.tags)
                    .equals(thresholdPublicKey, ignoreCase = true)
            }

            return@withLock when {
                asked == null -> {
                    val parentRoom = database.chatRoomDao().findChatRoomById(parent)
                        ?: return@withLock Step.Stuck("No parent room $parent to ask for a certificate")

                    SubgroupManager.proposeBirthCertificate(
                        database = database,
                        parentRoom = parentRoom,
                        userPublicKey = current.userPublicKey,
                        key = current,
                        adminPublicKeys = participants.toList(),
                        name = current.subject ?: "Subgroup",
                    )
                    logger.i("Ceremony ${current.id}: asked $parent to certify $roomId")
                    Step.Proposed("birth certificate")
                }

                asked.stage == FrostSigningStage.FAILED ->
                    Step.Stuck("The parent group could not certify $roomId")

                else -> Step.Waiting
            }
        }

        // 2. The group signs what the room will sign with.
        if (!isKeyStateSigned(database, roomId)) {
            val asked = latestSessionCarrying(database, current.chatRoomId, GroupKeyStateEvent.KIND) {
                GroupKeyStateEvent.parseDkgSessionId(it.tags) == current.id
            }

            return@withLock when {
                asked == null -> {
                    if (parent != null) {
                        SubgroupManager.proposeSubgroupKeyState(
                            database = database,
                            ceremonyRoom = ceremonyRoom,
                            userPublicKey = current.userPublicKey,
                            key = current,
                            parentChatRoomId = parent,
                        )
                    } else {
                        GroupKeyStateManager.propose(
                            database = database,
                            localChatRoom = ceremonyRoom,
                            userPublicKey = current.userPublicKey,
                            key = current,
                        )
                    }
                    logger.i("Ceremony ${current.id}: asked the group to sign $roomId's key state")
                    Step.Proposed("key state")
                }

                asked.stage == FrostSigningStage.FAILED ->
                    Step.Stuck("The group could not agree $roomId's key state")

                else -> Step.Waiting
            }
        }

        // 3. The room, with everyone the ceremony was run with as its admins --
        // which `MarmotGroupCreation.createFor` reads off the ceremony itself, so
        // there is no list here to get wrong (docs/robust-group-admins.md, Phase 1).
        when (
            val outcome = chatRepository.createMarmotGroup(
                ceremony = current,
                ceremonyRoom = ceremonyRoom,
                keyPair = keyPair,
                // Verified by the time it is written: the key state this room was
                // gated on carries the certificate, and `stateFrom` refused it
                // otherwise.
                parentChatRoomId = parent,
            )
        ) {
            is MarmotGroupCreation.Outcome.Created -> {
                logger.i("Ceremony ${current.id}: created $roomId")
                Step.Created(outcome.room.chatRoom.id)
            }
            is MarmotGroupCreation.Outcome.Existing -> Step.NothingToDo
            is MarmotGroupCreation.Outcome.BlockedOn -> Step.BlockedOn(outcome.missing)
            is MarmotGroupCreation.Outcome.Failed -> Step.Stuck(outcome.reason)
        }
    }

    private suspend fun isCertified(database: DedunDatabase, roomId: String, parentChatRoomId: HexKey): Boolean =
        database.groupSignedEventDao()
            .getByKind(SubgroupBirthCertificateEvent.KIND)
            .any { SubgroupBirthCertificateEvent.certifies(it.toEvent(), roomId, parentChatRoomId) }

    private suspend fun isKeyStateSigned(database: DedunDatabase, roomId: String): Boolean =
        GroupKeyStateManager.stateAmong(
            database.groupSignedEventDao().getByKind(GroupKeyStateEvent.KIND),
            roomId
        ) != null

    /**
     * The newest signing session in [chatRoomId] carrying an event of [kind] that
     * is [about] what is being asked, or null if nobody has asked.
     */
    private suspend fun latestSessionCarrying(
        database: DedunDatabase,
        chatRoomId: String,
        kind: Kind,
        about: (Event) -> Boolean,
    ): FrostSigningSession? = database.frostSigningSessionDao()
        .getSessionsForChatRoom(chatRoomId)
        .sortedByDescending { it.createdAt }
        .firstOrNull { session ->
            database.frostSigningSessionDao().getItems(session.id).any { item ->
                Event.fromJsonOrNull(item.unsignedEventJson)?.let { it.kind == kind && about(it) } == true
            }
        }
}
