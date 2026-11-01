package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mls.codec.TlsReader
import com.vitorpamplona.quartz.marmot.mls.codec.TlsWriter
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroupStateStore
import com.vitorpamplona.quartz.marmot.mls.group.RetainedEpochSecrets
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.MarmotRetainedEpochSecret
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
}
