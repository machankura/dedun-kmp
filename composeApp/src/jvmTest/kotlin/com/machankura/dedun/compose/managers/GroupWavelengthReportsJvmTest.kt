package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthStopped
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a member's device makes of what the operator says it holds. */
class GroupWavelengthReportsJvmTest {
    private val fixture = GroupLightningFixture()

    @AfterTest
    fun close() = fixture.close()

    private fun coin(outpoint: String, sat: Long) = GroupWavelengthSnapshot.Coin(outpoint, sat, "live")
    private fun snapshot(vararg coins: GroupWavelengthSnapshot.Coin) = GroupWavelengthSnapshot("w", 1, 100, 500, coins.sumOf { it.amountSat }, coins = coins.toList())
    private fun settled(outpoint: String, sat: Long) = GroupArkVtxo("w", outpoint, sat, settled = true)

    @Test
    fun `the same coins for the same amounts match`() {
        assertEquals(GroupWavelengthReports.Agreement.MATCHES, GroupWavelengthReports.compare(snapshot(coin("a:0", 10)), listOf(settled("a:0", 10))).agreement)
    }

    @Test
    fun `an operator claiming more than a device signed for differs, and says what`() {
        val comparison = GroupWavelengthReports.compare(snapshot(coin("a:0", 10), coin("b:0", 20)), listOf(settled("a:0", 10)))
        assertEquals(GroupWavelengthReports.Agreement.DIFFERS, comparison.agreement)
        assertEquals(listOf("b:0"), comparison.onlyTheOperator.map { it.outpoint })
    }

    @Test
    fun `an operator leaving out a coin differs, and a wrong amount differs`() {
        assertEquals(listOf("a:0"), GroupWavelengthReports.compare(snapshot(), listOf(settled("a:0", 10))).onlyThisDevice.map { it.outpoint })
        val amounts = GroupWavelengthReports.compare(snapshot(coin("a:0", 11)), listOf(settled("a:0", 10)))
        assertEquals(GroupWavelengthReports.Agreement.DIFFERS, amounts.agreement)
        assertEquals(listOf("a:0"), amounts.amountsDiffer)
    }

    @Test
    fun `a device with no record can't confirm what the operator says, rather than disagreeing`() {
        assertEquals(GroupWavelengthReports.Agreement.CANNOT_CONFIRM, GroupWavelengthReports.compare(snapshot(coin("a:0", 10)), emptyList()).agreement)
        assertEquals(GroupWavelengthReports.Agreement.MATCHES, GroupWavelengthReports.compare(snapshot(), emptyList()).agreement, "nothing on either side is agreement")
    }

    @Test
    fun `a coin still pending on this device isn't one it doesn't know of, and a spent one is`() {
        val pending = GroupArkVtxo("w", "a:0", 10)
        assertEquals(GroupWavelengthReports.Agreement.MATCHES, GroupWavelengthReports.compare(snapshot(coin("a:0", 10)), listOf(pending)).agreement)
        val spent = GroupArkVtxo("w", "a:0", 10, settled = true, spent = true)
        assertEquals(GroupWavelengthReports.Agreement.DIFFERS, GroupWavelengthReports.compare(snapshot(coin("a:0", 10)), listOf(spent, settled("c:0", 1))).agreement)
    }

