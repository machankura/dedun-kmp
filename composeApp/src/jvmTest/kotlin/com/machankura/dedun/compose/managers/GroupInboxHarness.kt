package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.inbox.InboxKeyRitualEvents

/**
 * One keyed group, a device per member, for the group inbox's tests (docs/group-inbox.md).
 *
 * Every member has a real nostr key, because the inbox ceremony derives its host keys from the
 * nsec and the inbox opens NIP-44 with real secrets. The group's signing key stands in for a
 * finished ceremony (a trusted dealer, as `SignedGroupKeyStateTest` has it), and the room is a
 * Marmot one by its column -- nothing here runs MLS, so what a device sends is read off its
 * inner events and handed to the others by [pump], stored first, as the inbound path would.
 *
 * Who a device sees as the room's admins is [admins], through [RoomAdmins.override].
 */
class GroupInboxHarness(
    names: List<String> = listOf("alice", "bob", "carol"),
    /** Members of the room who are not admins and hold no share. */
    watchers: List<String> = emptyList(),
    val threshold: Int = 2,
) {
    val secrets: Map<String, ByteArray> = (names + watchers).associateWith { Crypto.sha256("dedun inbox $it".encodeToByteArray()) }
    val keyOf: Map<String, HexKey> = secrets.mapValues { KeyPair(it.value).pubKey.toHexKey() }
    val signers: List<HexKey> = names.map { keyOf.getValue(it) }
    val everyone: List<HexKey> = (names + watchers).map { keyOf.getValue(it) }

    val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")),
        nParticipants = names.size,
        threshold = threshold,
    )
    val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()
    val roomId: String = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    private val ceremonyId = "ceremony".padEnd(64, '0')
    // Chosen to sort as the signers read, so signer i holds share i.
    private val signingHostKeys = names.indices.map { "2${('a' + it)}".padEnd(66, '0') }

    /** Who every device reads as the room's admins. */
    var admins: Set<HexKey> = signers.toSet()

    val devices = mutableListOf<Device>()

    init {
        ExperimentalFeatures.groupInboxOverride = true
        RoomAdmins.override = { id -> if (id == roomId) RoomAdmins(admins, governed = false) else null }
    }

    fun close() {
        devices.forEach { it.db.close() }
        RoomAdmins.override = null
        ExperimentalFeatures.groupInboxOverride = null
    }

    inner class Device(val name: String, val publicKey: HexKey, val secret: ByteArray, val db: DedunDatabase) {
        /** Inner events already handed to this device, so a pump terminates. */
        val received = mutableSetOf<String>()

        suspend fun room(): LocalChatRoom = db.chatRoomDao().findChatRoomById(roomId)!!

        suspend fun keys() = db.inboxKeyDao().getKeys(roomId, publicKey)

        suspend fun ceremony(id: String) = db.inboxKeyDao().getCeremony(id)

        suspend fun lines(types: Set<String>) =
            db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }.filter { it.messageType in types }
    }

    suspend fun device(name: String): Device {
        val publicKey = keyOf.getValue(name)
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

        everyone.forEach { member ->
            val eventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = eventId))
        }
        db.chatRoomDao().upsert(
            ChatRoom(
                id = roomId,
                userPublicKey = publicKey,
                subject = "Ekklesia",
                description = SharedKeyDerivation.describe("Admins of the group."),
                mlsGroupState = "00",
            )
        )

        val signerIndex = signers.indexOf(publicKey)
        if (signerIndex >= 0) {
            db.dkgSessionDao().upsert(
                DkgSession(
                    id = ceremonyId,
                    chatRoomId = roomId,
                    coordinatorPublicKey = signers.first(),
                    userPublicKey = publicKey,
                    threshold = threshold,
                    participantCount = signers.size,
                    stage = DkgRitualStage.COMPLETE,
                    hostPublicKey = signingHostKeys[signerIndex],
                    round1Random = "1".repeat(64),
                    round2AuxRandom = "2".repeat(64),
                    thresholdPublicKey = thresholdPublicKey,
                    secretShare = keyMaterial.secretShares[signerIndex].value.toHex(),
                    publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() },
                )
            )
            signers.forEachIndexed { index, signer ->
                db.dkgSessionDao().upsert(
                    DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = signer, kind = DkgRitualEvents.HOST_KEY, payload = signingHostKeys[index])
                )
            }
        }
        return Device(name, publicKey, secrets.getValue(name), db).also { devices += it }
    }

    /** The kinds [pump] carries between devices. */
    var carried: Set<Int> = FrostSigningEvents.ALL + InboxKeyRitualEvents.ALL

    private suspend fun outbox(device: Device): List<MarmotInnerEvent> =
        device.db.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId = roomId, kinds = carried.toList())
            .filter { it.publicKey == device.publicKey }
            .sortedWith(compareBy({ it.createdAt }, { it.kind }))

    /**
     * Hands everything each device has sent to every other, until nobody has anything new.
     * [except] devices are offline: they neither send nor receive.
     */
    suspend fun pump(vararg between: Device = devices.toTypedArray(), except: Set<Device> = emptySet()) {
        val online = between.filter { it !in except }
        var moved = true
        while (moved) {
            moved = false
            for (from in online) for (to in online) {
                if (from === to) continue
                outbox(from).forEach { row ->
                    if (!to.received.add(row.id)) return@forEach
                    moved = true
                    to.db.marmotInnerEventDao().upsert(row.copy())
                    val event = Event(row.id, row.publicKey, row.createdAt.epochSeconds, row.kind, row.tags, row.content, "")
                    val room = to.room()
                    when {
                        FrostSigningEvents.isFrostSigningKind(row.kind) ->
                            FrostSigningManager.processSigningPayload(to.db, room, event, to.publicKey)
                        InboxKeyRitualEvents.isInboxKeyKind(row.kind) ->
                            InboxKeyRitualManager.process(to.db, room, event, to.publicKey, to.secret)
                    }
                }
            }
        }
    }
}
