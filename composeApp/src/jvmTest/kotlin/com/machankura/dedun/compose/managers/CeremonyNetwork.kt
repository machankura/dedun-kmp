package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.crypto.curve25519.Ed25519
import com.vitorpamplona.marmot.groups.MarmotGroupPolicy
import com.vitorpamplona.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.random.Random
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents

/**
 * Several devices running one key ceremony, with the relay played by a loop.
 *
 * Each member has a database of their own, as on a phone, and nothing is shared
 * between them but the ceremony's messages. The ritual queues what a device sends
 * in its room's outbound store -- gift-wrap payloads in a NIP-17 room, inner
 * events in a Marmot one -- and [deliver] reads those back out and hands each to
 * every other device the way the inbound path does: stored first, in the store
 * `replayStoredMessages` reads, and then fed to `processRitualPayload`.
 *
 * What it does not do is encrypt. The transports are tested elsewhere; what this
 * is for is the ritual itself, run with real ChillDKG from a proposal to a key,
 * which no other test does. [GroupFixtures] gets its key material from a trusted
 * dealer.
 */
class CeremonyNetwork private constructor(
    val transport: Transport,
    val roomId: String,
    val devices: List<Device>,
) : AutoCloseable {

    enum class Transport { NIP17, MARMOT }

    class Device(val keys: KeyPair) {
        val publicKey: HexKey = keys.pubKey.toHexKey()
        val privateKey: ByteArray get() = keys.privKey!!
        val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

        override fun toString() = publicKey.take(8)
    }

    /** Which messages each device has already been handed, by device and event id. */
    private val delivered = mutableSetOf<Pair<HexKey, HexKey>>()

    val coordinator: Device get() = devices.first()

    /** The device's own copy of the room, read fresh: the ritual rewrites it. */
    suspend fun room(device: Device): LocalChatRoom =
        checkNotNull(device.db.chatRoomDao().findChatRoomById(roomId)) {
            "$device has no room $roomId"
        }

    /** The ceremony as this device has it, or null if it never recorded one. */
    suspend fun session(device: Device, sessionId: String): DkgSession? =
        device.db.dkgSessionDao().getSessionById(sessionId)

    suspend fun pendingApproval(device: Device, sessionId: String): DkgApprovalStep? =
        session(device, sessionId)?.let { ChillDkgRitualManager.pendingApproval(device.db, it) }

    suspend fun propose(
        threshold: Int,
        participants: Set<HexKey>? = null,
        parentChatRoomId: HexKey? = null,
        subject: String? = null,
    ): DkgSession = ChillDkgRitualManager.proposeRitual(
        database = coordinator.db,
        localChatRoom = room(coordinator),
        userPublicKey = coordinator.publicKey,
        nostrPrivateKey = coordinator.privateKey,
        threshold = threshold,
        parentChatRoomId = parentChatRoomId,
        participantPublicKeys = participants,
        subject = subject,
    )

    suspend fun approve(device: Device, sessionId: String, step: DkgApprovalStep) =
        ChillDkgRitualManager.approve(
            database = device.db,
            localChatRoom = room(device),
            sessionId = sessionId,
            step = step,
            nostrPrivateKey = device.privateKey,
        )

    /**
     * Hands every message queued so far to every device that has not had it.
     * Returns how many handings there were, so a caller can tell a quiet network.
     *
     * [random], when given, shuffles each pass: relays hand events back in no
     * particular order, and a round-1 message beating its proposal is a case the
     * ritual is written for.
     */
    suspend fun deliver(random: Random? = null): Int {
        val outbound = devices.flatMap { sender ->
            outboundOf(sender).map { event -> sender to event }
        }

        val handings = outbound.flatMap { (sender, event) ->
            devices
                .filter { it !== sender }
                .filter { (it.publicKey to event.id) !in delivered }
                .map { receiver -> receiver to event }
        }.let { if (random != null) it.shuffled(random) else it }

        handings.forEach { (receiver, event) ->
            delivered += receiver.publicKey to event.id
            store(receiver, event)
            ChillDkgRitualManager.processRitualPayload(
                database = receiver.db,
                localChatRoom = room(receiver),
                innerEvent = event,
                userPublicKey = receiver.publicKey,
                nostrPrivateKey = receiver.privateKey,
            )
        }

        return handings.size
    }

    /** Delivers until a pass moves nothing. */
    suspend fun deliverUntilQuiet(random: Random? = null) {
        repeat(MAX_PASSES) {
            if (deliver(random) == 0) return
        }
        error("The network was still busy after $MAX_PASSES passes")
    }

    /** What [sender] has queued for its room, as the rumors the others receive. */
    private suspend fun outboundOf(sender: Device): List<Event> {
        val kinds = DkgRitualEvents.ALL.toList()

        return when (transport) {
            Transport.NIP17 -> sender.db.giftWrapPayloadDao()
                .getByChatRoomAndKinds(roomId, kinds)
                .filter { it.publicKey == sender.publicKey }
                .map { Event(it.id, it.publicKey, it.createdAt.epochSeconds, it.kind, it.tags, it.content, "") }

            Transport.MARMOT -> sender.db.marmotInnerEventDao()
                .getByChatRoomAndKinds(roomId, kinds)
                .filter { it.publicKey == sender.publicKey }
                .map { Event(it.id, it.publicKey, it.createdAt.epochSeconds, it.kind, it.tags, it.content, "") }
        }
    }

    /** Stores [event] the way the inbound path does before it dispatches on kind. */
    private suspend fun store(receiver: Device, event: Event) {
        val createdAt = kotlin.time.Instant.fromEpochSeconds(event.createdAt)

        when (transport) {
            Transport.NIP17 -> receiver.db.giftWrapPayloadDao().upsert(
                GiftWrapPayload(
                    id = event.id,
                    publicKey = event.pubKey,
                    chatRoomId = roomId,
                    kind = event.kind,
                    tags = event.tags,
                    content = event.content,
                    createdAt = createdAt,
                )
            )

            Transport.MARMOT -> receiver.db.marmotInnerEventDao().upsert(
                MarmotInnerEvent(
                    id = event.id,
                    publicKey = event.pubKey,
                    kind = event.kind,
                    createdAt = createdAt,
                    tags = event.tags,
                    content = event.content,
                    chatRoomId = roomId,
                )
            )
        }
    }

    override fun close() = devices.forEach { it.db.close() }

    companion object {
        private const val MAX_PASSES = 50

        /**
         * A NIP-17 group of [size] members, the first of whom will open the
         * ceremony. The room's id is derived from its members, so every device
         * standing up its own copy lands on the same one.
         */
        suspend fun nip17(size: Int, subject: String = "Robust group"): CeremonyNetwork {
            val devices = List(size) { Device(KeyPair()) }
            val everyone = devices.map { it.publicKey }

            val rooms = devices.map { device ->
                everyone.forEach { seedProfile(device.db, it) }
                checkNotNull(
                    device.db.nostrNip17Dao().createNip17ChatRoom(
                        userPublicKey = device.publicKey,
                        participantPublicKeys = everyone - device.publicKey,
                        subject = subject,
                    )
                ) { "createNip17ChatRoom returned null on $device" }
            }

            val roomId = rooms.map { it.chatRoom.id }.distinct().single()
            return CeremonyNetwork(Transport.NIP17, roomId, devices)
        }

        /**
         * A Marmot room of [size] members with id [roomId], standing in for the
         * parent a subgroup's ceremony runs in. The MLS group is real but not
         * joint, as in `SubgroupCeremonyInParentRoomTest`: messages are handed
         * across directly rather than encrypted between trees.
         */
        suspend fun marmot(size: Int, roomId: String, subject: String = "Ekklesia"): CeremonyNetwork {
            val devices = List(size) { Device(KeyPair()) }
            val everyone = devices.map { it.publicKey }

            devices.forEach { device ->
                everyone.forEach { seedProfile(device.db, it) }

                val metadata = MarmotGroupData(
                    nostrGroupId = roomId,
                    name = subject,
                    description = SharedKeyDerivation.describe("Admins of $subject."),
                    adminPubkeys = everyone,
                    relays = Relays.DefaultDMRelayList.map { it.url }
                )
                val mlsGroup = MlsGroup.create(
                    KeyPair().pubKey,
                    Ed25519.generateKeyPair().privateKey,
                    listOf(metadata.toExtension()),
                    policy = MarmotGroupPolicy,
                )

                device.db.chatRoomDao().upsert(
                    ChatRoom(
                        id = roomId,
                        userPublicKey = device.publicKey,
                        subject = subject,
                        description = metadata.description,
                        mlsGroupState = mlsGroup.saveState().encodeTls().toHex(),
                    )
                )
                device.db.participantDao().upsert(
                    everyone.map {
                        Participant(participantPublicKey = it, chatRoomId = roomId, relayHint = null)
                    }
                )
            }

            return CeremonyNetwork(Transport.MARMOT, roomId, devices)
        }

        private suspend fun seedProfile(database: DedunDatabase, publicKey: HexKey) {
            if (database.profileDao().getProfileByPublicKey(publicKey) != null) return

            val nostrEventId = publicKey.take(63) + "f"
            database.nostrEventDao().upsert(
                NostrEvent(
                    id = nostrEventId,
                    pubKey = publicKey,
                    kind = 0,
                    tags = emptyArray(),
                    content = "{}",
                    sig = "0".repeat(128),
                )
            )
            database.profileDao().upsert(
                Profile(publicKey = publicKey, userName = "member", nostrEventId = nostrEventId)
            )
        }
    }
}