    private suspend fun elected(): List<GroupLightningFixture.Device> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, "02" + "ab".repeat(32))
        fixture.signSession(all, all[0], session.id)
        val nominated = GroupWavelengthOperatorManager.nominateSelf(all[0].db, all[0].room, all[0].publicKey, fixture.chain)
        fixture.signSession(all, all[0], nominated.id)
        return all
    }

    private val key get() = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0)

    private suspend fun said(all: List<GroupLightningFixture.Device>, by: GroupLightningFixture.Device, snapshot: GroupWavelengthSnapshot) =
        GroupWavelengthReports.receive(all[1].db, fixture.roomId, by.publicKey, snapshot)

    @Test
    fun `a snapshot is believed to be the operator's only if the operator of its term sent it`() = runBlocking<Unit> {
        val all = elected()
        said(all, all[2], GroupWavelengthSnapshot(key, 1, 100, 500, 999))
        assertNull(all[1].db.groupArkDao().snapshot(key), "another member can't speak for the wallet")
        said(all, all[0], GroupWavelengthSnapshot(key, 7, 100, 500, 999))
        assertNull(all[1].db.groupArkDao().snapshot(key), "nor can the operator for a term that isn't theirs")
        said(all, all[0], GroupWavelengthSnapshot(key, 1, 100, 500, 10))
        assertNotNull(all[1].db.groupArkDao().snapshot(key))
    }

    @Test
    fun `an older snapshot doesn't replace a newer, nor one for another room's wallet, nor one too big to be real`() = runBlocking<Unit> {
        val all = elected()
        said(all, all[0], GroupWavelengthSnapshot(key, 1, 200, 500, 10))
        said(all, all[0], GroupWavelengthSnapshot(key, 1, 100, 500, 999))
        assertTrue("\"confirmedSat\":10" in all[1].db.groupArkDao().snapshot(key)!!.content)
        said(all, all[0], GroupWavelengthSnapshot("wavelength:elsewhere:signet:0", 1, 300, 500, 999))
        assertNull(all[1].db.groupArkDao().snapshot("wavelength:elsewhere:signet:0"))
        val huge = List(GroupWavelengthSnapshot.MAX_COINS + 1) { coin("c$it:0", 1) }
        said(all, all[0], GroupWavelengthSnapshot(key, 1, 400, 500, 999, coins = huge))
        assertTrue("\"confirmedSat\":10" in all[1].db.groupArkDao().snapshot(key)!!.content)
    }

    @Test
    fun `a malformed message is dropped, and an operator stopping writes a line saying what the next can't pick up`() = runBlocking<Unit> {
        val all = elected()
        fixture.dispatch.let { inherited ->
            fixture.dispatch = { to, event ->
                when (event.kind) {
                    GroupWavelengthEvents.SNAPSHOT, GroupWavelengthEvents.STOPPED -> GroupWavelengthReports.process(to.db, to.room, event)
                    else -> inherited(to, event)
                }
            }
        }
        GroupLightningWalletManager.queue(all[0].db, all[0].room, all[0].publicKey, GroupWavelengthEvents.SNAPSHOT, "not json")
        GroupLightningWalletManager.queue(all[0].db, all[0].room, all[0].publicKey, GroupWavelengthEvents.STOPPED, CommonJson.encodeToString(GroupWavelengthStopped.serializer(), GroupWavelengthStopped(key, 1, exitsUnderWay = 2)))
        GroupLightningWalletManager.queue(all[2].db, all[2].room, all[2].publicKey, GroupWavelengthEvents.STOPPED, CommonJson.encodeToString(GroupWavelengthStopped.serializer(), GroupWavelengthStopped(key, 1)))
        fixture.pump(all, setOf(GroupWavelengthEvents.SNAPSHOT, GroupWavelengthEvents.STOPPED))
        val lines = fixture.lines(all[1], ChatMessage.TYPE_WAVELENGTH_OPERATOR_STOPPED)
        assertEquals(1, lines.size, "only the operator's")
        assertTrue("2 exits are under way" in lines.single().content, lines.single().content)
    }

    @Test
    fun `a wallet's key reads back as its chain and number, for this room alone`() {
        assertEquals(fixture.chain to 3L, GroupArkCoSigner.parseWalletKey(fixture.roomId, GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 3)))
        assertNull(GroupArkCoSigner.parseWalletKey("other", GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 3)))
        assertNull(GroupArkCoSigner.parseWalletKey(fixture.roomId, GroupArkCoSigner.treeKey(GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 3), 1)))
    }
}
