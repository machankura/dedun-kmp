package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.GovernanceProposal
import com.machankura.dedun.compose.database.model.GovernanceProposalStage
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.AddedMember
import com.machankura.dedun.compose.nostr.frost.GovernanceCarrierEvent
import com.machankura.dedun.compose.nostr.frost.GovernanceChange
import com.machankura.dedun.compose.nostr.frost.GovernanceEvents
import com.machankura.dedun.compose.nostr.frost.GovernanceStatementEvent
import com.machankura.dedun.compose.nostr.frost.OwnCommitEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupSpend
import com.vitorpamplona.marmot.GroupEventResult
import com.vitorpamplona.marmot.appComponents.AdminPolicyV1
import com.vitorpamplona.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.marmot.groups.MlsGroupManager
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.marmot.mip03GroupMessages.GroupEventEncryption
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.tree.Credential
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.text.ProposedSpend
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A governed room shared by a Giza-shaped device and two Dedun devices, end to end.
 * docs/giza-into-dedun.md, Phase 8: the two tests that phase owed.
 *
 * Dana plays Giza. Her device is quartz's own engine ([MarmotStandIn]) doing what a
 * Giza build does: it founds the room keyed to the group's key with the governance
 * marker in its first epoch, makes every change after the founding invite inside the
 * group's signature, and applies the commit inside each carrier and envelope it is
 * handed. Ann and Dedun's Ben are the app, each with a database of their own, holding
 * shares 0 and 1 of a 2-of-3 trusted-dealer key whose third share is Dana's.
 *
 * Nothing is planted between the devices: every message is a kind:445 one of them
 * encrypted, and each Dedun device takes what it is handed through the sync funnel.
 * The relay is [pump], which puts each device's queued messages on the wire, gives the
 * device the relay's OK on them -- the OK is what installs a commit -- and hands every
 * message to every member who has not had it. The group's signature on a Dedun
 * device's change is real FROST between Ann's and Ben's devices; on Dana's it is the
 * trusted dealer's, standing in for her and one other holder signing on Giza.
 */
class MixedDeviceGovernedRoomJvmTest {

    private val material = GroupFixtures.keyMaterial("91a2a0000000000000000000000000000000000000000000000000000000d0a1")
    private val thresholdPublicKey = GroupFixtures.thresholdKeyOf(material)
    private val roomId = GroupFixtures.roomOf(material)
    private val ceremonyId = "ceremony".padEnd(64, '0')
    private val hostKeys = listOf("2a", "2b", "2c").map { it.padEnd(66, '0') }
    private val chain = Chain.Regtest

    private val dana = MarmotStandIn()
    private val erin = MarmotStandIn()
    private val frank = MarmotStandIn()

    private val scope = CoroutineScope(Dispatchers.Default)
    private val dedun = mutableListOf<Dedun>()

    @AfterTest
    fun closeDatabases() = dedun.forEach { it.db.close() }

    /** One Dedun device: its own database, as on a phone. */
    private inner class Dedun(val name: String, val signerIndex: Int) {
        val keys = KeyPair()
        val hex: HexKey = keys.pubKey.toHexKey()
        val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
        val chat = DatabaseChatRepository(db, scope)
        val marmot = DatabaseMarmotRepository(db, scope)
        val nostr = DatabaseNostrRepository(db, scope)

        /** The kind:445s of this device's that are already on the wire. */
        val wired = mutableSetOf<HexKey>()

        suspend fun room() = assertNotNull(db.chatRoomDao().findChatRoomById(roomId), "$name has no room")
        suspend fun group(): MlsGroup = assertNotNull(db.marmotEngine.groupFor(routingId), "$name has no live group")
        suspend fun lines(type: String) =
            db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }.filter { it.messageType == type }

