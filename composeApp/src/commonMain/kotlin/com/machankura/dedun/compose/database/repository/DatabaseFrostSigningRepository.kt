package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.psbt.Psbt
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSignerMessage
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.FrostSignerTally
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.repository.FrostSigningRepository

class DatabaseFrostSigningRepository(
    private val database: DedunDatabase,
    private val scope: CoroutineScope
): FrostSigningRepository {
    private val logger = Logger.withTag(TAG)

    override fun observeSessionById(sessionId: String): Flow<FrostSigningSession?> =
        database.frostSigningSessionDao().observeSessionById(sessionId)

    override fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<LocalFrostSigningSession>> =
        database.frostSigningSessionDao().observeSessionsForChatRoom(chatRoomId)

    override fun observeSignerTalliesForChatRoom(
        chatRoomId: String
    ): Flow<List<FrostSignerTally>> =
        database.frostSigningSessionDao().observeSignerTalliesForChatRoom(
            chatRoomId = chatRoomId,
            // The two kinds a proposal's progress is counted in. Asked for by
            // name so the query carries no opinion about which kinds exist.
            kinds = listOf(FrostSigningEvents.NONCE, FrostSigningEvents.PARTIAL_SIGNATURE),
            hostKeyKind = DkgRitualEvents.HOST_KEY,
        )

    override fun observeMessages(sessionId: String): Flow<List<FrostSignerMessage>> =
        database.frostSigningSessionDao().observeMessages(sessionId)

    override fun observeShareHolders(dkgSessionId: String): Flow<Set<HexKey>> =
        database.dkgSessionDao().observeMessages(dkgSessionId).map { messages ->
            messages
                .filter { it.kind == DkgRitualEvents.HOST_KEY }
                .map { it.participantPublicKey }
                .toSet()
        }

    override fun observeItems(sessionId: String): Flow<List<FrostSigningItem>> =
        database.frostSigningSessionDao().observeItems(sessionId)

    override suspend fun getItems(sessionId: String): List<FrostSigningItem> =
        database.frostSigningSessionDao().getItems(sessionId)

    override suspend fun getSessionById(sessionId: String): FrostSigningSession? =
        database.frostSigningSessionDao().getSessionById(sessionId)

    override suspend fun canSign(chatRoomId: String): Boolean =
        FrostSigningManager.canSign(database, chatRoomId)

    override suspend fun proposeSigning(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        kind: Kind,
        tags: Array<Array<String>>,
        content: String
    ): FrostSigningSession? = proposing(localChatRoom) {
        FrostSigningManager.proposeSigning(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            kind = kind,
            tags = tags,
            content = content
        )
    }

    override suspend fun proposeSigningBatch(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        events: List<EventTemplate<*>>
    ): FrostSigningSession? = proposing(localChatRoom) {
        FrostSigningManager.proposeSigningBatch(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            events = events
        )
    }

    override suspend fun proposeSigningBatch(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        lead: EventTemplate<*>,
        dependents: (lead: Event) -> List<EventTemplate<*>>
    ): FrostSigningSession? = proposing(localChatRoom) {
        FrostSigningManager.proposeSigningBatch(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            lead = lead,
            dependents = dependents
        )
    }

    /**
     * Proposing throws when the group has no key, when this device was not in the
     * ceremony, or when a batch is empty or over the cap. All four are states the
     * UI is supposed to have checked for, so they become a null the caller
     * reports rather than a crash.
     */
    private inline fun proposing(
        localChatRoom: LocalChatRoom,
        propose: () -> FrostSigningSession
    ): FrostSigningSession? = try {
        propose()
    } catch (e: Throwable) {
        logger.e("Error proposing a signature in ${localChatRoom.chatRoom.id}", e)
        null
    }

    override suspend fun proposeSpend(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        psbt: Psbt,
        chain: Chain,
    ): FrostSigningSession? = proposing(localChatRoom) {
        FrostSigningManager.proposeSpend(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            psbt = psbt,
            chain = chain,
        )
    }

    override suspend fun proposeGuardianSpend(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        psbt: Psbt,
        descendantChatRoomId: String,
        chain: Chain,
    ): FrostSigningSession? = proposing(localChatRoom) {
        FrostSigningManager.proposeGuardianSpend(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            psbt = psbt,
            descendantChatRoomId = descendantChatRoomId,
            chain = chain,
        )
    }

    override suspend fun checkedSpend(sessionId: String): GroupSpend.Checked? = try {
        FrostSigningManager.checkedSpend(database, sessionId)
    } catch (e: Throwable) {
        logger.e("Error checking the spend of $sessionId", e)
        null
    }

    override suspend fun checkedSweep(sessionId: String): GroupSweep.Checked? = try {
        FrostSigningManager.checkedSweep(database, sessionId)
    } catch (e: Throwable) {
        logger.e("Error checking the sweep of $sessionId", e)
        null
    }

    override suspend fun reservedOutPoints(chatRoomId: String): Set<OutPoint> = try {
        FrostSigningManager.reservedOutPoints(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading the coins reserved in $chatRoomId", e)
        emptySet()
    }

    override suspend fun reservedChangeIndices(chatRoomId: String, account: GroupWalletDerivation.Account): Set<Long> = try {
        FrostSigningManager.reservedChangeIndices(database, chatRoomId, account)
    } catch (e: Throwable) {
        logger.e("Error reading the change addresses reserved in $chatRoomId", e)
        emptySet()
    }

    override suspend fun reservedFundingIndices(chatRoomId: String, account: GroupWalletDerivation.Account): Set<Long> = try {
        FrostSigningManager.reservedFundingIndices(database, chatRoomId, account)
    } catch (e: Throwable) {
        logger.e("Error reading the funding addresses reserved in $chatRoomId", e)
        emptySet()
    }

    override fun observeGroupTransactions(chatRoomId: String): Flow<List<GroupTransaction>> =
        database.groupTransactionDao().observeByChatRoomId(chatRoomId)

    override suspend fun resend(localChatRoom: LocalChatRoom, sessionId: String): Boolean = try {
        FrostSigningManager.resend(
            database = database,
            localChatRoom = localChatRoom,
            sessionId = sessionId
        )
    } catch (e: Throwable) {
        // False rather than rethrown, for the reason `approve` swallows: a
        // resend is a repair, and a repair that takes the screen down with it is
        // worse than one that did not happen.
        logger.e("Error resending signing session $sessionId", e)
        false
    }

    override suspend fun resendAll(
        localChatRoom: LocalChatRoom,
        sessionIds: List<String>
    ): Int = try {
        FrostSigningManager.resendAll(
            database = database,
            localChatRoom = localChatRoom,
            sessionIds = sessionIds
        )
    } catch (e: Throwable) {
        // Zero rather than rethrown, as a single resend swallows and for the
        // same reason. Whatever went out before the throw is already out; what
        // this loses is the count, and a repair that takes the screen down with
        // it is worse than one that undercounted.
        logger.e("Error asking again about ${sessionIds.size} signing session(s)", e)
        0
    }

    override suspend fun membersOwing(session: FrostSigningSession): Set<HexKey> = try {
        FrostSigningManager.membersOwing(database = database, session = session)
    } catch (e: Throwable) {
        // Empty rather than rethrown: this is read on every emission of the
        // signing screen, and a session whose ceremony cannot be read should
        // offer no knock rather than take the screen down.
        logger.e("Error reading who signing session ${session.id} is waiting on", e)
        emptySet()
    }

    override suspend fun resumeAnswered(localChatRoom: LocalChatRoom) {
        try {
            FrostSigningManager.resumeAnswered(database = database, localChatRoom = localChatRoom)
        } catch (e: Throwable) {
            // Each session already settles its own failure inside `advance`, so
            // anything reaching here is the sweep itself, and a repair that
            // takes the room down with it is worse than one that did not happen.
            logger.e("Error resuming signing sessions in ${localChatRoom.chatRoom.id}", e)
        }
    }

    override suspend fun approve(localChatRoom: LocalChatRoom, sessionId: String) {
        try {
            FrostSigningManager.approve(
                database = database,
                localChatRoom = localChatRoom,
                sessionId = sessionId
            )
        } catch (e: Throwable) {
            // The session fails itself and tells the group; swallowing here keeps a
            // protocol fault from taking the screen down with it.
            logger.e("Error approving signing session $sessionId", e)
        }
    }

    override suspend fun decline(localChatRoom: LocalChatRoom, sessionId: String) {
        try {
            FrostSigningManager.decline(
                database = database,
                localChatRoom = localChatRoom,
                sessionId = sessionId
            )
        } catch (e: Throwable) {
            logger.e("Error declining signing session $sessionId", e)
        }
    }

    override fun unsignedEvents(items: List<FrostSigningItem>): List<Event> =
        items.map { Event.fromJson(it.unsignedEventJson) }

    override fun signedEvents(items: List<FrostSigningItem>): List<Event> =
        FrostSigningManager.signedEvents(items)

    companion object {
        private const val TAG = "DatabaseFrostSigningRepository"
    }
}
