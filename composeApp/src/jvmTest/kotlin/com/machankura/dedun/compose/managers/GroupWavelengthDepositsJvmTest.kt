package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.wallet.wavelength.group.ArkScripts
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkDerivation
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Money coming in by boarding: an operator's address is only believed if a device recomputes it as the group's. */
class GroupWavelengthDepositsJvmTest {
    private val fixture = GroupLightningFixture().also { f ->
        val inherited = f.dispatch
        f.dispatch = { to, event ->
            when (event.kind) {
                GroupWavelengthEvents.DEPOSIT_REQUEST, GroupWavelengthEvents.DEPOSIT_ANSWER -> GroupWavelengthDeposits.process(to.db, to.room, event)
                else -> inherited(to, event)
            }
        }
    }
    private val arkOperator = "02" + "ab".repeat(32)
    private val key get() = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0)

    @AfterTest
    fun close() = fixture.close()

    private suspend fun elected(): Pair<List<GroupLightningFixture.Device>, GroupWavelengthWalletManager.Wallet> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, arkOperator)
        fixture.signSession(all, all[0], session.id)
        val nominated = GroupWavelengthOperatorManager.nominateSelf(all[0].db, all[0].room, all[0].publicKey, fixture.chain)
        fixture.signSession(all, all[0], nominated.id)
        return all to assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
    }

    private fun boardingScript(wallet: GroupWavelengthWalletManager.Wallet, index: Long): ByteVector =
        ArkScripts.standardScript(GroupArkCoSigner.keysOf(wallet).key(GroupArkDerivation.Slot.BOARDING, 0, index), PublicKey.fromHex(arkOperator), wallet.statement.policy.boardingDelay)

    private fun addressOf(script: ByteVector): String =
        Bitcoin.addressFromPublicKeyScript(fixture.chain.chainHash, Script.parse(script)).right!!

    private suspend fun requested(device: GroupLightningFixture.Device, all: List<GroupLightningFixture.Device>, wallet: GroupWavelengthWalletManager.Wallet): String {
        val requestId = GroupWavelengthDeposits.request(device.db, device.room, device.publicKey, wallet)
        // The depositor statement isn't what's being tested: every device files what the requester did.
        all.filter { it !== device }.forEach { it.db.groupArkDao().insertDepositAddress(GroupArkDepositAddress(key, requestId, device.publicKey.lowercase(), Clock.System.now())) }
        return requestId
    }

    @Test
    fun `an address the group's key makes is believed on every device, as that member's`() = runBlocking<Unit> {
        val (all, wallet) = elected()
        val requestId = requested(all[1], all, wallet)
        val script = boardingScript(wallet, 3)
        GroupWavelengthDeposits.answerOutstanding(all[0].db, all[0].room, all[0].publicKey, wallet, 1) { addressOf(script) }
        fixture.pump(all, setOf(GroupWavelengthEvents.DEPOSIT_ANSWER))
        all.forEach { d ->
            val row = d.db.groupArkDao().depositAddresses(key).single { it.requestId == requestId }
            assertEquals(script.toHex(), row.script, "device ${d.index}")
        }
    }

    @Test
    fun `an address the operator made up isn't, and every device says so`() = runBlocking<Unit> {
        val (all, wallet) = elected()
        val requestId = requested(all[1], all, wallet)
        val theirs = ByteVector(Script.write(Script.pay2tr(PublicKey.fromHex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798").xOnly(), fr.acinq.bitcoin.Crypto.TaprootTweak.KeyPathTweak)))
        GroupWavelengthDeposits.answerOutstanding(all[0].db, all[0].room, all[0].publicKey, wallet, 1) { addressOf(theirs) }
        fixture.pump(all, setOf(GroupWavelengthEvents.DEPOSIT_ANSWER))
        all.forEach { d ->
            assertNull(d.db.groupArkDao().depositAddresses(key).single { it.requestId == requestId }.address, "device ${d.index}")
            assertTrue(fixture.lines(d, ChatMessage.TYPE_WAVELENGTH_REFUSED).any { "can't confirm is the group's" in it.content })
        }
    }

    @Test
    fun `one address can't be two members'`() = runBlocking<Unit> {
        val (all, wallet) = elected()
        val script = boardingScript(wallet, 4)
        val first = requested(all[1], all, wallet)
        GroupWavelengthDeposits.answerOutstanding(all[0].db, all[0].room, all[0].publicKey, wallet, 1) { addressOf(script) }
        val second = requested(all[2], all, wallet)
        GroupWavelengthDeposits.answerOutstanding(all[0].db, all[0].room, all[0].publicKey, wallet, 1) { addressOf(script) }
        fixture.pump(all, setOf(GroupWavelengthEvents.DEPOSIT_ANSWER))
        val rows = all[3].db.groupArkDao().depositAddresses(key)
        assertEquals(script.toHex(), rows.single { it.requestId == first }.script)
        assertNull(rows.single { it.requestId == second }.script)
    }

    @Test
    fun `only the operator's answer counts, and only a depositor's request does`() = runBlocking<Unit> {
        val (all, wallet) = elected()
        val requestId = GroupWavelengthDeposits.request(all[1].db, all[1].room, all[1].publicKey, wallet)
        fixture.pump(all, setOf(GroupWavelengthEvents.DEPOSIT_REQUEST))
        assertTrue(all[2].db.groupArkDao().depositAddresses(key).isEmpty(), "they haven't started depositing")
        GroupWavelengthDeposits.answerOutstanding(all[2].db, all[2].room, all[2].publicKey, wallet, 1) { error("nothing outstanding on this device") }
        all[1].db.groupArkDao().insertDepositAddress(GroupArkDepositAddress(key, "other", all[1].publicKey.lowercase(), Clock.System.now()))
        assertNull(all[1].db.groupArkDao().depositAddresses(key).single { it.requestId == requestId }.address)
    }

    @Test
    fun `a boarded coin is a deposit once its round happens, the address's member's, or nobody's yet`() = runBlocking<Unit> {
        val (all, wallet) = elected()
        val device = all[0]
        val script = boardingScript(wallet, 5)
        val dao = device.db.groupArkDao()
        dao.insertDepositAddress(GroupArkDepositAddress(key, "r", all[1].publicKey.lowercase(), Clock.System.now(), addressOf(script), script.toHex()))
        val txid = "ab".repeat(32)
        val other = "cd".repeat(32)
        dao.upsertBoarded(GroupArkBoarded(key, "$txid:0", "commitment", 20_000, script.toHex()))
        dao.upsertBoarded(GroupArkBoarded(key, "$other:1", "commitment", 7_000, "51".repeat(34)))
        val chain = GroupWalletStatementEvent.chainName(fixture.chain)
        assertTrue(device.db.depositDao().inRoom(fixture.roomId, chain).isEmpty(), "not before it has happened")
        GroupWavelengthDeposits.roundHappened(device.db, fixture.roomId, chain, key, "commitment")
        val rows = device.db.depositDao().inRoom(fixture.roomId, chain)
        val mine = rows.single { it.depositKey == "$txid:0" }
        assertEquals(DepositRow.WAVELENGTH, mine.source)
        assertEquals(20_000_000, mine.amountMsat)
        assertEquals(all[1].publicKey.lowercase(), mine.member)
        assertNull(rows.single { it.depositKey == "$other:1" }.member, "unattributed, as elsewhere")
        val tallies = DepositLedger.tallies(device.db, fixture.roomId, fixture.chain)
        assertEquals(20_000_000, tallies.members.getValue(all[1].publicKey.lowercase()).wavelengthMsat)
        assertEquals(7_000_000, tallies.unattributedMsat)
        assertTrue(dao.boardedIn(key, "commitment").isEmpty())
    }
}
