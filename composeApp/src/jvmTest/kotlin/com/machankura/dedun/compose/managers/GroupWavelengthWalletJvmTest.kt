package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkAuth
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 4 of `docs/group-wavelength-wallet.md`: the ceremony's purpose, the statement and the seed. */
class GroupWavelengthWalletJvmTest {
    private val fixture = GroupLightningFixture()
    private val arkOperator = "02" + "ab".repeat(32)

    @AfterTest
    fun close() = fixture.close()

    private suspend fun GroupLightningFixture.openWallet(all: List<GroupLightningFixture.Device>, proposer: GroupLightningFixture.Device = all[0]) {
        val session = GroupWavelengthWalletManager.proposeWallet(proposer.db, proposer.room, proposer.publicKey, proposer.privateKey, chain, arkOperator)
        signSession(all, proposer, session.id)
    }

    @Test
    fun `the group opens a wallet, and every member holds its seed and agrees on its manifest`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        fixture.openWallet(all)

        val wallets = all.map { assertNotNull(GroupWavelengthWalletManager.current(it.db, fixture.roomId, fixture.chain), "device ${it.index}") }
        val statement = wallets.first().statement
        assertEquals(1, wallets.map { it.statement.manifestHash }.toSet().size)
        assertEquals(1, wallets.map { it.statement.identity }.toSet().size)
        assertEquals(arkOperator, statement.arkOperatorKey)
        all.forEach {
            val seed = assertNotNull(it.db.groupWavelengthSeedDao().get(fixture.roomId, statement.identity))
            assertEquals(statement.identity, GroupArkAuth.of(ByteVector32(seed.seed), fixture.chain).identity().toString())
        }
    }

    @Test
    fun `a key made for one wallet can't open the other`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        all.forEach { d ->
            assertNull(GroupLightningWalletManager.unusedKey(d.db, fixture.roomId, fixture.chain), "a Lightning wallet must not claim this key")
            assertNotNull(GroupWavelengthWalletManager.unusedKey(d.db, fixture.roomId))
        }

        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_LIGHTNING)
        all.forEach { d ->
            val forLightning = assertNotNull(GroupLightningWalletManager.unusedKey(d.db, fixture.roomId, fixture.chain))
            assertEquals(IcebergKeySession.PURPOSE_LIGHTNING, forLightning.purpose)
            val forWavelength = assertNotNull(GroupWavelengthWalletManager.unusedKey(d.db, fixture.roomId))
            assertEquals(IcebergKeySession.PURPOSE_WAVELENGTH, forWavelength.purpose)
        }
    }

    @Test
    fun `a proposal that names no purpose is the Lightning wallet's, as every old one was`() = runBlocking<Unit> {
        val all = fixture.group()
        val proposed = fixture.runCeremony(all)
        all.forEach { assertEquals(IcebergKeySession.PURPOSE_LIGHTNING, it.ceremony(proposed.id)?.purpose) }
    }

    @Test
    fun `a ceremony's lines name the wallet it is for`() = runBlocking<Unit> {
        val all = fixture.group()
        val proposed = fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        assertEquals("Wavelength wallet", all[1].ceremony(proposed.id)?.walletName)
        assertEquals(IcebergRitualStage.COMPLETE, all[1].ceremony(proposed.id)?.stage)
    }

    @Test
    fun `a seed that does not produce the identity it claims is dropped`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, arkOperator)
        val queued = fixture.outbox(all[0], setOf(GroupWavelengthEvents.AUTH_SEED)).single()
        val message = CommonJson.decodeFromString(GroupWavelengthWalletManager.SeedMessage.serializer(), queued.content)
        val forged = message.copy(identity = "02" + "cd".repeat(32))
        val event = Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, CommonJson.encodeToString(GroupWavelengthWalletManager.SeedMessage.serializer(), forged), "")
        GroupWavelengthWalletManager.receiveSeed(all[1].db, all[1].room, event, all[1].publicKey, all[1].privateKey)
        assertNull(all[1].db.groupWavelengthSeedDao().get(fixture.roomId, forged.identity))
    }

    @Test
    fun `a participant who holds no seed for the statement does not believe it`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, arkOperator)
        // Device 1 hears everything but the seed.
        fixture.pump(all, FrostSigningEvents.ALL)
        all.filter { it !== all[0] }.forEach { FrostSigningManager.approve(it.db, it.room, session.id) }
        fixture.pump(all, FrostSigningEvents.ALL)
        assertNull(GroupWavelengthWalletManager.current(all[1].db, fixture.roomId, fixture.chain))
        assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
    }

    @Test
    fun `a statement with a manifest the keys don't make is not believed`() = runBlocking<Unit> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        fixture.openWallet(all)
        val good = assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain)).statement
        val forged = GroupWavelengthWalletStatementEvent.template(good.copy(wallet = good.wallet, manifestHash = "00".repeat(32)))
        val event = fixture.fileSignedAsRoom(all, forged.kind, forged.tags, forged.content, createdAt = 4_000_000_000)
        assertFalse(event.id.isEmpty())
        all.forEach { d ->
            val row = assertNotNull(d.db.groupSignedEventDao().getByChatRoomIdAndKind(fixture.roomId, forged.kind).firstOrNull { it.id == event.id })
            assertNull(GroupWavelengthWalletManager.verify(d.db, fixture.roomId, row))
        }
        assertEquals(good.manifestHash, assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain)).statement.manifestHash, "the group's own still stands")
    }

    @Test
    fun `mainnet is refused in code, and the gate is shut`() = runBlocking<Unit> {
        assertFalse(GroupWavelengthRollout.ENABLED)
        assertFalse(GroupWavelengthRollout.allows(Chain.Mainnet))
        assertTrue(GroupWavelengthRollout.allows(Chain.Signet))
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val refused = runCatching { GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, Chain.Mainnet, arkOperator) }
        assertTrue(refused.isFailure)
    }

    @Test
    fun `a member proposing it is told what the wallet asks of the group`() {
        val template = GroupWavelengthWalletStatementEvent.template(
            GroupWavelengthWalletStatementEvent.Statement(
                Chain.Signet, 0, 0, arkOperator, "c".repeat(64), 2, listOf("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)),
                arkOperator, "00".repeat(32), arkOperator, GroupWavelengthWalletStatementEvent.Policy.DEFAULT,
            )
        )
        val summary = ProposedEvent.summarize(Event("0".repeat(64), "0".repeat(64), 0, template.kind, template.tags, template.content, ""))
        assertEquals("Open a Wavelength wallet", summary.label)
        assertTrue(summary.detail.contains("3 devices online"), summary.detail)
    }

    @Test
    fun `its kinds stand apart from the Lightning wallet's and each other's`() {
        assertEquals((30370..30382).toSet(), GroupWavelengthEvents.ALL)
        assertTrue(GroupWavelengthEvents.ALL.intersect(GroupLightningEvents.ALL).isEmpty())
        assertTrue(GroupWavelengthEvents.ALL.min() > GroupLightningEvents.ALL.max())
    }

}
