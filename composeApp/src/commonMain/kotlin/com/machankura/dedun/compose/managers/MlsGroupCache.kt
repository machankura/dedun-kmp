package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.machankura.dedun.compose.extensions.toHex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a room's [MlsGroup] alive between messages instead of rebuilding it
 * from the stored state every time.
 *
 * ### The bug this exists for
 *
 * MLS is specified to tolerate out-of-order delivery within an epoch: a
 * receiver that gets generation N+1 before N derives and caches the key for N
 * so the older message can still be read when it turns up. Quartz's
 * `SecretTree` does exactly that, in a private `skippedKeys` map.
 *
 * `SecretTree.exportSenderStates()` does not include that map, so
 * `MlsGroup.saveState()` does not carry it. Rebuilding the group from stored
 * state therefore throws the skipped keys away, and a message for a generation
 * the ratchet has already passed fails
 * `require(generation >= state.applicationGeneration)` and is dropped. There is
 * no recovering it afterwards: the key is gone and the sender will not resend.
 *
 * Nostr relays offer no ordering whatsoever, so this is not an edge case. Two
 * events published in the same second race, and exactly one survives — which is
 * how a signing session's proposal was lost while the nonce sent immediately
 * behind it arrived fine.
 *
 * ### What this fixes, and what it does not
 *
 * Holding the instance means `skippedKeys` survives for as long as the process
 * does and nothing else writes the room's state. That covers the case that
 * actually bites — a burst of messages arriving in one sync — because they are
 * decrypted one after another against the same tree.
 *
 * It does not survive a restart, and it does not survive another writer, so
 * reordering across app launches still loses messages. The real fix is for
 * `exportSenderStates` to carry the skipped keys; see
 * `docs/mls-skipped-keys.md`.
 *
 * ### Staleness
 *
 * The group is only reused when the stored state is still exactly what this
 * cache last wrote. Anything else that saves a room's state — sending a message
 * advances the sender ratchet and saves, so does adding a member — changes the
 * hex, and the next read rebuilds rather than carrying on from a group that has
 * been overtaken. Losing the skipped keys there is the same behaviour as
 * before this existed, so the fallback is never worse than not caching.
 */
object MlsGroupCache {
    private val cache = LiveInstanceCache<MlsGroup> { it.saveState().encodeTls().toHex() }

    /**
     * Runs [block] against the room's live group, then stores whatever state it
     * left behind.
     *
     * [storedStateHex] is the room's state as the database currently has it, and
     * [build] turns it into a group. [save] is handed the state to persist.
     *
     * Returns null without calling [block] when the room has no usable group
     * state, which is the same thing a failed `toMlsGroup()` meant before.
     */
    suspend fun <T> withGroup(
        chatRoomId: String,
        storedStateHex: String?,
        build: () -> MlsGroup?,
        save: suspend (String) -> Unit,
        block: suspend (MlsGroup) -> T,
    ): T? = cache.withInstance(
        key = chatRoomId,
        storedState = storedStateHex,
        build = build,
        save = save,
        block = block
    )
}

/**
 * One live instance per key, reused only while the stored state is still the one
 * this cache last wrote.
 *
 * Split out from [MlsGroupCache] so the decision it makes can be tested without
 * standing up an MLS group. That decision is the whole safety argument: reuse
 * when nothing else has written, rebuild when something has, and never carry on
 * with an instance whose last use failed part-way through.
 */
internal class LiveInstanceCache<T : Any>(
    /** The persisted form of an instance, for spotting another writer. */
    private val stateOf: (T) -> String,
) {
    private val logger = Logger.withTag("LiveInstanceCache")

    private class Entry<T>(val instance: T, val state: String)

    private val entries = mutableMapOf<String, Entry<T>>()

    /**
     * Serialises use of one key's instance.
     *
     * The instance is mutable and [block] advances it, so two callers running at
     * once would corrupt it. One lock per key rather than one overall, so a busy
     * key cannot hold up a quiet one.
     *
     * Held across [block], which may touch the database. Safe here because a
     * caller only ever takes this lock while it is already running -- it never
     * waits on a resource the holder is itself waiting for.
     */
    private val locks = mutableMapOf<String, Mutex>()
    private val locksGuard = Mutex()

    private suspend fun lockFor(key: String): Mutex =
        locksGuard.withLock { locks.getOrPut(key) { Mutex() } }

    suspend fun <R> withInstance(
        key: String,
        storedState: String?,
        build: () -> T?,
        save: suspend (String) -> Unit,
        block: suspend (T) -> R,
    ): R? = lockFor(key).withLock {
        val cached = entries[key]

        val instance = if (cached != null && cached.state == storedState) {
            cached.instance
        } else {
            if (cached != null) {
                logger.d("$key was written elsewhere; rebuilding")
            }
            // Dropped before the block runs, so a build that fails does not leave
            // the old instance behind to be picked up by the next caller.
            entries.remove(key)
            build() ?: return@withLock null
        }

        // Deliberately not in a finally: an instance whose use threw part-way is
        // in an unknown state, and the next caller should rebuild from whatever
        // was last persisted rather than carry on with it.
        val result = block(instance)

        val state = stateOf(instance)
        save(state)
        entries[key] = Entry(instance = instance, state = state)

        result
    }

    /** How many instances are held. For tests. */
    internal fun size(): Int = entries.size
}
