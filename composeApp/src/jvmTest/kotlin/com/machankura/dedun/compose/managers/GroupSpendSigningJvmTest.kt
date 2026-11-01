package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.text.ProposedSpend
import com.machankura.dedun.compose.wallet.GroupSpendBuilder
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.lightning.blockchain.electrum.WalletState
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A spend from the group's wallet between two devices, through the real
 * signing manager, with the wire between them held by hand -- the harness
 * `SignedGroupKeyStateTest` uses, cut down to a Marmot room.
 *
 * The wallet is opened the way a group opens one, by signing its statement in
 * the same harness, so the spend is checked against a statement that went
 * through the same session machinery rather than one planted in a table.
 *
 * The refusals are driven by handing a device a hand-built proposal, which is
 * the only way to be a dishonest member here. Each asserts the device made no
 * session at all: a proposal refused at acceptance has nothing to approve, so
 * nothing of the member's can ever be published for it.
 */
class GroupSpendSigningJvmTest {
    private val participants = 3
    private val threshold = 2

    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")),
        nParticipants = participants,
        threshold = threshold,
    )
    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()
    private val ceremonyId = "ceremony".padEnd(64, '0')
    private val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    private val members = listOf("a", "b", "c").map { it.repeat(64) }
    private val hostKeys = listOf("2a", "2b", "2c").map { it.padEnd(66, '0') }
    private val chain = Chain.Regtest
    private val account = GroupWalletDerivation.account(thresholdPublicKey, chain)

    private val devices = mutableListOf<Device>()

    @AfterTest
    fun closeDatabases() = devices.forEach { it.db.close() }

    private inner class Device(val publicKey: HexKey, val db: DedunDatabase, val room: LocalChatRoom) {
        val received = mutableSetOf<String>()

        suspend fun store(queued: Queued) = db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = queued.id, publicKey = queued.publicKey, kind = queued.kind, createdAt = queued.createdAt,
                tags = queued.tags, content = queued.content, chatRoomId = roomId,
            )
        )

        suspend fun session(id: String) = db.frostSigningSessionDao().getSessionById(id)
        suspend fun transaction(id: String) = db.groupTransactionDao().getBySessionId(id)
    }

    private suspend fun device(publicKey: HexKey, signerIndex: Int): Device {
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
        members.forEach { member ->
            val nostrEventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = nostrEventId))
        }
        val chatRoom = ChatRoom(
            id = roomId, userPublicKey = publicKey, subject = "Treasury",
            description = SharedKeyDerivation.describe("Treasury."), mlsGroupState = "00",
        )
        db.chatRoomDao().upsert(chatRoom)
        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId, chatRoomId = roomId, coordinatorPublicKey = members.first(), userPublicKey = publicKey,
                threshold = threshold, participantCount = participants, stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[signerIndex], round1Random = "1".repeat(64), round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[signerIndex].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() },
            )
        )
        members.forEachIndexed { index, member ->
            db.dkgSessionDao().upsert(
                DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = hostKeys[index])
            )
        }
        val localChatRoom = db.chatRoomDao().findChatRoomById(roomId) ?: LocalChatRoom(chatRoom = chatRoom)
        return Device(publicKey, db, localChatRoom).also { devices += it }
    }

    private data class Queued(
        val id: HexKey, val publicKey: HexKey, val kind: Int,
        val tags: Array<Array<String>>, val content: String, val createdAt: Instant,
    ) {
        fun toEvent() = Event(id, publicKey, createdAt.epochSeconds, kind, tags, content, "")
    }

    private suspend fun outbox(device: Device): List<Queued> =
        device.db.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId = roomId, kinds = FrostSigningEvents.ALL.toList())
            .map { Queued(it.id, it.publicKey, it.kind, it.tags, it.content, it.createdAt) }
            .filter { it.publicKey == device.publicKey }
            .sortedWith(compareBy({ it.createdAt }, { it.kind }))

    private suspend fun deliver(to: Device, queued: Queued) {
        if (!to.received.add(queued.id)) return
        to.store(queued)
        FrostSigningManager.processSigningPayload(to.db, to.room, queued.toEvent(), to.publicKey)
    }

    private suspend fun pump(vararg between: Device) {
        var moved = true
        while (moved) {
            moved = false
            for (from in between) for (to in between) {
                if (from === to) continue
                outbox(from).forEach { queued ->
                    if (queued.id !in to.received) moved = true
                    deliver(to, queued)
                }
            }
        }
    }

    /** The group opens its wallet the way it would: a statement, proposed and signed by a quorum. */
    private suspend fun openWallet(proposer: Device, other: Device) {
        val template = GroupWalletStatementEvent.template(account)
        val session = FrostSigningManager.proposeSigning(
            proposer.db, proposer.room, proposer.publicKey, template.kind, template.tags, template.content,
        )
        pump(proposer, other)
        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)
        listOf(proposer, other).forEach {
            assertNotNull(GroupWalletManager.walletFor(it.db, roomId, chain), "the statement opens the wallet on every device")
        }
    }

    private val first = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
    private val second = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 1)
    private val change = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)

    private val funding = Transaction(
        version = 2,
        txIn = listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 0x42 })), 0), ByteVector.empty, 0xffffffffL)),
        txOut = listOf(TxOut(Satoshi(60_000), first.pubkeyScript), TxOut(Satoshi(40_000), second.pubkeyScript)),
        lockTime = 0,
    )

    private val elsewhere = Script.write(Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { 9 })).publicKey())).let(::ByteVector)

    private fun spendTx(fee: Long = 1_000, sequence: Long = 0xfffffffdL) = Transaction(
        version = 2,
        txIn = listOf(
            TxIn(OutPoint(funding, 0), ByteVector.empty, sequence),
            TxIn(OutPoint(funding, 1), ByteVector.empty, sequence),
        ),
        txOut = listOf(TxOut(Satoshi(70_000), elsewhere), TxOut(Satoshi(30_000 - fee), change.pubkeyScript)),
        lockTime = 0,
    )

    private fun psbt(tx: Transaction = spendTx(), changeAt: Map<Int, GroupWalletDerivation.Derived> = mapOf(1 to change)): Psbt =
        GroupSpend.psbtOf(tx, listOf(funding.txOut[0] to first, funding.txOut[1] to second), changeAt, account)

    /** A 30332 from member 0, built by hand, under [sessionId]. */
    private fun proposalOf(content: String, sessionId: String = Random.nextBytes(32).toHex(), walletTags: Array<Array<String>> = FrostSigningEvents.walletTags("regtest", 0)): Queued {
        val tags = FrostSigningEvents.assembleTags(sessionId = sessionId, dkgSessionId = ceremonyId) + walletTags
        val createdAt = 1_700_000_000L
        return Queued(
            id = EventHasher.hashId(members[0], createdAt, FrostSigningEvents.TRANSACTION_PROPOSAL, tags, content),
            publicKey = members[0], kind = FrostSigningEvents.TRANSACTION_PROPOSAL, tags = tags, content = content,
            createdAt = Instant.fromEpochSeconds(createdAt),
        )
    }

    private fun sessionIdOf(queued: Queued) = FrostSigningEvents.parseSessionId(queued.tags)!!

    // ---- The spend, end to end ------------------------------------------

    @Test
    fun `a quorum spends the group's coins, and both devices hold the transaction`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        openWallet(proposer, other)

        val session = FrostSigningManager.proposeSpend(proposer.db, proposer.room, proposer.publicKey, psbt(), chain)
        pump(proposer, other)

        assertEquals(FrostSigningSubject.TRANSACTION, other.session(session.id)?.subject)
        assertNull(other.transaction(session.id)?.signedTransaction, "nothing is signed before the member approves")

        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)

        listOf(proposer, other).forEach { device ->
            assertEquals(FrostSigningStage.COMPLETE, device.session(session.id)?.stage)
            val signed = Transaction.read(assertNotNull(device.transaction(session.id)?.signedTransaction))
            // The network's question, asked of each device's copy.
            signed.correctlySpends(
                funding.txOut.mapIndexed { i, out -> OutPoint(funding, i.toLong()) to out }.toMap(),
                ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS,
            )
            assertEquals(spendTx().txid, signed.txid, "the txid fixed at proposal is the one that was signed")
        }

        // Each input its own instance: every positive assertion above still
        // passes if two share a nonce, and what sharing costs is the share.
        val items = proposer.db.frostSigningSessionDao().getItems(session.id)
        assertEquals(listOf(0, 1), items.map { it.inputIndex })
        assertNotEquals(items[0].aggregatedNonce, items[1].aggregatedNonce)
        assertNotEquals(items[0].nonceRandom, items[1].nonceRandom)
    }

    // ---- What a member refuses ------------------------------------------

    @Test
    fun `a spend from a wallet the group never opened is refused`() = runBlocking {
        val other = device(members[1], signerIndex = 1)

        val proposal = proposalOf(GroupSpend.encode(psbt()))
        deliver(other, proposal)

        assertNull(other.session(sessionIdOf(proposal)))
    }

    @Test
    fun `each check refuses the proposal outright`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        openWallet(proposer, other)

        val stranger = GroupWalletDerivation.derive(GroupWalletDerivation.account(
            PrivateKey(ByteVector32(ByteArray(32) { 5 })).publicKey().toString(), chain), 0, 0)

        val refusals = mapOf(
            // 2. an input that is not the group's: the claim names /0/0, the coin is a stranger's.
            "a foreign input" to GroupSpend.psbtOf(spendTx(), listOf(TxOut(Satoshi(60_000), stranger.pubkeyScript) to first, funding.txOut[1] to second), mapOf(1 to change), account),
            // 3. a sighash that would let whoever holds the signatures redirect the money.
            "a sighash other than the default" to psbt().let { p ->
                p.copy(inputs = p.inputs.mapIndexed { i, input ->
                    if (i == 0) (input as fr.acinq.bitcoin.psbt.Input.WitnessInput.PartiallySignedWitnessInput).copy(sighashType = SigHash.SIGHASH_ALL) else input
                })
            },
            // 4. a spend that cannot be replaced.
            "a final sequence" to psbt(spendTx(sequence = 0xffffffffL)),
            // 5. an output called change that is not where its claim says.
            "a mislabelled change output" to psbt(changeAt = mapOf(0 to change)),
            // 6. outputs worth more than the coins: no fee, and no transaction.
            "a spend paying out more than it spends" to psbt(spendTx(fee = -1_000)),
        )

        refusals.forEach { (name, bad) ->
            val proposal = proposalOf(GroupSpend.encode(bad))
            deliver(other, proposal)
            assertNull(other.session(sessionIdOf(proposal)), "$name must be refused")
        }

        // And a wallet the proposal names but the room never opened.
        val otherAccount = proposalOf(GroupSpend.encode(psbt()), walletTags = FrostSigningEvents.walletTags("regtest", 1))
        deliver(other, otherAccount)
        assertNull(other.session(sessionIdOf(otherAccount)), "an account the group did not open")
    }

    @Test
    fun `a second proposal under the same id changes nothing`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        openWallet(proposer, other)

        val sessionId = Random.nextBytes(32).toHex()
        deliver(other, proposalOf(GroupSpend.encode(psbt()), sessionId = sessionId))
        val before = other.db.frostSigningSessionDao().getItems(sessionId).map { it.message }
        val psbtBefore = other.transaction(sessionId)?.psbt

        // Every item's seed is committed to its sighash. A different transaction
        // under the same id would have those seeds sign a second message.
        deliver(other, proposalOf(GroupSpend.encode(psbt(spendTx(fee = 2_000))), sessionId = sessionId).copy(id = Random.nextBytes(32).toHex()))

        assertEquals(before, other.db.frostSigningSessionDao().getItems(sessionId).map { it.message })
        assertEquals(psbtBefore, other.transaction(sessionId)?.psbt)
    }

    @Test
    fun `a feerate past the ceiling is refused, and one under it is not`() {
        // One coin large enough that the ceiling is reachable: about 110 vbytes
        // at 2,000 sats each is a fee of roughly 220,000 sats.
        val big = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 7 })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(10_000_000), first.pubkeyScript)), 0)
        fun spending(fee: Long) = GroupSpend.psbtOf(
            Transaction(2, listOf(TxIn(OutPoint(big, 0), ByteVector.empty, 0xfffffffdL)), listOf(TxOut(Satoshi(10_000_000 - fee), elsewhere)), 0),
            listOf(big.txOut[0] to first), emptyMap(), account,
        )

        kotlin.test.assertIs<GroupSpend.Result.Refused>(GroupSpend.check(spending(fee = 1_000_000), account))
        kotlin.test.assertIs<GroupSpend.Result.Ok>(GroupSpend.check(spending(fee = 5_000), account))
    }

    @Test
    fun `a member reads the spend as checked, and its coins are reserved until it fails`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        openWallet(proposer, other)

        val session = FrostSigningManager.proposeSpend(proposer.db, proposer.room, proposer.publicKey, psbt(), chain)
        pump(proposer, other)

        // What the approval screen draws, read off the member's own copy.
        val spend = assertNotNull(FrostSigningManager.checkedSpend(other.db, session.id))
        assertEquals(Satoshi(70_000), spend.amountSent)
        assertEquals(Satoshi(1_000), spend.fee)
        assertEquals(listOf(false, true), spend.outputs.map { it.isChange })

        val coins = setOf(OutPoint(funding, 0), OutPoint(funding, 1))
        assertEquals(coins, FrostSigningManager.reservedOutPoints(other.db, roomId))
        assertEquals(setOf(0L), FrostSigningManager.reservedChangeIndices(other.db, roomId, account), "and its change address")

        // Abandoned however it was -- the stage is all the reservation reads.
        other.db.frostSigningSessionDao().upsert(other.session(session.id)!!.copy(stage = FrostSigningStage.FAILED))
        assertEquals(emptySet(), FrostSigningManager.reservedOutPoints(other.db, roomId), "a failed spend releases its coins")
        assertEquals(emptySet(), FrostSigningManager.reservedChangeIndices(other.db, roomId, account), "and its change address")
    }

    /** The wallet as Electrum would report it holding [funding], with [changeWindow] change addresses watched and unused. */
    private fun fundedState(changeWindow: Int = 3): GroupWalletState {
        fun derived(branch: Long, index: Int) = GroupWalletDerivation.derive(account, branch, index.toLong())
        val receive = listOf(first, second).mapIndexed { index, derivation ->
            val meta = WalletState.AddressMeta.Derived(index)
            derivation.address(chain) to WalletState.AddressState(
                meta, alreadyUsed = true, utxos = listOf(WalletState.Utxo(funding.txid, index, 100, funding, meta)),
            )
        }.toMap()
        val change = (0 until changeWindow).associate { index ->
            derived(GroupWalletDerivation.CHANGE, index).address(chain) to
                WalletState.AddressState(WalletState.AddressMeta.Derived(index), alreadyUsed = false, utxos = emptyList())
        }
        return GroupWalletState(account, WalletState(receive), WalletState(change), tipHeight = 110)
    }

    @Test
    fun `an amount the form built is signed by the group, its change is the group's on every device, and it comes back`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        openWallet(proposer, other)

        // A first spend is open and holds change index 0, so the form pays past it.
        FrostSigningManager.proposeSpend(proposer.db, proposer.room, proposer.publicKey, psbt(), chain)
        pump(proposer, other)
        val heldChange = FrostSigningManager.reservedChangeIndices(proposer.db, roomId, account)
        assertEquals(setOf(0L), heldChange)

        // The second spends coins the first does not hold: a fresh funding of its own.
        val more = Transaction(
            2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 0x43 })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(80_000), first.pubkeyScript)), 0,
        )
        val wallet = fundedState().let { state ->
            val meta = WalletState.AddressMeta.Derived(0)
            val receive = state.receive.addresses + (first.address(chain) to WalletState.AddressState(
                meta, alreadyUsed = true, utxos = listOf(WalletState.Utxo(more.txid, 0, 100, more, meta)),
            ))
            state.copy(receive = WalletState(receive))
        }
        val reserved = FrostSigningManager.reservedOutPoints(proposer.db, roomId)
        val built = assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.send(wallet, ProposedSpend.addressOf(elsewhere, chain), Satoshi(30_000), 3, reserved, heldChange)
        )

        val session = FrostSigningManager.proposeSpend(proposer.db, proposer.room, proposer.publicKey, built.psbt, chain)
        pump(proposer, other)
        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)

        // On the device that did not build it: the change derives, so it is change.
        val seen = assertNotNull(FrostSigningManager.checkedSpend(other.db, session.id))
        val changeOutput = seen.outputs.single { it.isChange }
        assertEquals(1L, changeOutput.change!!.index, "past the index the open spend holds")
        assertEquals(built.change, changeOutput.txOut.amount)
        assertEquals(Satoshi(30_000), seen.amountSent)
        assertEquals(setOf(0L, 1L), FrostSigningManager.reservedChangeIndices(other.db, roomId, account))

        listOf(proposer, other).forEach { device ->
            assertEquals(FrostSigningStage.COMPLETE, device.session(session.id)?.stage)
            val signed = Transaction.read(assertNotNull(device.transaction(session.id)?.signedTransaction))
            signed.correctlySpends(mapOf(OutPoint(more, 0) to more.txOut[0]), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        // Confirmed, the change is a coin the wallet counts, on the branch it watches.
        val signed = Transaction.read(other.transaction(session.id)!!.signedTransaction!!)
        val changeMeta = WalletState.AddressMeta.Derived(1)
        val changeAddress = changeOutput.change!!.address(chain)
        val after = wallet.copy(
            receive = WalletState(wallet.receive.addresses - first.address(chain)),
            change = WalletState(wallet.change.addresses + (changeAddress to WalletState.AddressState(
                changeMeta, alreadyUsed = true, utxos = listOf(WalletState.Utxo(signed.txid, changeOutput.index, 111, signed, changeMeta)),
            ))),
        )
        assertEquals(changeOutput.change, after.derivationOf(changeOutput.txOut.publicKeyScript))
        assertEquals(Satoshi(40_000) + built.change!!, after.confirmedBalance, "the second funding coin, and the change")

        // The transcript says what left the group: the payment, never the change with it.
        FrostSigningManager.recordConfirmed(other.db, session.id)
        val lines = other.db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }
        val signedLine = lines.single { it.messageType == ChatMessage.TYPE_FROST_COMPLETE && it.content.startsWith("The group signed a transaction") }
        assertTrue(signedLine.content.startsWith("The group signed a transaction sending 30000 sats, with ${seen.fee.sat} sats in fees."), signedLine.content)
        val confirmedLine = lines.single { it.messageType == ChatMessage.TYPE_GROUP_TRANSACTION_CONFIRMED }
        assertEquals("The group's transaction confirmed: 30000 sats sent.", confirmedLine.content)
    }

    // ---- A guarded wallet -----------------------------------------------

    /**
     * Files the room's key state as a subgroup's, under a certificate granting
     * one guardian -- signed by a parent whose key the test holds, since
     * `certifies` asks only that the room it names as parent signed it.
     */
    private suspend fun fileGuardedParentage(device: Device): GroupWalletManager.GuardedOpening {
        val parentKey = PrivateKey(ByteVector32(ByteArray(32) { 0x0b }))
        val parentRoomId = parentKey.publicKey().xOnly().value.toString()
        val guardians = GuardianDerivation.grant(
            PrivateKey(ByteVector32(ByteArray(32) { 0x0c })).publicKey().toString(), parentRoomId, emptyList(), roomId,
        )
        val tags = com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = roomId, parentChatRoomId = parentRoomId, thresholdPublicKey = thresholdPublicKey,
            adminPublicKeys = members, name = "Treasury", guardians = guardians,
        )
        val id = EventHasher.hashId(parentRoomId, 1_700_000_000, 30329, tags, roomId)
        val certificate = Event(id, parentRoomId, 1_700_000_000, 30329, tags, roomId,
            fr.acinq.bitcoin.Crypto.signSchnorr(ByteVector32(id), parentKey, null).toString())

        device.db.groupKeyStateDao().upsert(
            com.machankura.dedun.compose.database.model.GroupKeyState(
                chatRoomId = roomId, dkgSessionId = ceremonyId, thresholdPublicKey = thresholdPublicKey,
                derivationPath = SharedKeyDerivation.formatPath(), announcedBy = roomId,
                announcedAt = Instant.fromEpochSeconds(1_700_000_000),
                parentChatRoomId = parentRoomId, birthCertificateJson = certificate.toJson(),
            )
        )
        return assertNotNull(GroupWalletManager.guardedAccountFor(device.db, roomId, chain))
    }

    @Test
    fun `a guarded wallet is opened by its quorum, and its own spends stay on the key path`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        val opening = fileGuardedParentage(proposer)
        fileGuardedParentage(other)

        val template = GroupWalletStatementEvent.template(opening.account, opening.certificate)
        val statement = FrostSigningManager.proposeSigning(proposer.db, proposer.room, proposer.publicKey, template.kind, template.tags, template.content)
        pump(proposer, other)
        FrostSigningManager.approve(other.db, other.room, statement.id)
        pump(proposer, other)

        val guarded = assertNotNull(GroupWalletManager.walletFor(other.db, roomId, chain)).account
        assertTrue(guarded.isGuarded)
        assertEquals(opening.account, guarded)

        val first = GroupWalletDerivation.derive(guarded, GroupWalletDerivation.RECEIVE, 0)
        val change = GroupWalletDerivation.derive(guarded, GroupWalletDerivation.CHANGE, 0)
        val funding = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 0x47 })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(60_000), first.pubkeyScript)), 0)
        val tx = Transaction(2, listOf(TxIn(OutPoint(funding, 0), ByteVector.empty, 0xfffffffdL)),
            listOf(TxOut(Satoshi(40_000), elsewhere), TxOut(Satoshi(19_000), change.pubkeyScript)), 0)
        val psbt = GroupSpend.psbtOf(tx, listOf(funding.txOut[0] to first), mapOf(1 to change), guarded)

        // The plain account's check refuses the guarded coin: same internal key, another output.
        assertIs<GroupSpend.Result.Refused>(GroupSpend.check(psbt, account))

        val session = FrostSigningManager.proposeSpend(proposer.db, proposer.room, proposer.publicKey, psbt, chain)
        pump(proposer, other)
        assertEquals(listOf(false, true), assertNotNull(FrostSigningManager.checkedSpend(other.db, session.id)).outputs.map { it.isChange })
        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)

        listOf(proposer, other).forEach { device ->
            assertEquals(FrostSigningStage.COMPLETE, device.session(session.id)?.stage)
            val signed = Transaction.read(assertNotNull(device.transaction(session.id)?.signedTransaction))
            signed.correctlySpends(mapOf(OutPoint(funding, 0) to funding.txOut[0]), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            assertEquals(1, signed.txIn[0].witness.stack.size, "a key-path spend: one signature, no script, no control block")
        }
    }
}
