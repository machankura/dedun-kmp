package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import com.machankura.dedun.compose.AppLifecycle
import com.machankura.dedun.compose.database.dao.NotificationDao
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.notifications.NotificationDispatcher

/**
 * One bounded sync while the app is off screen: what returning to the foreground does, on a
 * timer, with a deadline. docs/notifications-and-background-sync.md, D5, D6, D11 and Phase 9.
 *
 * It runs the app's own engines rather than a copy of them. If no screen has set an identity --
 * a worker's process, a background task launched cold -- it activates the default profile in the
 * runtime, which starts the same pumps, live subscriptions, notary and dispatcher a launch would;
 * it then holds the connections open, which makes `LiveSubscriptionManager` reconnect, open its
 * subscriptions (whose `limit` is the quick catch-up) and queue the negentropy reconciliation (the
 * full one); and it waits until the sync queue and the outbound queues have both been quiet for a
 * while, or the deadline comes. Then it lets go, and the engines it started stop with the identity.
 *
 * So everything a pass does is something the app already does while open, and the Welcome that
 * makes a new room widens the group filter by the same loop the live tier already closes. What the
 * pass adds is only the bounds, and the record in the ledger.
 */
class BackgroundSyncPass(
    private val activeIdentity: MutableStateFlow<Identity?>,
    private val loadDefaultIdentity: suspend () -> Identity?,
    /** Touches the runtime's lazy engines, so they are running for the identity once it is set. */
    private val startEngines: () -> Unit,
    private val syncPending: suspend (HexKey) -> Flow<Boolean>,
    /**
     * How many things this identity has queued to send. Read for change, not for emptiness: a row
     * that can never be sent would otherwise hold every pass to its deadline.
     */
    private val outboundQueued: suspend (HexKey) -> Flow<Int>,
    private val dispatcher: NotificationDispatcher,
    private val ledger: NotificationDao,
    private val isVisible: StateFlow<Boolean> = AppLifecycle.isVisible,
    private val keepConnected: (String) -> Unit = AppLifecycle::keepConnected,
    private val release: (String) -> Unit = AppLifecycle::release,
    private val clock: () -> Instant = { Clock.System.now() },
    /** How long both queues must stay empty before the pass calls the network quiet. */
    private val quietFor: Duration = 5.seconds,
) {
    private val logger = Logger.withTag(TAG)
    private val mutex = Mutex()

    enum class Kind(val wire: String) {
        /** For a short budget (iOS app refresh): whatever the subscriptions bring before the deadline. */
        Quick("quick"),

        /** For a longer one (WorkManager, iOS processing): until the reconciliation drains too. */
        Full("full"),
    }

    enum class Outcome(val wire: String) {
        Completed("completed"),
        Deadline("deadline"),
        /** The app is on screen; its own subscriptions already cover this. */
        Visible("visible"),
        NoIdentity("noIdentity"),
        /** Another pass is running in this process. */
        AlreadyRunning("alreadyRunning"),
        Failed("failed"),
    }

    suspend fun run(kind: Kind, deadline: Duration): Outcome {
        if (!mutex.tryLock()) return Outcome.AlreadyRunning
        try {
            if (isVisible.value) return Outcome.Visible

            val running = activeIdentity.value?.takeIf { it.nostrPrivateKey != null }
            val identity = running ?: loadDefaultIdentity() ?: return Outcome.NoIdentity
            val publicKey = identity.nostrPublicKey
            val activatedHere = running == null

            logger.i { "Background ${kind.wire} pass for $publicKey, deadline $deadline" }

            val outcome = try {
                startEngines()
                if (activatedHere) activeIdentity.value = identity
                keepConnected(AppLifecycle.KEEP_BACKGROUND_PASS)

                val settled = withTimeoutOrNull(deadline) {
                    awaitQuiet(publicKey, minimum = if (kind == Kind.Quick) MINIMUM_QUICK else MINIMUM_FULL)
                }
                // Whatever arrived is filed already; this only makes sure it was decided about before
                // the platform suspends us.
                dispatcher.dispatchPending(publicKey)
                if (settled != null) Outcome.Completed else Outcome.Deadline
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                logger.e("Background pass failed", failure)
                Outcome.Failed
            } finally {
                release(AppLifecycle.KEEP_BACKGROUND_PASS)
                // Only what this pass started: a screen that came up meanwhile, or set the identity
                // before the pass began, keeps it.
                if (activatedHere && !isVisible.value && activeIdentity.value == identity) activeIdentity.value = null
            }

            runCatching { ledger.recordBackgroundSync(publicKey, clock(), outcome.wire, kind.wire) }
            logger.i { "Background ${kind.wire} pass for $publicKey: ${outcome.wire}" }
            return outcome
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Waits at least [minimum] -- long enough for subscriptions to reach EOSE, which no queue
     * records -- and until the sync queue is empty and the outbound queue has not moved, both for
     * [quietFor]. A queue that is draining moves; one that is stuck does not, and is no reason to
     * stay awake.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitQuiet(publicKey: HexKey, minimum: Duration) {
        delay(minimum)
        combine(syncPending(publicKey), outboundQueued(publicKey)) { sync, outbound -> sync to outbound }
            .distinctUntilChanged()
            .transformLatest { (sync, _) ->
                if (!sync) {
                    delay(quietFor)
                    emit(Unit)
                }
            }
            .first()
    }

    companion object {
        private const val TAG = "BackgroundSyncPass"

        private val MINIMUM_QUICK = 8.seconds
        private val MINIMUM_FULL = 15.seconds
    }
}
