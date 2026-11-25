package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.nostr.wallet.ContactClaimEvent
import com.machankura.dedun.compose.nostr.wallet.ContactPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
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
 * The group saying a contact was one of its members, and saying so again to correct it:
 * `docs/non-member-deposits.md`, D8 and Phase 5.
 */
class ContactClaimJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun tearDown() = db.close()

    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private fun key(seed: String) = PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0'))))
    private val alice = key("a11ce").xOnlyPublicKey().value.toHex()
    private val bobKey = key("b0b")
    private val bob = bobKey.xOnlyPublicKey().value.toHex()
    private val carol = key("ca201").xOnlyPublicKey().value.toHex()

    private val mumByPhone = ContactIdentifier.Phone("+27821234567")
    private val mumByEmail = ContactIdentifier.Email("mum@example.com")

    private var clock = 1_700_000_000L

    private suspend fun signed(template: EventTemplate<*>) {
        val event = signedAsRoom(group, template.kind, template.tags, template.content, ++clock)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(event, chatRoomId = room, derivationPath = null, frostSigningSessionId = null))
    }

    private suspend fun setUp(): Pair<String, String> {
        GroupFixtures.seedRoom(db, room)
        listOf(alice, bob, carol).forEach { GroupFixtures.seedProfile(db, it) }
        db.participantDao().upsert(listOf(alice, bob, carol).map { Participant(participantPublicKey = it, chatRoomId = room, relayHint = null) })
        signed(ContactRecordEvent.template(room, alice, "Mum", mumByPhone))
        signed(ContactRecordEvent.template(room, alice, "Mum, by email", mumByEmail))
        return mumByPhone.contactId(room) to mumByEmail.contactId(room)
    }

    private suspend fun deposit(seed: Byte, sats: Long, to: Party) {
        val outPoint = OutPoint(TxId(ByteVector32(ByteArray(32) { seed })), 0)
        DepositLedger.recordOnchain(db, room, chain, listOf(GroupOnchainWallets.OnchainDeposit(outPoint, Satoshi(sats), GroupWalletDerivation.RECEIVE, confirmed = true)))
        signed(DepositLedger.attributionTemplate(db, room, chain, mapOf("${outPoint.txid}:0" to to)))
    }

    @Test
    fun `one member can be two contacts, and holds both tallies`(): Unit = runBlocking {
        val (phone, email) = setUp()
        deposit(1, 20_000, Party.Contact(phone))
        deposit(2, 7_000, Party.Contact(email))
        deposit(3, 1_000, Party.Member(bob))

        signed(ContactClaimEvent.template(phone, bob))
        signed(ContactClaimEvent.template(email, bob))
        val tallies = DepositLedger.tallies(db, room, chain)
        assertEquals(28_000_000, tallies.members.getValue(bob).depositedMsat)
        assertTrue(tallies.contacts.isEmpty())
    }

    @Test
    fun `a correction moves a contact's deposits to another member, and none gives them back`(): Unit = runBlocking {
        val (phone, _) = setUp()
        deposit(1, 20_000, Party.Contact(phone))
        signed(ContactClaimEvent.template(phone, bob))
        signed(ContactClaimEvent.template(phone, carol))
        var tallies = DepositLedger.tallies(db, room, chain)
        assertNull(tallies.members[bob])
        assertEquals(20_000_000, tallies.members.getValue(carol).depositedMsat)

        signed(ContactClaimEvent.template(phone, null))
        tallies = DepositLedger.tallies(db, room, chain)
        assertNull(tallies.members[carol])
        assertEquals(20_000_000, tallies.contacts.getValue(phone).depositedMsat)
    }

    @Test
    fun `once claimed, a contact is paid as the member, at the member's own destination`(): Unit = runBlocking {
        val (phone, _) = setUp()
        deposit(1, 20_000, Party.Contact(phone))
        signed(ContactPayoutDestinationEvent.template(phone, chain, LightningAddress("mum", "example.com"), null))
        assertEquals(Party.Contact(phone), GroupPayoutDestinations.payoutDestinationOf(db, room, chain, phone)?.payee)

        val own = GroupPayoutDestinationEvent.verified(
            GroupPayoutDestinationEvent.build(bobKey, room, chain, LightningAddress("bob", "example.com"), createdAt = 1_700_000_000)
        ) as GroupPayoutDestination
        GroupPayoutDestinations.publish(db, room, own.event)
        signed(ContactClaimEvent.template(phone, bob))

        assertNull(GroupPayoutDestinations.payoutDestinationOf(db, room, chain, phone))
        val balances = MemberBalances.balancesIn(db, room, chain, state = null).members
        assertFalse(phone in balances, "the contact has no balance of their own once claimed")
        assertEquals(LightningAddress("bob", "example.com"), balances.getValue(bob).destination?.lightningAddress)
    }
}
