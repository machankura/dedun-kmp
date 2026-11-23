package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.GroupDmWrap
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.groupdm.GroupEcdhEvents

/**
 * A keyed group whose devices are each their own database, for the group's direct messages
 * (docs/group-threshold-ecdh.md, Phases 5 to 10): every member a real nostr key, so host keys
 * derive as the app derives them, and nothing passing between devices but the inner events
 * [pump] ferries. Who a device sees as admins is [admins], through [RoomAdmins.override].
 *
 * Modelled on `ShareRefreshRitualJvmTest`'s devices.
 */
class GroupDmNetwork(n: Int = 4, val t: Int = 2, secret: String = "6d0000".padEnd(64, '7')) {
    val keyPairs: List<KeyPair> = List(n) { KeyPair() }
    val members: List<HexKey> = keyPairs.map { it.pubKey.toHexKey() }
    val hostKeys: List<HexKey> = keyPairs.map { ChillDkgRitualManager.deriveHostPublicKey(it.privKey!!).value.toHex() }

    /** Participant id of member i: its host key's place in the bytewise sort, as a ceremony orders them. */
    val idOf: List<Int> = hostKeys.map { hostKeys.sorted().indexOf(it) }

    val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(PrivateKey(ByteVector32(secret)), n, t)
    val thresholdPublicKey: HexKey = keyMaterial.thresholdPublicKey.value.toHex()
    val roomId: HexKey = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    val ceremonyId = "dmceremony".padEnd(64, '0')

    /** Who every device sees as the room's admins. */
    var admins: Set<HexKey> = members.toSet()

    val devices = mutableListOf<Device>()

    inner class Device(val index: Int, val db: DedunDatabase, val room: LocalChatRoom) {
        val keyPair: KeyPair get() = keyPairs[index]
        val publicKey: HexKey get() = members[index]
        val privateKey: ByteArray get() = keyPair.privKey!!
        val received = mutableSetOf<String>()

        suspend fun context() = GroupEcdhManager.contextFor(db, room, publicKey, privateKey)

        override fun toString(): String = "Device($index)"
    }

    fun install() {
        RoomAdmins.override = { id -> if (id == roomId) RoomAdmins(admins, governed = true) else null }
    }

    fun close() {
        devices.forEach { it.db.close() }
        RoomAdmins.override = null
    }

    suspend fun device(index: Int): Device {
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
        members.forEach { member ->
            val nostrEventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = nostrEventId))
        }
        val chatRoom = ChatRoom(id = roomId, userPublicKey = members[index], subject = "&Ekklesia", description = SharedKeyDerivation.describe("Admins."), mlsGroupState = "00")
        db.chatRoomDao().upsert(chatRoom)
        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId,
                chatRoomId = roomId,
                coordinatorPublicKey = members.first(),
                userPublicKey = members[index],
                threshold = t,
                participantCount = members.size,
                stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[index],
                round1Random = "1".repeat(64),
                round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[idOf[index]].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() },
            )
        )
        members.forEachIndexed { i, member ->
            db.dkgSessionDao().upsert(DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = hostKeys[i]))
        }
        return Device(index, db, LocalChatRoom(chatRoom = chatRoom)).also { devices += it }
    }

    suspend fun all(): List<Device> = members.indices.map { device(it) }

    // ---- the wire ----------------------------------------------------------------------------------------------

    val ferried: List<Int> = (GroupEcdhEvents.ALL + FrostSigningEvents.ALL).toList()

    suspend fun outbox(device: Device, kinds: List<Int> = ferried): List<MarmotInnerEvent> =
        device.db.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId = roomId, kinds = kinds)
            .filter { it.publicKey == device.publicKey }
            .sortedWith(compareBy({ it.createdAt }, { it.kind }))

    /**
     * Ferries every device's inner events to every other until nothing moves. [hold] keeps a
     * message from leaving; [between] is who is online.
     */
    suspend fun pump(between: List<Device> = devices, hold: (from: Device, MarmotInnerEvent) -> Boolean = { _, _ -> false }) {
        var moved = true
        while (moved) {
            moved = false
            for (from in between) for (to in between) {
                if (from === to) continue
                outbox(from).forEach { queued ->
                    if (queued.id in to.received || hold(from, queued)) return@forEach
                    to.received += queued.id
                    moved = true
                    deliver(queued, to)
                }
            }
        }
    }

    suspend fun deliver(queued: MarmotInnerEvent, to: Device) {
        to.db.marmotInnerEventDao().upsert(queued.copy(chatRoomId = roomId))
        val event = Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, queued.content, "")
        if (event.kind in GroupEcdhEvents.ALL) {
            GroupEcdhManager.process(to.db, to.room, event, to.publicKey, to.privateKey)
        } else {
            FrostSigningManager.processSigningPayload(to.db, to.room, event, to.publicKey, to.privateKey)
        }
    }

    // ---- the outside world -------------------------------------------------------------------------------------

    /** Someone on a stock NIP-17 app, writing to the group's npub. */
    suspend fun wrapFrom(sender: KeyPair, text: String): GiftWrapEvent {
        val rumor = Event("", sender.pubKey.toHexKey(), 1_700_000_000, 14, arrayOf(arrayOf("p", roomId)), text, "")
        val seal = SealEvent.create(rumor, roomId, NostrSignerInternal(sender))
        return GiftWrapEvent.create(seal, roomId)
    }

    /**
     * The group signs where it receives messages, through the real signing session: [proposer]
     * proposes, [approver] approves, and the network carries the rest. Every device then holds
     * the signed 10050.
     */
    suspend fun openMessages(proposer: Device = devices[0], approver: Device = devices[1], relays: List<String> = listOf("wss://dm.example.com")) {
        val session = GroupDmRelays.propose(proposer.db, proposer.room, proposer.publicKey, relays)
        pump()
        FrostSigningManager.approve(approver.db, approver.room, session.id)
        pump()
        check(devices.all { GroupDmRelays.relaysOf(it.db, roomId) != null }) { "the 10050 did not reach every device" }
    }

    /** [wrap] reaching [to] on the group's subscription. */
    suspend fun receive(wrap: Event, vararg to: Device) =
        to.forEach { GroupDmReceiver.receive(it.db, wrap, it.publicKey, it.privateKey) }

    /** The wrap as the group's subscription would put it in [device]'s ledger. */
    suspend fun ledger(device: Device, wrap: Event) {
        device.db.groupDmDao().insertWrap(
            GroupDmWrap(id = wrap.id, chatRoomId = roomId, wrapJson = wrap.toJson(), createdAt = Instant.fromEpochSeconds(wrap.createdAt))
        )
    }
}