        override fun toString() = name
    }

    private val ann by lazy { Dedun("Ann", signerIndex = 0).also { dedun += it } }
    private val ben by lazy { Dedun("Ben", signerIndex = 1).also { dedun += it } }

    private lateinit var routingId: String

    // ---- The wire ------------------------------------------------------------

    /** Every kind:445 sent, in the order the relay took it, with who sent it. */
    private val wire = mutableListOf<Pair<Any, GroupEvent>>()

    /** Who has been handed which of [wire], by index. */
    private val handed = mutableMapOf<Any, Int>()

    /** The members a message is handed to: who is in the group now. */
    private val inRoom = mutableListOf<Any>()

    /** What a Dedun device put on the wire that the others' engines took as a bare commit. */
    private val bareFromBennu = mutableListOf<GroupEvent>()

    private fun joins(member: Any) {
        inRoom += member
        handed[member] = wire.size
    }

    private fun leaves(member: Any) {
        inRoom -= member
    }

    /** [event] on the wire from [sender]. */
    private fun send(sender: Any, event: GroupEvent) {
        wire += sender to event
    }

    /**
     * What [device] has to send, sent: whatever it already built for a relay (a
     * carrier, an envelope), and then its queue of messages, encrypted one at a time
     * the way the outbound worker does. Each takes the relay's OK as it goes out.
     */
    private suspend fun flush(device: Dedun): Boolean {
        var moved = false
        while (true) {
            val built = pendingOf(device)
            if (built.isNotEmpty()) {
                built.forEach { putOnWire(device, it) }
                moved = true
                continue
            }
            val queued = device.db.marmotInnerEventDao().observeUnprocessedMarmotInnerEvents(device.hex).first()
                .filter { it.chatRoomId == roomId }
            if (queued.isEmpty()) return moved
            device.marmot.encryptAndSendMarmotInnerEvent(queued.first(), NostrSignerSync(device.keys))
            moved = true
        }
    }

    /** Kind:445s [device] has built for a relay and not yet sent, oldest first. */
    private suspend fun pendingOf(device: Dedun): List<NostrEvent> =
        device.db.nostrEventDao().getMLSGroupEvents(since = Instant.fromEpochSeconds(0), limit = 1_000)
            .filter { it.kind == GroupEvent.KIND && it.id !in device.wired }
            .filter { device.db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(it.id)?.status == "pending" }
            .sortedBy { it.createdAt }

    private suspend fun putOnWire(device: Dedun, event: NostrEvent) {
        device.wired += event.id
        send(device, assertNotNull(event.toGroupEvent(device.hex)))
        device.nostr.broadcastProcessed(
            assertNotNull(device.db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(event.id)),
            "published",
        )
    }

    /** Hands every member each message on the wire they have not had. */
    private suspend fun deliver(): Boolean {
        var moved = false
        for (member in inRoom.toList()) {
            while (member in inRoom && handed.getValue(member) < wire.size) {
                val index = handed.getValue(member)
                handed[member] = index + 1
                val (sender, event) = wire[index]
                if (sender === member) continue
                moved = true
                when (member) {
                    is Dedun -> MarmotStandIn.storeInApp(member.db, member.keys, event)
                    is MarmotStandIn -> member.receive(event, fromBennu = sender is Dedun)
                }
            }
        }
        return moved
    }

    /** Sends and hands on until nothing moves. */
    private suspend fun pump() {
        repeat(200) {
            var moved = false
            for (device in dedun.filter { it in inRoom }) moved = flush(device) || moved
            moved = deliver() || moved
            if (!moved) return
        }
        error("The room was still busy after 200 passes")
    }

    /**
     * What a Giza device does with a kind:445: applies it, and when it is a carrier
     * of the group's signature or a member's own envelope, applies the commit inside.
     */
    private suspend fun MarmotStandIn.receive(event: GroupEvent, fromBennu: Boolean) {
        when (val result = inbound.processGroupEvent(event)) {
            is GroupEventResult.ApplicationMessage -> {
                val inner = Event.fromJson(result.innerEventJson)
                if (inner.kind == GovernanceCarrierEvent.KIND || inner.kind == OwnCommitEvent.KIND) {
                    val statement = GovernanceCarrierEvent.statementOf(inner) ?: inner
                    inbound.processGroupEvent(assertNotNull(GovernanceEvents.innerCommitOf(statement), "an envelope with no commit in it"))
                }
            }
            is GroupEventResult.CommitProcessed -> if (fromBennu) bareFromBennu += event
            else -> {}
        }
    }

    // ---- The room, the Giza way -----------------------------------------------

    private fun MarmotStandIn.group(): MlsGroup = assertNotNull(manager.getGroup(routingId))

    private fun MlsGroup.leafOf(member: HexKey): Int =
        members().first { (_, leaf) -> (leaf.credential as Credential.Basic).identity.toHexKey() == member }.first

    /** Dana's [commit] at [epoch], put on the wire inside the group's signature on [change]. */
    private suspend fun danaCarries(staged: MlsGroupManager.StagedCommit, change: GovernanceChange) {
        val epoch = dana.group().epoch
        val commit = dana.outbound.buildCommitEvent(routingId, staged.result.framedCommitBytes, staged.result.preCommitExporterSecret).signedEvent
        val statement = GroupFixtures.signedAsRoom(
            material, GovernanceStatementEvent.KIND,
            GovernanceStatementEvent.assembleTags(roomId, epoch, change, proposer = dana.hex),
            commit.toJson(),
        )
        val payload = MarmotAppEvent.build(
            pubKey = dana.hex, kind = GovernanceCarrierEvent.KIND, content = statement.toJson(),
            createdAt = statement.createdAt, tags = GovernanceCarrierEvent.assembleTags(roomId),
        ).encodeToPayload()
        send(dana, dana.outbound.buildGroupEventFromBytes(routingId, payload).signedEvent)
        dana.manager.installState(routingId, staged.pendingState)
    }

    /** [device]'s share of the group's key, filed as a finished ceremony files it. */
    private suspend fun holdsShare(device: Dedun) {
        val roster = listOf(ann.hex, ben.hex, dana.hex)
        device.db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId, chatRoomId = roomId, coordinatorPublicKey = dana.hex, userPublicKey = device.hex,
                threshold = 2, participantCount = 3, stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[device.signerIndex], round1Random = "1".repeat(64), round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = material.secretShares[device.signerIndex].value.toHex(),
                publicShares = material.publicShares.joinToString(",") { it.value.toHex() },
            )
        )
        roster.forEachIndexed { index, member ->
            device.db.dkgSessionDao().upsert(
                DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = hostKeys[index])
            )
        }
    }

    /**
     * Dana founds the room governed from its first epoch, invites Ann by the founding
     * commit -- the one Giza makes bare, while she is its only member -- makes Ann an
     * admin, and invites Ben, both inside the group's signature.
     */
    private suspend fun room() {
        listOf(ann, ben).forEach { device ->
            listOf(ann.hex, ben.hex, dana.hex, erin.hex, frank.hex).forEach { MarmotStandIn.seedProfile(device.db, it) }
        }
        val keyed = MarmotRoomIdentity.describe(SharedKeyDerivation.describe("Treasury."), roomId)
        routingId = dana.createGroup("#treasury", GovernanceMarker.describe(keyed, minimumAdmins = 2))
        joins(dana)

        dana.inviteApp(ann.db, ann.marmot, ann.keys, routingId, roomId)
        joins(ann)
        assertTrue(ann.group().isGoverned(), "the room was not born governed")

        danaCarries(
            dana.manager.stageAppDataUpdate(routingId, AdminPolicyV1.COMPONENT_ID, AdminPolicyV1.ofHex(listOf(dana.hex, ann.hex)).encode()),
            GovernanceChange(admins = setOf(dana.hex, ann.hex)),
        )
        pump()
        assertEquals(setOf(dana.hex, ann.hex), ann.group().currentAdminIdentities(), "Ann was not made an admin")

        val (benEvent, benPackage) = dana.appKeyPackage(ben.db, ben.marmot, ben.keys)
        val add = dana.manager.stageAddMember(routingId, benPackage.toTlsBytes())
        danaCarries(add, GovernanceChange(adds = setOf(AddedMember(ben.hex, benPackage.reference().toHex()))))
        dana.welcomeApp(ben.db, ben.keys, routingId, assertNotNull(add.result.welcomeBytes), benEvent.id)
        joins(ben)
        pump()

        holdsShare(ann)
        holdsShare(ben)
        assertOneGroup(ann.hex, ben.hex, dana.hex)
    }

    /** Ann's device, Ben's and Dana's on one epoch, one key schedule, one membership. */
    private suspend fun assertOneGroup(vararg members: HexKey) {
        val danas = dana.group()
        listOf(ann, ben).forEach { device ->
            val group = device.group()
            assertEquals(danas.epoch, group.epoch, "$device is at another epoch than Dana")
            assertContentEquals(danas.epochAuthenticator, group.epochAuthenticator, "$device is in another group than Dana")
            assertEquals(members.toSet(), group.currentMemberIdentities(), "$device sees other members")
            assertTrue(device.lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).isEmpty(), "$device refused a change")
        }
        assertEquals(members.toSet(), danas.currentMemberIdentities())
        assertTrue(bareFromBennu.isEmpty(), "a Dedun device sent a bare commit into the governed room")
    }

    /** Ben puts his share to the session Ann's device opened, as he would on the approval screen. */
    private suspend fun benApproves(sessionId: String) {
        assertNotNull(ben.db.frostSigningSessionDao().getSessionById(sessionId), "Ben's device never heard of the session")
        FrostSigningManager.approve(ben.db, ben.room(), sessionId)
        pump()
    }

    /** What Ann's device asks the group to sign when she does [change] in the app. */
    private suspend fun annProposes(change: suspend () -> Unit): GovernanceProposal {
        val before = ann.db.governanceProposalDao().getForRoom(roomId).map { it.id }.toSet()
        change()
        pump()
        return ann.db.governanceProposalDao().getForRoom(roomId).single { it.id !in before }
    }

    /** Erin, a member with no share, joining from the Welcome Ann's device sent her. */
    private suspend fun erinTakesHerWelcome() {
        val welcome = ann.db.giftWrapPayloadDao().getAllGiftWrapUnsigned().single { it.kind == WelcomeEvent.KIND && it.publicKey == ann.hex }
        val event = WelcomeEvent(id = welcome.id, pubKey = ann.hex, createdAt = welcome.createdAt.epochSeconds, tags = welcome.tags, content = welcome.content, sig = "")
        erin.manager.processWelcome(Base64.decode(event.welcomeBase64()), erin.bundle)
        erin.inbound.trackGroup(routingId)
        joins(erin)
    }

    // ---- Invite, leave and remove ----------------------------------------------

    @Test
    fun `invites, a leave and a removal across Giza and Dedun devices leave every device in one group`() = runBlocking {
        room()

        // 1. Ann invites Erin from the app. The group signs it -- Ann's share and Ben's --
        //    and the carrier is what goes out; Dana's device applies what is inside it.
        erin.makeBundle()
        val erinPackage = MarmotKeyPackage(
            id = erin.hex, publicKey = erin.hex,
            tlsEncodedMarmotKeyPackage = erin.bundle.keyPackage.toTlsBytes(), profile = MarmotProfile.CURRENT.wireName,
        )
        val beforeInvite = ann.group().epoch
        val invite = annProposes { ann.chat.inviteMember(ann.room(), erin.hex, erinPackage) }
        assertEquals(beforeInvite, ann.group().epoch, "the invite landed before the group signed it")
        benApproves(invite.frostSigningSessionId)
        assertEquals(GovernanceProposalStage.APPLIED, ann.db.governanceProposalDao().getById(invite.id)?.stage)
        erinTakesHerWelcome()
        pump()
        assertEquals(beforeInvite + 1, ann.group().epoch)
        assertOneGroup(ann.hex, ben.hex, dana.hex, erin.hex)

        // 2. Dana invites Frank from Giza, inside the group's signature.
        frank.makeBundle()
        val add = dana.manager.stageAddMember(routingId, frank.bundle.keyPackage.toTlsBytes())
        danaCarries(add, GovernanceChange(adds = setOf(AddedMember(frank.hex, frank.bundle.keyPackage.reference().toHex()))))
        frank.manager.processWelcome(assertNotNull(add.result.welcomeBytes), frank.bundle)
        frank.inbound.trackGroup(routingId)
        joins(frank)
        pump()
        assertOneGroup(ann.hex, ben.hex, dana.hex, erin.hex, frank.hex)

        // 3. Erin leaves. Her proposal needs nobody's signature; Ann's device, an
        //    admin's, commits it inside its own envelope, which Dana's applies.
        val (framed, exporter) = erin.manager.leaveGroup(routingId)
        send(erin, NostrSignerInternal(KeyPair()).sign(GroupEvent.build(GroupEventEncryption.encrypt(framed, exporter), routingId)))
        leaves(erin)
        pump()
        assertOneGroup(ann.hex, ben.hex, dana.hex, frank.hex)
        // Dedun's: what reacts to a departure -- a group wallet's list of signers who have
        // left, which only a new wallet leaves out -- reads who is still among the room's
        // participants. A leave that came inside an envelope takes her out as a bare one does.
        listOf(ann, ben).forEach { device ->
            val inRoom = device.db.participantDao().findParticipantsByChatRoomId(roomId).filter { it.deletedAt == null }.map { it.participantPublicKey.lowercase() }
            assertTrue(erin.hex.lowercase() !in inRoom, "$device still counts Erin in the room")
            assertTrue(frank.hex.lowercase() in inRoom, "$device lost Frank with her")
        }

        // 4. Ann removes Frank from the app, again inside the group's signature.
        val beforeRemoval = ann.group().epoch
        val removal = annProposes { ann.chat.removeMember(ann.room(), frank.hex) }
        assertEquals(beforeRemoval, ann.group().epoch, "the removal landed before the group signed it")
        benApproves(removal.frostSigningSessionId)
        leaves(frank)
        pump()
        assertEquals(GovernanceProposalStage.APPLIED, ann.db.governanceProposalDao().getById(removal.id)?.stage)

        assertEquals(beforeRemoval + 1, ann.group().epoch)
        assertOneGroup(ann.hex, ben.hex, dana.hex)
        assertTrue(dana.group().isGoverned(), "the room stopped being governed on the way")
        assertEquals(setOf(dana.hex, ann.hex), dana.group().currentAdminIdentities())
    }

    // ---- A spend while governed ------------------------------------------------

    private val account = GroupWalletDerivation.account(thresholdPublicKey, chain)
    private val first = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
    private val funding = Transaction(
        version = 2,
        txIn = listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 0x42 })), 0), ByteVector.empty, 0xffffffffL)),
        txOut = listOf(TxOut(Satoshi(100_000), first.pubkeyScript)),
        lockTime = 0,
    )
    private val elsewhere = Script.write(Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { 9 })).publicKey())).let(::ByteVector)
    /**
     * A payout, the only spend Dedun's group wallet signs (docs/payouts-only.md): the coin is
     * all Ben's, and all of it, less the fee, goes to his payout address. Bennu's version of this
     * test spends with change, which Dedun refuses before it is proposed.
     */
    private val spend = Transaction(
        version = 2,
        txIn = listOf(TxIn(OutPoint(funding, 0), ByteVector.empty, 0xfffffffdL)),
        txOut = listOf(TxOut(Satoshi(99_000), elsewhere)),
        lockTime = 0,
    )

    /**
     * On Ann's and Ben's devices: Ben's payout address is [elsewhere], and the funding coin is
     * his by a coin assignment the room signed -- what a payout of it needs.
     */
    private suspend fun bensCoin() {
        val destination = GroupPayoutDestinationEvent.build(
            PrivateKey(ben.keys.privKey!!), roomId, chain, LightningAddress("ben", "example.com"),
            onchainAddress = ProposedSpend.addressOf(elsewhere, chain), createdAt = 1_700_000_000,
        )
        val template = CoinAssignmentEvent.template(
            chain,
            listOf(CoinAssignmentEvent.Coin(DepositAttributionEvent.DepositKey.Onchain(funding.txid.toString(), 0), 100_000, mapOf(ben.hex to 100_000L))),
            createdAt = 1_700_000_100,
        )
        val assignment = GroupFixtures.signedAsRoom(material, template.kind, template.tags, template.content, createdAt = 1_700_000_100)
        listOf(ann, ben).forEach { device ->
            val tags = arrayOf(arrayOf("h", roomId))
            device.db.marmotInnerEventDao().upsert(
                MarmotInnerEvent(
                    id = EventHasher.hashId(ben.hex, destination.createdAt, GroupPayoutDestinationEvent.KIND, tags, destination.toJson()),
                    publicKey = ben.hex, kind = GroupPayoutDestinationEvent.KIND, createdAt = Instant.fromEpochSeconds(destination.createdAt),
                    tags = tags, content = destination.toJson(), chatRoomId = roomId,
                )
            )
            device.db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(assignment, chatRoomId = roomId))
        }
    }

    @Test
    fun `a spend from the group's wallet is signed inside the governed room, and moves no epoch`() = runBlocking {
        room()
        val epoch = dana.group().epoch

        // The group opens its wallet by signing its statement, over the same room.
        val template = GroupWalletStatementEvent.template(account)
        val opening = FrostSigningManager.proposeSigning(ann.db, ann.room(), ann.hex, template.kind, template.tags, template.content)
        pump()
        benApproves(opening.id)
        listOf(ann, ben).forEach { assertNotNull(GroupWalletManager.walletFor(it.db, roomId, chain), "$it has no wallet") }

        bensCoin()
        val psbt = GroupSpend.psbtOf(spend, listOf(funding.txOut[0] to first), emptyMap(), account)
        val session = FrostSigningManager.proposeSpend(ann.db, ann.room(), ann.hex, psbt, chain)
        pump()
        benApproves(session.id)

        listOf(ann, ben).forEach { device ->
            assertEquals(FrostSigningStage.COMPLETE, device.db.frostSigningSessionDao().getSessionById(session.id)?.stage, "$device did not finish the spend")
            val signed = Transaction.read(assertNotNull(device.db.groupTransactionDao().getBySessionId(session.id)?.signedTransaction))
            signed.correctlySpends(mapOf(OutPoint(funding, 0) to funding.txOut[0]), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            assertEquals(spend.txid, signed.txid)
        }
        // The spend was application messages: the gate let it through and nothing moved.
        assertEquals(epoch, dana.group().epoch, "signing a spend changed the group")
        assertOneGroup(ann.hex, ben.hex, dana.hex)
    }
}
