package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.managers.GroupFixtures.fileKeyState
import com.machankura.dedun.compose.managers.GroupFixtures.guardedCertificate
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.nostr.wallet.GuardedWalletNoticeEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the group's page and the person's page say about member wallets: the
 * group lists its members' wallets apart from its subgroups, with the requests
 * still waiting for a signer, and the person's page lists every group here that
 * guards them.
 */
class MemberWalletPagesJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val parent = keyMaterial("9a9e")
    private val group = keyMaterial("6a0b")
    private val groupRoom = roomOf(group)
    private val parentRoom = roomOf(parent)

    private val alice = privateKey(0xa11ce5)
    private val alicePublicKey = alice.xOnlyPublicKey().value.toHex()
    private val bob = privateKey(0xb0b5)
    private val bobPublicKey = bob.xOnlyPublicKey().value.toHex()

    private suspend fun certify(requestJson: String): Event {
        val template = MemberWalletCertificateEvent.template(Event.fromJson(requestJson))
        val signed = signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signed, chatRoomId = groupRoom, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))
        return signed
    }

    @Test
    fun `the group lists its members' wallets, certified and waiting, and never as subgroups`(): Unit = runBlocking {
        GroupFixtures.seedProfile(db, alicePublicKey, name = "alice")
        signingRoom(db, group, members = listOf(alicePublicKey, bobPublicKey), holdsShare = false)
        fileKeyState(db, group, guardedCertificate(parent, group))
        GroupFixtures.openToMembers(db, group)

        val certified = MemberWalletManager.request(db, groupRoom, alice, chain, label = "Savings")
        certify(certified.requestJson)
        val waiting = MemberWalletManager.request(db, groupRoom, bob, chain, label = null)

        val listed = MemberWalletManager.memberWalletsOf(db, groupRoom)
        assertEquals(listOf(certified.walletId to true, waiting.walletId to false), listed.map { it.walletId to it.isCertified })
        assertEquals("alice", listed.first().ownerName)
        assertEquals("Savings", listed.first().label)
        assertEquals(bobPublicKey, listed.last().ownerPublicKey)
        assertTrue(SubgroupManager.subgroupsOf(db, groupRoom).isEmpty())
    }

    @Test
    fun `the person's page lists every group here that guards them`(): Unit = runBlocking {
        signingRoom(db, group, members = listOf(alicePublicKey))
        fileKeyState(db, group, guardedCertificate(parent, group))
        GroupFixtures.openToMembers(db, group)
        signingRoom(db, parent, members = listOf(alicePublicKey), holdsShare = false, subject = "#Root")
        fileKeyState(db, parent)
        GroupFixtures.openToMembers(db, parent)

        val wallet = MemberWalletManager.request(db, groupRoom, alice, chain, label = null)
        val certificate = certify(wallet.requestJson)
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = "9".repeat(64), publicKey = alicePublicKey, kind = GuardedWalletNoticeEvent.KIND,
                createdAt = Instant.fromEpochSeconds(1_700_000_100), tags = arrayOf(arrayOf("h", parentRoom)),
                content = certificate.toJson(), chatRoomId = parentRoom,
            )
        )

        val here = MemberWalletManager.guardedByGroupsHere(db, GroupFixtures.owner, alicePublicKey)
        assertEquals(setOf(groupRoom, parentRoom), here.map { it.wallet.ancestorChatRoomId }.toSet())
        assertEquals(true, here.single { it.wallet.ancestorChatRoomId == groupRoom }.canSpend, "a share of the group's key")
        assertEquals(false, here.single { it.wallet.ancestorChatRoomId == parentRoom }.canSpend, "none of the root's")
        assertEquals("#Root", here.single { it.wallet.ancestorChatRoomId == parentRoom }.holderName)
        assertTrue(MemberWalletManager.guardedByGroupsHere(db, GroupFixtures.owner, bobPublicKey).isEmpty())
    }

    // ---- One entry per wallet: docs/member-wallet-guarding-group.md, Phase 6

    @Test
    fun `a wallet held by a group and its parent is one entry, guarded by the group, with both holders`(): Unit = runBlocking {
        signingRoom(db, group, members = listOf(alicePublicKey))
        fileKeyState(db, group, guardedCertificate(parent, group))
        GroupFixtures.openToMembers(db, group)
        signingRoom(db, parent, members = listOf(alicePublicKey), holdsShare = false, subject = "#Root")
        fileKeyState(db, parent)
        GroupFixtures.openToMembers(db, parent)

        val wallet = MemberWalletManager.request(db, groupRoom, alice, chain, label = "Savings")
        val certificate = certify(wallet.requestJson)
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = "9".repeat(64), publicKey = alicePublicKey, kind = GuardedWalletNoticeEvent.KIND,
                createdAt = Instant.fromEpochSeconds(1_700_000_100), tags = arrayOf(arrayOf("h", parentRoom)),
                content = certificate.toJson(), chatRoomId = parentRoom,
            )
        )

        val entry = MemberWalletManager.byWallet(MemberWalletManager.guardedByGroupsHere(db, GroupFixtures.owner, alicePublicKey)).single()
        assertEquals(wallet.walletId, entry.walletId)
        assertEquals("Savings", entry.label)
        assertEquals("#Child", entry.guardianName, "the founding name the parent certified")
        assertEquals(listOf(groupRoom, parentRoom), entry.holders.map { it.wallet.ancestorChatRoomId })
        assertEquals(listOf(true, false), entry.holders.map { it.canSpend }, "a share of the group's key, none of the root's")
    }

    @Test
    fun `Savings held by P and R, and Rent held by R, are two entries`() {
        val savingsAtP = MemberWalletManager.GuardedHere(LineageExample.heldBy(LineageExample.p, LineageExample.savings, LineageExample.savingsGuardians, label = "Savings", guardianName = "#M"), holderName = "#P", canSpend = true)
        val savingsAtR = MemberWalletManager.GuardedHere(LineageExample.heldBy(LineageExample.r, LineageExample.savings, LineageExample.savingsGuardians, label = "Savings", guardianName = null, branchName = "#P"), holderName = "#R", canSpend = true)
        val rentAtR = MemberWalletManager.GuardedHere(LineageExample.heldBy(LineageExample.r, LineageExample.rent, LineageExample.rentGuardians), holderName = "#R", canSpend = false)

        val entries = MemberWalletManager.byWallet(listOf(savingsAtR, rentAtR, savingsAtP))
        assertEquals(listOf(LineageExample.savings, LineageExample.rent), entries.map { it.walletId })

        val savings = entries.first()
        assertEquals("#M", savings.guardianName, "named by whichever holder can")
        assertEquals(listOf("#P", "#R"), savings.holders.map { it.holderName }, "nearest first")

        val rent = entries.last()
        assertNull(rent.guardianName)
        assertEquals("#R", rent.below, "N is unnamed here, but it is below R")
        assertEquals(listOf(false), rent.holders.map { it.canSpend })
    }
}
