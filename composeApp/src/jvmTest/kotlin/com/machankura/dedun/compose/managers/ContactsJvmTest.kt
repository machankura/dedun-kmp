package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.nostr.chronicle.ChronicleEvent
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.nostr.wallet.ContactClaimEvent
import com.machankura.dedun.compose.nostr.wallet.ContactDepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.ContactPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.Attribution
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.CoinOwnership
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every device reads about contacts, before any device proposes one: `docs/non-member-deposits.md`,
 * Phase 2. The records are signed here by hand, as an R2 device would have the group sign them.
 */
class ContactsJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun tearDown() = db.close()

    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private fun key(seed: String) = PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0'))))
    private val alice = key("a11ce").xOnlyPublicKey().value.toHex()
    private val bob = key("b0b").xOnlyPublicKey().value.toHex()

    private val mum = ContactIdentifier.Phone("+27821234567")
    private val mumId = mum.contactId(room)

    private var clock = 1_700_000_000L

    /** [template], signed by the room and on file, as a completed signing session leaves it. */
    private suspend fun signed(template: EventTemplate<*>): Event {
        GroupFixtures.seedRoom(db, room)
        val event = signedAsRoom(group, template.kind, template.tags, template.content, ++clock)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(event, chatRoomId = room, derivationPath = null, frostSigningSessionId = null))
        return event
    }

    private suspend fun recorded(name: String = "Mum") = signed(ContactRecordEvent.template(room, alice, name, mum))

    private suspend fun attributed(vararg attributions: Attribution) = signed(DepositAttributionEvent.template(chain, attributions.toList()))

    private suspend fun deposit(seed: Byte, sats: Long, branch: Long = GroupWalletDerivation.RECEIVE): DepositKey.Onchain {
        val outPoint = OutPoint(TxId(ByteVector32(ByteArray(32) { seed })), 0)
        DepositLedger.recordOnchain(db, room, chain, listOf(GroupOnchainWallets.OnchainDeposit(outPoint, Satoshi(sats), branch, confirmed = true)))
        return DepositKey.Onchain(outPoint.txid.toString(), 0)
    }

    @Test
    fun `a contact's deposits are theirs in the tallies, and a claim makes them the member's`(): Unit = runBlocking {
        recorded()
        val key = deposit(1, 20_000)
        deposit(2, 5_000)
        attributed(Attribution(key, mumId, 20_000, contact = true))

        val before = DepositLedger.tallies(db, room, chain)
        assertEquals(20_000_000, before.contacts.getValue(mumId).onchainMsat)
        assertFalse(mumId in before.members)
        assertEquals(5_000_000, before.unattributedMsat)
        assertEquals(before.totalMsat, before.members.values.sumOf { it.depositedMsat } + before.contacts.values.sumOf { it.depositedMsat } + before.unattributedMsat)

        signed(ContactClaimEvent.template(mumId, bob))
        val after = DepositLedger.tallies(db, room, chain)
        assertEquals(20_000_000, after.members.getValue(bob).onchainMsat)
        assertTrue(after.contacts.isEmpty())
        assertEquals(mumId, DepositLedger.depositsIn(db, room, chain, resolveClaims = false).single { it.depositKey == key.text }.member)

        signed(ContactClaimEvent.template(mumId, null))
        assertEquals(20_000_000, DepositLedger.tallies(db, room, chain).contacts.getValue(mumId).onchainMsat)
    }

    @Test
    fun `an attribution to a contact the group has no record of is ignored`(): Unit = runBlocking {
        GroupFixtures.seedRoom(db, room)
        val key = deposit(1, 20_000)
        attributed(Attribution(key, mumId, 20_000, contact = true))
        val tallies = DepositLedger.tallies(db, room, chain)
        assertTrue(tallies.contacts.isEmpty())
        assertEquals(20_000_000, tallies.unattributedMsat)
        assertNull(DepositLedger.depositsIn(db, room, chain).single().member)

        recorded()
        assertEquals(20_000_000, DepositLedger.tallies(db, room, chain).contacts.getValue(mumId).onchainMsat)
    }

    @Test
    fun `amendments name and close a contact, and a claim is the newest`(): Unit = runBlocking {
        recorded()
        signed(ContactAmendmentEvent.template(mumId, ContactAmendmentEvent.Change.Rename("Gogo")))
        signed(ContactAmendmentEvent.template(mumId, ContactAmendmentEvent.Change.Close))
        val contact = Contacts.contactOf(db, room, mumId)!!
        assertEquals("Gogo", contact.name)
        assertTrue(contact.closed)
        assertFalse(contact.isOpen)
        assertEquals(Party.Contact(mumId), contact.party)

        signed(ContactClaimEvent.template(mumId, alice))
        signed(ContactClaimEvent.template(mumId, bob))
        assertEquals(Party.Member(bob), Contacts.contactOf(db, room, mumId)!!.party)
    }

    @Test
    fun `a contact's coin is theirs by branch or by attribution, and a claimed contact's is the member's`(): Unit = runBlocking {
        recorded()
        val statement = signed(ContactDepositorStatementEvent.template(room, mumId, chain))
        val branch = ContactDepositorStatementEvent.verify(statement, room)!!.branch
        assertEquals(branch, Depositors.branchesIn(db, room, chain)[mumId])

        val onBranch = deposit(1, 20_000, branch)
        assertEquals(DepositRow.BY_BRANCH, db.depositDao().get(room, onBranch.text)?.attributedBy)
        assertEquals(mumId, db.depositDao().get(room, onBranch.text)?.member)

        val legacy = deposit(2, 7_000)
        attributed(Attribution(legacy, mumId, 7_000, contact = true))

        var records = MemberBalances.recordsOf(db, room, chain)
        fun owner(key: DepositKey.Onchain, branchOf: Long, sats: Long) =
            CoinOwnership.ownerOf(OutPoint(TxId(key.txid), key.vout), branchOf, sats, records)
        assertEquals(CoinOwnership.Owner.Member(mumId), owner(onBranch, branch, 20_000))
        assertEquals(CoinOwnership.Owner.Member(mumId), owner(legacy, GroupWalletDerivation.RECEIVE, 7_000))

        signed(ContactClaimEvent.template(mumId, bob))
        records = MemberBalances.recordsOf(db, room, chain)
        assertEquals(CoinOwnership.Owner.Member(bob), owner(onBranch, branch, 20_000))
        assertEquals(CoinOwnership.Owner.Member(bob), owner(legacy, GroupWalletDerivation.RECEIVE, 7_000))
    }

    @Test
    fun `a contact's newest destination is where a payout goes, until a claim or a removal`(): Unit = runBlocking {
        recorded()
        val address = Bitcoin.computeP2WpkhAddress(key("ad").publicKey(), chain.chainHash)
        signed(ContactPayoutDestinationEvent.template(mumId, chain, LightningAddress("mum", "example.com"), null))
        val newest = signed(ContactPayoutDestinationEvent.template(mumId, chain, null, address))

        val destination = GroupPayoutDestinations.payoutDestinationOf(db, room, chain, mumId)!!
        assertEquals(Party.Contact(mumId), destination.payee)
        assertEquals(newest.id, destination.event.id)
        assertEquals(address, destination.onchainAddress)
        assertNull(destination.lightningAddress)
        assertEquals(2, GroupPayoutDestinations.everSignedIn(db, room, chain).count { it.payee == Party.Contact(mumId) })
        assertTrue(mumId in MemberBalances.payoutContextOf(db, room, chain).scripts)

        signed(ContactPayoutDestinationEvent.removedTemplate(mumId, chain))
        assertNull(GroupPayoutDestinations.payoutDestinationOf(db, room, chain, mumId))

        signed(ContactPayoutDestinationEvent.template(mumId, chain, null, address))
        signed(ContactClaimEvent.template(mumId, bob))
        assertNull(GroupPayoutDestinations.payoutDestinationOf(db, room, chain, mumId), "a claimed contact is paid as the member")
    }

    @Test
    fun `a destination for a contact with no record pays nobody`(): Unit = runBlocking {
        GroupFixtures.seedRoom(db, room)
        signed(ContactPayoutDestinationEvent.template(mumId, chain, LightningAddress("mum", "example.com"), null))
        assertNull(GroupPayoutDestinations.payoutDestinationOf(db, room, chain, mumId))
    }

    @Test
    fun `a contact's balance is a contact's, never a former member's`(): Unit = runBlocking {
        recorded()
        signed(ContactPayoutDestinationEvent.template(mumId, chain, LightningAddress("mum", "example.com"), null))
        val balance = MemberBalances.balancesIn(db, room, chain, state = null).members.getValue(mumId)
        assertTrue(balance.isContact)
        assertFalse(balance.isFormerMember)
        assertEquals(Party.Contact(mumId), balance.party)
    }

    @Test
    fun `everyone reads contacts only once every current member has said so`(): Unit = runBlocking {
        GroupFixtures.seedRoom(db, room)
        listOf(alice, bob).forEach { GroupFixtures.seedProfile(db, it) }
        db.participantDao().upsert(listOf(alice, bob).map { Participant(participantPublicKey = it, chatRoomId = room, relayHint = null) })
        assertFalse(Contacts.everyoneReads(db, room))

        assertTrue(Contacts.announceIfNeeded(db, room, alice))
        assertFalse(Contacts.announceIfNeeded(db, room, alice), "once is enough")
        assertEquals(listOf(bob), Contacts.waitingFor(db, room))

        Contacts.announce(db, room, bob)
        assertTrue(Contacts.everyoneReads(db, room))

        val carol = key("ca201").xOnlyPublicKey().value.toHex()
        GroupFixtures.seedProfile(db, carol)
        db.participantDao().upsert(listOf(Participant(participantPublicKey = carol, chatRoomId = room, relayHint = null)))
        assertEquals(listOf(carol), Contacts.waitingFor(db, room))
    }

    @Test
    fun `a late joiner's chronicle carries every contact kind, ahead of what names a contact`() {
        val order = ChronicleEvent.KINDS.inApplyOrder(
            listOf(
                DepositAttributionEvent.KIND, ContactDepositorStatementEvent.KIND, ContactPayoutDestinationEvent.KIND,
                ContactClaimEvent.KIND, ContactAmendmentEvent.KIND, ContactRecordEvent.KIND, DepositorStatementEvent.KIND,
            ).mapIndexed { i, kind -> Event("%064x".format(i), room, 1, kind, emptyArray(), "", "") }
        ).map { it.kind }
        Contacts.SIGNED_KINDS.forEach { assertTrue(ChronicleEvent.isChroniclable(it), "kind $it") }
        assertEquals(ContactRecordEvent.KIND, order.first())
        assertTrue(order.indexOf(ContactClaimEvent.KIND) < order.indexOf(DepositAttributionEvent.KIND))
        assertTrue(order.indexOf(ContactDepositorStatementEvent.KIND) < order.indexOf(DepositAttributionEvent.KIND))
    }
}
