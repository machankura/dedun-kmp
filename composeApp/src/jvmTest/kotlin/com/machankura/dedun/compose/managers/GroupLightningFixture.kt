package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import kotlin.time.Clock
import kotlin.test.assertTrue
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.ByteVector
import fr.acinq.lightning.Lightning.randomBytes32
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.DepositRow
import kotlin.time.Instant
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.database.model.ChatMessage
import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlin.test.assertNotNull

/**
 * Four devices, each with its own database, holding a 3-of-4 FROST key for one Marmot room -- the
 * group a Lightning wallet is made for. Messages move between them as the managers queue them, the
 * seam `SubgroupCeremonyInParentRoomTest` uses: MLS itself is not under test.
 *
 * [pump] delivers every queued message of the given kinds to every other device, and hands each to the
 * manager the inbound path would, until nothing new moves.
 */
class GroupLightningFixture(val n: Int = 4, val frostThreshold: Int = 3, val chain: Chain = Chain.Regtest) {
    val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32("1ceb".padEnd(62, '0') + "a1")),
        nParticipants = n,
        threshold = frostThreshold,
    )
    val thresholdPublicKey: String = keyMaterial.thresholdPublicKey.value.toHex()
    val onchainCeremonyId: String = "onchain".padEnd(64, '0')
    val roomId: String = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    val account = GroupWalletDerivation.account(thresholdPublicKey, chain)

    val keys: List<KeyPair> = (1..n).map { KeyPair() }
    val members: List<HexKey> = keys.map { it.pubKey.toHexKey() }
    private val hostKeys = (1..n).map { "02" + "2${it}".padEnd(64, '0') }

    val devices = mutableListOf<Device>()

    inner class Device(val index: Int, val db: DedunDatabase, val room: LocalChatRoom) {
        val publicKey: HexKey = members[index]
        val privateKey: ByteArray = keys[index].privKey!!
        val received = mutableSetOf<String>()
        suspend fun ceremony(id: String): IcebergKeySession? = db.icebergKeySessionDao().getSessionById(id)
    }

    fun close() = devices.forEach { it.db.close() }

    /** [member] leaves the room on every device in [on], as an applied removal commit leaves it. */
    suspend fun remove(member: HexKey, on: List<Device>) = on.forEach { d ->
        d.db.participantDao().delete(d.db.participantDao().findParticipantsByChatRoomId(roomId).filter { it.participantPublicKey == member })
    }

    suspend fun device(index: Int, holders: List<HexKey> = members): Device {
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
        members.forEach { member ->
            val eventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = eventId))
        }
        val chatRoom = ChatRoom(id = roomId, userPublicKey = members[index], subject = "Treasury", description = SharedKeyDerivation.describe("Treasury."), mlsGroupState = "00")
        db.chatRoomDao().upsert(chatRoom)
        db.participantDao().upsert(members.map { Participant(participantPublicKey = it, chatRoomId = roomId, relayHint = null) })
        db.dkgSessionDao().upsert(
            DkgSession(
                id = onchainCeremonyId, chatRoomId = roomId, coordinatorPublicKey = members.first(), userPublicKey = members[index],
                threshold = frostThreshold, participantCount = n, stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[index], round1Random = "1".repeat(64), round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[index].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() },
                participantPublicKeys = DkgSession.formatParticipants(holders.toSet()),
            )
        )
        members.forEachIndexed { i, member ->
            db.dkgSessionDao().upsert(DkgParticipantMessage(sessionId = onchainCeremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = hostKeys[i]))
        }
        val room = db.chatRoomDao().findChatRoomById(roomId) ?: LocalChatRoom(chatRoom = chatRoom)
        return Device(index, db, room).also { devices += it }
    }

    suspend fun outbox(from: Device, kinds: Set<Int>): List<MarmotInnerEvent> = from.db.marmotInnerEventDao()
        .getByChatRoomAndKinds(chatRoomId = roomId, kinds = kinds.toList())
        .filter { it.publicKey == from.publicKey }
        .sortedWith(compareBy({ it.createdAt }, { it.kind }))

    fun MarmotInnerEvent.asEvent() = Event(id, publicKey, createdAt.epochSeconds, kind, tags, content, "")

    /** What the inbound path does with one message, per kind. Later phases add theirs. */
    var dispatch: suspend (to: Device, event: Event) -> Unit = { to, event ->
        when (event.kind) {
            in FrostSigningEvents.ALL -> FrostSigningManager.processSigningPayload(to.db, to.room, event, to.publicKey)
            in GroupLightningEvents.CEREMONY -> IcebergRitualManager.processCeremonyPayload(to.db, to.room, event, to.publicKey, to.privateKey)
            GroupLightningEvents.NODE_SEED -> GroupLightningWalletManager.receiveSeed(to.db, to.room, event, to.publicKey, to.privateKey)
            GroupLightningEvents.SNAPSHOT, GroupLightningEvents.INVOICE, GroupLightningEvents.INVOICE_REQUEST -> GroupLightningReports.process(to.db, to.room, event)
            GroupLightningEvents.STOPPED -> GroupLightningOperatorManager.receiveStopped(to.db, to.room, event)
            GroupWavelengthEvents.AUTH_SEED -> GroupWavelengthWalletManager.receiveSeed(to.db, to.room, event, to.publicKey, to.privateKey)
        }
    }

    /** The Lightning wallet's lines of [type] in [device]'s copy of the room. */
    suspend fun lines(device: Device, type: String): List<ChatMessage> =
        device.db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }.filter { it.messageType == type }

    suspend fun pump(between: List<Device>, kinds: Set<Int>) {
        var moved = true
        while (moved) {
            moved = false
            for (from in between) for (to in between) {
                if (from === to) continue
                outbox(from, kinds).forEach { queued ->
                    if (to.received.add(queued.id)) {
                        moved = true
                        to.db.marmotInnerEventDao().upsert(queued.copy(chatRoomId = roomId))
                        dispatch(to, queued.asEvent())
                    }
                }
            }
        }
    }

    /** Proposes a group-signed event from [proposer] and has every other device approve it. */
    suspend fun sign(all: List<Device>, proposer: Device, kind: Int, tags: Array<Array<String>>, content: String) {
        val session = FrostSigningManager.proposeSigning(proposer.db, proposer.room, proposer.publicKey, kind, tags, content)
        signSession(all, proposer, session.id)
    }

    suspend fun signSession(all: List<Device>, proposer: Device, sessionId: String) {
        pump(all, FrostSigningEvents.ALL + GroupLightningEvents.WALLET_TRAFFIC + GroupWavelengthEvents.WALLET_TRAFFIC)
        all.filter { it !== proposer }.forEach { FrostSigningManager.approve(it.db, it.room, sessionId) }
        pump(all, FrostSigningEvents.ALL + GroupLightningEvents.WALLET_TRAFFIC + GroupWavelengthEvents.WALLET_TRAFFIC)
    }

    suspend fun openOnchainWallet(all: List<Device>) {
        val template = GroupWalletStatementEvent.template(account)
        sign(all, all[0], template.kind, template.tags, template.content)
        all.forEach { assertNotNull(GroupWalletManager.walletFor(it.db, roomId, chain), "device ${it.index} has the onchain wallet") }
    }

    /**
     * [member] sets a payout destination and the group opens their deposits, on every device in
     * [all]: what a member needs before the operator's node issues them an invoice
     * (`docs/member-deposits.md`, Phase 6).
     */
    suspend fun openDeposits(all: List<Device>, member: Device): HexKey {
        val signed = GroupPayoutDestinationEvent.build(
            PrivateKey(ByteVector32(member.privateKey)), roomId, chain, LightningAddress("member${member.index}", "example.com"),
        )
        val tags = arrayOf(arrayOf("h", roomId))
        val id = EventHasher.hashId(signed.pubKey, signed.createdAt, GroupPayoutDestinationEvent.KIND, tags, signed.toJson())
        all.forEach { d ->
            d.db.marmotInnerEventDao().upsert(
                MarmotInnerEvent(id = id, publicKey = signed.pubKey, kind = GroupPayoutDestinationEvent.KIND, createdAt = Instant.fromEpochSeconds(signed.createdAt), tags = tags, content = signed.toJson(), chatRoomId = roomId)
            )
        }
        val session = Depositors.propose(member.db, member.room, member.publicKey, member.publicKey, chain)
        signSession(all, member, session.id)
        all.forEach { assertNotNull(Depositors.statementOf(it.db, roomId, chain, member.publicKey), "device ${it.index} holds ${member.index}'s statement") }
        return signed.id
    }

    /**
     * What a payment needs to be a payout the group signs (`docs/payouts-only.md`, Phase 6): [member]'s
     * deposits opened, and [depositedMsat] of Lightning deposits on every device's ledger, so their
     * balance covers it. The label to put on the payment.
     */
    suspend fun payoutFor(all: List<Device>, member: Device, depositedMsat: Long = 100_000_000): GroupLightningAuthorisationEvent.Payout {
        val destination = openDeposits(all, member)
        all.forEach { d ->
            d.db.depositDao().upsert(
                DepositRow(
                    chatRoomId = roomId, chain = GroupWalletStatementEvent.chainName(chain), depositKey = "ln:" + randomBytes32().toHex(),
                    source = DepositRow.LIGHTNING, amountMsat = depositedMsat, member = member.publicKey, attributedBy = DepositRow.BY_INVOICE,
                )
            )
        }
        return GroupLightningAuthorisationEvent.Payout(member.publicKey, destination)
    }

    /**
     * [kind] over [tags], signed by the room as a quorum would have signed it, filed on every device
     * in [all] without asking any of them: a group-signed event from before a rule that now refuses
     * it -- a liquidity purchase, say -- whose machinery still has to work.
     */
    suspend fun fileSignedAsRoom(all: List<Device>, kind: Int, tags: Array<Array<String>>, content: String = "", createdAt: Long = Clock.System.now().epochSeconds): Event {
        val id = EventHasher.hashId(roomId, createdAt, kind, tags, content)
        val cache = SharedKeyDerivation.derive(thresholdPublicKey, SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH).cache
        val signature = GroupFixtures.signWith(keyMaterial, cache, ByteVector(id.hexToByteArray()), participants = n, threshold = frostThreshold).toHex()
        val event = Event(id, roomId, createdAt, kind, tags, content, signature)
        all.forEach { d ->
            val row = GroupSignedEvent.fromEvent(event, chatRoomId = roomId)
            assertTrue(row.verifies(), "the room signed it")
            d.db.groupSignedEventDao().upsert(row)
        }
        return event
    }

    /** Four devices with an onchain wallet open. */
    suspend fun group(): List<Device> = (0 until n).map { device(it) }.also { openOnchainWallet(it) }

    /** Runs a Lightning wallet key ceremony from [proposer], every other device approving every step. */
    suspend fun runCeremony(all: List<Device>, proposer: Device = all[0], threshold: Int = 2, purpose: String = IcebergKeySession.PURPOSE_LIGHTNING): IcebergKeySession {
        val proposed = IcebergRitualManager.proposeCeremony(proposer.db, proposer.room, proposer.publicKey, proposer.privateKey, threshold, chain, purpose)
        repeat(6) {
            pump(all, GroupLightningEvents.CEREMONY)
            all.forEach { d ->
                val session = d.ceremony(proposed.id) ?: return@forEach
                IcebergRitualManager.pendingApproval(d.db, session)?.let { step -> IcebergRitualManager.approve(d.db, d.room, session.id, step, d.privateKey) }
            }
        }
        pump(all, GroupLightningEvents.CEREMONY)
        return proposed
    }

    /**
     * Gives [member] somewhere to be paid and [sat] of deposits in [wallet] on every device, as if it had boarded and settled, so a payout to
     * them can be signed: what a Wavelength payment has to name.
     */
    suspend fun payable(all: List<Device>, member: Device, wallet: GroupWavelengthWalletManager.Wallet, sat: Long): GroupWavelengthPaymentEvent.Payout {
        val destination = GroupPayoutDestinationEvent.build(PrivateKey(ByteVector32(member.privateKey)), roomId, chain, LightningAddress("member${member.index}", "example.com"), createdAt = 1_700_000_000)
        val tags = arrayOf(arrayOf("h", roomId))
        val id = EventHasher.hashId(member.publicKey, destination.createdAt, GroupPayoutDestinationEvent.KIND, tags, destination.toJson())
        val key = GroupArkCoSigner.walletKey(roomId, chain, wallet.statement.wallet)
        all.forEach { device ->
            device.db.marmotInnerEventDao().upsert(
                MarmotInnerEvent(id = id, publicKey = member.publicKey, kind = GroupPayoutDestinationEvent.KIND, createdAt = Instant.fromEpochSeconds(destination.createdAt), tags = tags, content = destination.toJson(), chatRoomId = roomId)
            )
            device.db.depositDao().upsert(DepositRow(chatRoomId = roomId, chain = GroupWalletStatementEvent.chainName(chain), depositKey = "${"ab".repeat(32)}:${member.index}", source = DepositRow.WAVELENGTH, amountMsat = sat * 1000, member = member.publicKey.lowercase(), attributedBy = DepositRow.BY_BOARDING_ADDRESS))
            device.db.groupArkDao().upsertVtxo(GroupArkVtxo(key, "coin${member.index}:0", sat, settled = true))
        }
        return GroupWavelengthPaymentEvent.Payout(member.publicKey.lowercase(), assertNotNull(GroupPayoutDestinations.payoutDestinationOf(member.db, roomId, chain, member.publicKey)).event.id)
    }
}
