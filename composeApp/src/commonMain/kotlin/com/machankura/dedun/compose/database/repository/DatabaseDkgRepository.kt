package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.managers.ChillDkgRitualManager
import com.machankura.dedun.compose.managers.GroupKeyStateManager
import com.machankura.dedun.compose.managers.SubgroupManager
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.repository.DkgRepository
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DatabaseDkgRepository(
    private val database: DedunDatabase,
    private val scope: CoroutineScope
): DkgRepository {
    private val logger = Logger.withTag(TAG)

    override fun observeSessionById(sessionId: String): Flow<DkgSession?> =
        database.dkgSessionDao().observeSessionById(sessionId)

    override fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<DkgSession>> =
        database.dkgSessionDao().observeSessionsForChatRoom(chatRoomId)

    override suspend fun roomAwaitingCreation(session: DkgSession): String? {
        if (session.stage != DkgRitualStage.COMPLETE) return null

        val roomId = session.thresholdPublicKey
            ?.let { runCatching { SharedKeyDerivation.marmotGroupId(it) }.getOrNull() }
            ?: return null

        return roomId.takeIf { database.chatRoomDao().findChatRoomById(it) == null }
    }

    override fun observeLatestSessionForChatRoom(
        chatRoomId: String,
        parentChatRoomId: String?,
    ): Flow<DkgSession?> =
        database.dkgSessionDao().observeLatestSessionFor(chatRoomId, parentChatRoomId)

    override fun observeMessages(sessionId: String): Flow<List<DkgParticipantMessage>> =
        database.dkgSessionDao().observeMessages(sessionId)

    override suspend fun getLatestSessionForChatRoom(chatRoomId: String): DkgSession? =
        database.dkgSessionDao().getLatestSessionForChatRoom(chatRoomId)

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
    ): DkgSession? = try {
        ChillDkgRitualManager.proposeRitual(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            nostrPrivateKey = nostrPrivateKey,
            threshold = threshold,
            parentChatRoomId = parentChatRoomId,
            participantPublicKeys = participantPublicKeys,
            subject = subject,
            walletPolicy = walletPolicy,
            guardianDelay = guardianDelay
        )
    } catch (e: Throwable) {
        logger.e("Error proposing DKG ritual for ${localChatRoom.chatRoom.id}", e)
        null
    }

    override suspend fun pendingApproval(session: DkgSession): DkgApprovalStep? =
        ChillDkgRitualManager.pendingApproval(database, session)

    override suspend fun proposeGroupKeyState(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        session: DkgSession
    ): FrostSigningSession? = try {
        GroupKeyStateManager.propose(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            key = session
        )
    } catch (e: Throwable) {
        // A room with no standing to propose is a bug rather than a condition,
        // and a ceremony with no key yet is a caller that got ahead of itself.
        // Neither is worth failing the room over: the group still has a working
        // chat, and the screen reports a proposal that did not open by leaving
        // the group with nothing signed and the button still there.
        logger.e("Error proposing the key state from ${localChatRoom.chatRoom.id}", e)
        null
    }

    override fun observeSigningSessions(
        chatRoomId: String
    ): Flow<List<LocalFrostSigningSession>> =
        database.frostSigningSessionDao().observeSessionsForChatRoom(chatRoomId)

    override fun observeSignedGroupKeyState(dkgSessionId: String): Flow<GroupKeyState?> =
        database.groupSignedEventDao()
            .observeByKind(GroupKeyStateEvent.KIND)
            .map { signedEvents ->
                // The room this ceremony's key derives, resolved per emission
                // rather than once: the ceremony has no key until it finishes,
                // and this flow is running before it does.
                //
                // By session id, not by room. A ceremony room holds more than one
                // ceremony the moment a subgroup's admins are the whole group, and
                // a lookup by room answered about whichever the room's rule picked
                // -- for a room holding only a subgroup's ceremony, asking for the
                // room's *own* found nothing at all, and a finished subgroup with
                // a signed key state could never be created.
                val subjectRoomId = database.dkgSessionDao()
                    .getSessionById(dkgSessionId)
                    ?.thresholdPublicKey
                    ?.let { runCatching { SharedKeyDerivation.marmotGroupId(it) }.getOrNull() }

                subjectRoomId?.let { GroupKeyStateManager.stateAmong(signedEvents, it) }
            }

    override suspend fun adoptGroupKeyState(chatRoomId: String): GroupKeyState? = try {
        GroupKeyStateManager.adopt(database = database, chatRoomId = chatRoomId)
    } catch (e: Throwable) {
        // The room is made and usable either way; what it loses is the shortcut
        // from room to ceremony, and `FrostSigningManager.completedKey` still
        // rederives its way to the same key.
        logger.e("Error adopting the signed key state for $chatRoomId", e)
        null
    }

    override suspend fun proposeBirthCertificate(
        parentChatRoomId: String,
        userPublicKey: HexKey,
        session: DkgSession,
        adminPublicKeys: List<HexKey>,
        name: String,
    ): FrostSigningSession? = try {
        val parentRoom = database.chatRoomDao().findChatRoomById(parentChatRoomId)
            ?: throw IllegalStateException("No parent room $parentChatRoomId to certify from")

        SubgroupManager.proposeBirthCertificate(
            database = database,
            parentRoom = parentRoom,
            userPublicKey = userPublicKey,
            key = session,
            adminPublicKeys = adminPublicKeys,
            name = name,
        )
    } catch (e: Throwable) {
        // A device with no share of the parent's key cannot open this session,
        // and a ceremony with no key yet is a caller that got ahead of itself.
        // Neither is worth failing anything over: nothing was published, and the
        // screen reports it by leaving the rung unfinished with the button there.
        logger.e("Error proposing a birth certificate in $parentChatRoomId", e)
        null
    }

    override fun observeBirthCertificate(
        dkgSessionId: String,
        parentChatRoomId: String,
    ): Flow<GroupSignedEvent?> =
        database.groupSignedEventDao()
            .observeByKind(SubgroupBirthCertificateEvent.KIND)
            .map { signedEvents ->
                // The room the ceremony's key derives, resolved per emission
                // rather than once: the ceremony has no key until it finishes,
                // and this flow is running before it does.
                val subgroupChatRoomId = database.dkgSessionDao()
                    .getSessionById(dkgSessionId)
                    ?.thresholdPublicKey
                    ?.let { runCatching { SharedKeyDerivation.marmotGroupId(it) }.getOrNull() }
                    ?: return@map null

                signedEvents
                    .filter {
                        SubgroupBirthCertificateEvent.certifies(
                            event = it.toEvent(),
                            subgroupChatRoomId = subgroupChatRoomId,
                            parentChatRoomId = parentChatRoomId,
                        )
                    }
                    .maxByOrNull { it.createdAt }
            }

    override suspend fun proposeSubgroupKeyState(
        ceremonyRoom: LocalChatRoom,
        userPublicKey: HexKey,
        session: DkgSession,
        parentChatRoomId: HexKey,
    ): FrostSigningSession? = try {
        SubgroupManager.proposeSubgroupKeyState(
            database = database,
            ceremonyRoom = ceremonyRoom,
            userPublicKey = userPublicKey,
            key = session,
            parentChatRoomId = parentChatRoomId,
        )
    } catch (e: Throwable) {
        logger.e("Error proposing the subgroup key state from ${ceremonyRoom.chatRoom.id}", e)
        null
    }

    override suspend fun abandon(localChatRoom: LocalChatRoom, sessionId: String): Boolean = try {
        ChillDkgRitualManager.abandon(
            database = database,
            localChatRoom = localChatRoom,
            sessionId = sessionId,
        )
    } catch (e: Throwable) {
        logger.e("Error abandoning DKG ritual $sessionId", e)
        false
    }

    override suspend fun approve(
        localChatRoom: LocalChatRoom,
        sessionId: String,
        step: DkgApprovalStep,
        nostrPrivateKey: ByteArray
    ) {
        try {
            ChillDkgRitualManager.approve(
                database = database,
                localChatRoom = localChatRoom,
                sessionId = sessionId,
                step = step,
                nostrPrivateKey = nostrPrivateKey
            )
        } catch (e: Throwable) {
            // The ritual fails itself and tells the group; swallowing here keeps a
            // protocol fault from taking the screen down with it.
            logger.e("Error approving $step for DKG ritual $sessionId", e)
        }
    }

    companion object {
        private const val TAG = "DatabaseDkgRepository"
    }
}
