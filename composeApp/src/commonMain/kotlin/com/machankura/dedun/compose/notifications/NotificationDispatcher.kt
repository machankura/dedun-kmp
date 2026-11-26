package com.machankura.dedun.compose.notifications

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.machankura.dedun.compose.database.DedunDatabase

/**
 * Turns new chat lines into notifications, from the table and never from the wire.
 * docs/notifications-and-background-sync.md, D1-D3 and Phase 6.
 *
 * Whatever wrote the line -- the live subscription, a catch-up, the background pass, a ceremony
 * step -- this reads it the same way: rows above the identity's cursor, through the policy, grouped
 * per room and category, posted, and only then the cursor moved past them. A process killed between
 * the post and the move re-posts the same room's notification on the next run, which replaces it,
 * so nothing doubles.
 *
 * @param syncPending whether the identity still has catch-up queued; arming waits for it to stay
 *   false for [settleFor], so a first sync's history is never news (D3).
 */
class NotificationDispatcher(
    private val database: DedunDatabase,
    private val notifier: PlatformNotifier,
    private val strings: suspend () -> NotificationStrings,
    private val appVisible: StateFlow<Boolean>,
    private val visibleChatRoomId: StateFlow<String?>,
    private val syncPending: suspend (HexKey) -> Flow<Boolean>,
    private val clock: () -> Instant = { Clock.System.now() },
    private val settleFor: Duration = 30.seconds,
) {
    private val logger = Logger.withTag(TAG)
    private val dao get() = database.notificationDao()

    /** What is showing, by key, so a new line is merged into its room's notification. */
    private val showing = mutableMapOf<String, RoomNotification>()
    private val showingMutex = Mutex()

    /** Runs until cancelled: arms the identity once its first sync has settled, then dispatches. */
    suspend fun run(userPublicKey: HexKey): Unit = coroutineScope {
        awaitArmed(userPublicKey)
        launch { cancelWhatWasRead(userPublicKey) }
        while (true) {
            val cursor = dao.getLedger(userPublicKey)?.cursorMessageId ?: return@coroutineScope
            dao.observeAfter(userPublicKey, cursor).first { it.isNotEmpty() }
            dispatchPending(userPublicKey)
        }
    }

    /** Arms [userPublicKey] once its catch-up queue has stayed empty for [settleFor]. D3. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitArmed(userPublicKey: HexKey) {
        if (dao.getLedger(userPublicKey) != null) return
        syncPending(userPublicKey)
            .distinctUntilChanged()
            .transformLatest { pending ->
                if (!pending) {
                    delay(settleFor)
                    emit(Unit)
                }
            }
            .first()
        if (dao.arm(userPublicKey, clock())) logger.i { "Armed notifications for $userPublicKey" }
    }

    /**
     * Decides about every line above the cursor, posts what passes, moves the cursor past all of
     * them, and returns what was posted. Does nothing for an identity that is not armed.
     */
    suspend fun dispatchPending(userPublicKey: HexKey): List<RoomNotification> = dispatchMutex.withLock {
        dispatchPendingLocked(userPublicKey)
    }

    /** The run loop and a background pass both dispatch; one at a time, or a line posts twice. */
    private val dispatchMutex = Mutex()

    private suspend fun dispatchPendingLocked(userPublicKey: HexKey): List<RoomNotification> {
        val posted = mutableListOf<RoomNotification>()
        while (true) {
            val ledger = dao.getLedger(userPublicKey) ?: return posted
            val batch = dao.getAfter(userPublicKey, ledger.cursorMessageId)
            if (batch.isEmpty()) return posted

            val rooms = batch.map { it.chatRoomId }.distinct()
                .associateWith { database.chatRoomDao().findChatRoomById(it) }
            val now = clock()
            val text = strings()

            val passing = batch.mapNotNull { line ->
                val room = rooms[line.chatRoomId]
                when (val decision = NotificationPolicy.decide(
                    line = line,
                    room = room?.chatRoom,
                    settings = ledger.settings,
                    visibleChatRoomId = visibleChatRoomId.value,
                    appVisible = appVisible.value,
                    now = now,
                )) {
                    is NotificationDecision.Post -> Triple(line, room!!, decision.category)
                    is NotificationDecision.Drop -> {
                        logger.d { "Line ${line.id} not posted: ${decision.reason}" }
                        null
                    }
                }
            }

            passing.groupBy { (line, _, category) -> line.chatRoomId to category }
                .forEach { (_, group) ->
                    val (_, room, category) = group.first()
                    val unnamed = group.map { it.first.senderPublicKey }.distinct()
                        .filter { NotificationContent.senderName(room, it) == null }
                    val knownNames = if (unnamed.isEmpty()) emptyMap() else
                        database.profileDao().getProfileByPublicKeys(unnamed)
                            .associate { it.publicKey to it.humanReadableNameOrPubkey() }
                    val arrived = NotificationContent.build(
                        room = room,
                        category = category,
                        lines = group.map { it.first },
                        showMessageText = ledger.settings.showMessageText,
                        profileName = null,
                        strings = text,
                        knownNames = knownNames,
                    )
                    val merged = showingMutex.withLock {
                        NotificationContent.merge(showing[arrived.key], arrived).also { showing[it.key] = it }
                    }
                    notifier.post(merged)
                    posted += merged
                }

            dao.advanceCursor(userPublicKey, batch.maxOf { it.id })
        }
    }

    /** Takes a room's notifications away once the room has been read past them. D8. */
    private suspend fun cancelWhatWasRead(userPublicKey: HexKey) {
        database.chatRoomDao().observeChatRoomListByUserPublicKey(userPublicKey).collect { rooms ->
            val byId = rooms.associateBy { it.chatRoom.id }
            val read = showingMutex.withLock {
                showing.values.filter { notification ->
                    val room = byId[notification.chatRoomId]?.chatRoom
                    val newest = notification.lines.maxOfOrNull { it.messageId } ?: 0
                    room == null || (room.lastReadMessageId ?: 0) >= newest
                }.onEach { showing.remove(it.key) }
            }
            read.forEach { notifier.cancel(it.key) }
        }
    }

    companion object {
        private const val TAG = "NotificationDispatcher"
    }
}
