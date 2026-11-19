package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.marmot.groups.MlsGroupStateStore
import com.vitorpamplona.mls.codec.TlsReader
import com.vitorpamplona.mls.codec.TlsWriter
import com.vitorpamplona.mls.group.OwnSenderRatchet
import com.vitorpamplona.mls.group.RetainedEpochSecrets
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.MarmotRetainedEpochSecret
import com.machankura.dedun.compose.database.model.MarmotSenderRatchet
import com.machankura.dedun.compose.extensions.toHex

/**
 * quartz's group state store over the app's own tables.
 *
 * The library keys a group by its `nostr_group_id` -- the routing address -- and the
 * app keys a room by its id, so every call here resolves the address through
 * `MarmotRouting` first (a room with no row is its own address). The state itself is
 * `ChatRoom.mlsGroupState`, the same hex the app has always written there; the
 * retained epochs are `MarmotRetainedEpochSecret` rows, one per epoch, which until
 * this store were written by the outbound path and read back by nothing -- every
 * epoch the inbound path retained was a map in an `object`, gone at the next launch.
 * The sender ratchet is a `MarmotSenderRatchet` row, one per room, and is the one
 * thing here that is written on the send path instead of the full state.
 *
 * A save for a room that does not exist yet is dropped with a log rather than
 * creating one: the room row is what carries the state when a room is made, and
 * the manager takes the group over once the row is there.
 *
 * Encryption at rest is what it was: the library's interface says MUST, the app's
 * `TODO(security)` says the same, and moving the bytes behind an interface neither
 * fixes nor worsens it. It does make the eventual fix one class.
 *
 * See docs/marmot-v2.md, Phase 5.
 */
class RoomMlsGroupStateStore(
    private val database: DedunDatabase,
) : MlsGroupStateStore {
    private val logger = Logger.withTag("RoomMlsGroupStateStore")

    private suspend fun chatRoomIdFor(nostrGroupId: String): String =
        database.marmotRoutingDao().chatRoomIdFor(nostrGroupId)

    override suspend fun save(nostrGroupId: String, state: ByteArray) {
        val chatRoomId = chatRoomIdFor(nostrGroupId)
        if (!database.chatRoomDao().exists(chatRoomId)) {
            logger.w("No room for $nostrGroupId; its state is not saved")
            return
        }
        database.chatRoomDao().updateMlsGroupState(chatRoomId, state.toHex())
    }

    override suspend fun load(nostrGroupId: String): ByteArray? =
        database.chatRoomDao().getMlsGroupState(chatRoomIdFor(nostrGroupId))?.hexToByteArray()

    override suspend fun delete(nostrGroupId: String) {
        val chatRoomId = chatRoomIdFor(nostrGroupId)
        database.chatRoomDao().updateMlsGroupState(chatRoomId, null)
        database.marmotRetainedEpochSecretDao().deleteForChatRoomId(chatRoomId)
        // A position is only meaningful inside the secret tree that derived it, and
        // that tree is what just went. The record would be refused on its binder
        // anyway; going now means no restore has to reason about it.
        database.marmotSenderRatchetDao().deleteForChatRoomId(chatRoomId)
    }

    override suspend fun listGroups(): List<String> =
        database.chatRoomDao().getChatRoomIdsWithMlsState().map { chatRoomId ->
            database.marmotRoutingDao().activeRoutingIdFor(chatRoomId)
        }

    override suspend fun saveRetainedEpochs(nostrGroupId: String, retainedSecrets: List<ByteArray>) {
        val chatRoomId = chatRoomIdFor(nostrGroupId)
        val dao = database.marmotRetainedEpochSecretDao()
        // Replaced whole rather than merged: the manager's window is the truth and it
        // trims from the front, so a row it no longer lists has fallen out of it.
        dao.deleteForChatRoomId(chatRoomId)
        retainedSecrets.forEach { bytes ->
            val retained = RetainedEpochSecrets.decodeTls(TlsReader(bytes))
            dao.insert(
                MarmotRetainedEpochSecret(
                    chatRoomId = chatRoomId,
                    epoch = retained.epoch,
                    senderDataSecret = retained.senderDataSecret,
                    encryptionSecret = retained.encryptionSecret,
                    leafCount = retained.leafCount,
                    exporterSecret = retained.exporterSecret,
                )
            )
        }
    }

    override suspend fun loadRetainedEpochs(nostrGroupId: String): List<ByteArray> =
        database.marmotRetainedEpochSecretDao()
            .getMarmotRetainedEpochSecretForChatRoomId(chatRoomIdFor(nostrGroupId))
            .sortedBy { it.epoch }
            .map { row ->
                val writer = TlsWriter()
                RetainedEpochSecrets(
                    epoch = row.epoch,
                    senderDataSecret = row.senderDataSecret,
                    encryptionSecret = row.encryptionSecret,
                    leafCount = row.leafCount,
                    exporterSecret = row.exporterSecret,
                ).encodeTls(writer)
                writer.toByteArray()
            }

    /**
     * The sender ratchet position as its own row, which is the whole point: a send
     * advances this leaf's entry in the secret tree and nothing else, so the
     * alternative is re-encoding the ratchet tree, the group context and every epoch
     * secret per message, growing with the group.
     *
     * Returns false for a room with no row, which sends the manager back to a full
     * [save] -- the one place that can create the state, and it drops a stateless
     * room with a log for the same reason. Returning false for an unreadable record
     * does the same: the interface's fallback is correct-but-slower, never
     * correct-but-lossy, so anything this cannot store honestly is declined rather
     * than reported saved.
     *
     * Durability is Room's, which is what [save] has always had: the suspend returns
     * when the write is in the write-ahead log. The interface asks for the write to
     * be durable before it returns because the ciphertext it describes is about to be
     * published, and that is the guarantee this inherits -- no stronger.
     */
    override suspend fun saveSenderRatchet(nostrGroupId: String, state: ByteArray): Boolean {
        val chatRoomId = chatRoomIdFor(nostrGroupId)
        if (!database.chatRoomDao().exists(chatRoomId)) {
            logger.w("No room for $nostrGroupId; its sender ratchet is not saved")
            return false
        }
        val record = OwnSenderRatchet.decodeTlsOrNull(state)
        if (record == null) {
            logger.w("The sender ratchet record for $nostrGroupId did not decode; falling back to a full save")
            return false
        }
        database.marmotSenderRatchetDao().upsert(
            MarmotSenderRatchet(
                chatRoomId = chatRoomId,
                epoch = record.epoch,
                leafIndex = record.leafIndex,
                applicationGeneration = record.applicationGeneration,
                record = state,
            )
        )
        return true
    }

    /**
     * The bytes as they were written. Null for a room that has not sent since its
     * last full save, which is every room the moment this table arrives.
     *
     * The manager applies what comes back on top of the restored state, and the
     * library accepts it only for the epoch, leaf and secret tree it was taken from,
     * and then only to move a chain forward. So a stale row is inert rather than
     * harmful -- but see `MarmotEngine.groupFor`, which has to apply it before the
     * manager adopts the group, because adopting persists and would otherwise write
     * the restored state's older position over this one.
     */
    override suspend fun loadSenderRatchet(nostrGroupId: String): ByteArray? =
        database.marmotSenderRatchetDao().forChatRoomId(chatRoomIdFor(nostrGroupId))?.record
}
