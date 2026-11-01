package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.dedun.compose.managers.GuardianManager.GuardedSubject
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The rules forwarding is built on, with no database: which groups a statement
 * is told to from a room -- upward only -- who sends at once, and what a
 * forwarder believes before it passes anything on.
 * `docs/guarded-wallet-forwarding.md`, Phase 1.
 */
class GuardedWalletForwardingRulesJvmTest {
    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val root = keyMaterial("7007")
    private val middle = keyMaterial("9a9e")
    private val group = keyMaterial("6a0b")
    private val rootRoom = roomOf(root)
    private val middleRoom = roomOf(middle)
    private val groupRoom = roomOf(group)

    /** A grandchild's guardians as its certificate lists them: its parent, then the root one step further. */
    private val grandchildRoom = roomOf(keyMaterial("c1d0"))
    private val grandchildGuardians = GuardianDerivation.grant(
        thresholdKeyOf(middle), middleRoom,
        GuardianDerivation.grant(thresholdKeyOf(root), rootRoom, emptyList(), middleRoom),
        grandchildRoom,
    )

    @Test
    fun `a grandchild's statement goes up from where it is, and never back down`() {
        assertEquals(listOf(middleRoom, rootRoom), GuardianManager.upwardOf(grandchildGuardians, grandchildRoom), "from its own room, every guardian")
        assertEquals(listOf(rootRoom), GuardianManager.upwardOf(grandchildGuardians, middleRoom), "from the middle group, the root alone")
        assertEquals(emptyList(), GuardianManager.upwardOf(grandchildGuardians, rootRoom), "from the root, nothing: the middle group told it")
    }

    private val member = privateKey(0x3e3be7)
    private val walletPrivateKey = privateKey(0xa11ce)
    private val walletId = MemberWalletDerivation.walletId(walletPrivateKey.publicKey())

    /** A member's wallet under `group`, two levels below the root. */
    private val memberGuardians = GuardianDerivation.grant(
        thresholdKeyOf(group), groupRoom,
        GuardianDerivation.grant(
            thresholdKeyOf(middle), middleRoom,
            GuardianDerivation.grant(thresholdKeyOf(root), rootRoom, emptyList(), middleRoom),
            groupRoom,
        ),
        walletId,
    )

    private fun certificate(signer: fr.acinq.bitcoin.crypto.frost.KeyMaterial = group): Event {
        val request = MemberWalletRequestEvent.build(
            memberPrivateKey = member,
            groupChatRoomId = groupRoom,
            walletPrivateKey = walletPrivateKey,
            account = MemberWalletDerivation.account(walletPrivateKey.publicKey(), chain, memberGuardians),
            label = null,
            createdAt = 1_700_000_000,
        )
        val template = MemberWalletCertificateEvent.template(request)
        return signedAsRoom(signer, template.kind, template.tags, template.content, template.createdAt)
    }

    @Test
    fun `a member's wallet goes up from the group that signed it`() {
        assertEquals(listOf(middleRoom, rootRoom), GuardianManager.upwardOf(memberGuardians, groupRoom))
        assertEquals(listOf(rootRoom), GuardianManager.upwardOf(memberGuardians, middleRoom))
    }

    @Test
    fun `the lowest key sends at once`() {
        assertEquals("0a".repeat(32), GuardianManager.electedSender(setOf("ff".repeat(32), "0A".repeat(32), "7c".repeat(32))))
        assertNull(GuardianManager.electedSender(emptySet()))
    }

    @Test
    fun `a forwarder believes a member's certificate the group signed`() {
        val believed = assertNotNull(GuardianManager.believable(certificate()))
        assertEquals(walletId, believed.descendantId)
        assertEquals(memberGuardians, believed.guardians)
        assertIs<GuardedSubject.Member>(believed.subject)
    }

    @Test
    fun `a forwarder believes nothing it could not check`() {
        // Signed by a group that was not the one asked.
        assertNull(GuardianManager.believable(certificate(signer = middle)))
        // A signature that is not one.
        val certificate = certificate()
        assertNull(GuardianManager.believable(Event(certificate.id, certificate.pubKey, certificate.createdAt, certificate.kind, certificate.tags, certificate.content, "0".repeat(128))))
        // A plain wallet statement names no guardians.
        val plain = GroupWalletStatementEvent.template(GroupWalletDerivation.account(thresholdKeyOf(group), chain))
        assertNull(GuardianManager.believable(signedAsRoom(group, plain.kind, plain.tags, plain.content, plain.createdAt)))
        // Something else altogether.
        assertNull(GuardianManager.believable(signedAsRoom(group, 1, emptyArray(), "hello")))
    }
}
