package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.subgroup.tags.GuardianTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A wallet statement, read and believed.
 *
 * The statement is a record that the group agreed, not a source of keys, so most
 * of what is here is refusals: a statement is only a wallet when the room signed
 * it *and* the room's threshold key rederives every value it carries.
 */
class GroupWalletStatementEventTest {
    private fun key(seed: Int): String =
        PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0')))).publicKey().toString()

    private val thresholdKey = key(0x1c0ffee)
    private val otherThresholdKey = key(0xdecaf)

    /**
     * The room, as a key whose secret the test holds -- `GroupSignedEvent.verifies`
     * asks only that the room's id signed it, and a real FROST signature over the
     * same id is `GroupWalletSpendRoundTest`'s business.
     */
    private val roomKey = PrivateKey(ByteVector32(Hex.decode("07".repeat(32))))
    private val roomId = roomKey.publicKey().xOnly().value.toString()

    private fun signed(tags: Array<Array<String>>, content: String, createdAt: Long = 1_700_000_000, by: PrivateKey = roomKey): GroupSignedEvent {
        val pubKey = by.publicKey().xOnly().value.toString()
        val id = EventHasher.hashId(pubKey, createdAt, GroupWalletStatementEvent.KIND, tags, content)
        val sig = Crypto.signSchnorr(ByteVector32(id), by, null).toString()
        return GroupSignedEvent.fromEvent(
            Event(id, pubKey, createdAt, GroupWalletStatementEvent.KIND, tags, content, sig),
            chatRoomId = roomId,
        )
    }

    private fun statement(account: GroupWalletDerivation.Account, createdAt: Long = 1_700_000_000): GroupSignedEvent {
        val template = GroupWalletStatementEvent.template(account, createdAt = createdAt)
        return signed(template.tags, template.content, createdAt)
    }

    private val mainnet = GroupWalletDerivation.account(thresholdKey, Chain.Mainnet)

    @Test
    fun `a statement reads back as what was written`() {
        val template = GroupWalletStatementEvent.template(mainnet)
        val reading = assertNotNull(GroupWalletStatementEvent.read(template.tags, template.content))

        assertEquals(Chain.Mainnet, reading.chain)
        assertEquals(0L, reading.account)
        assertEquals(mainnet.xpub, reading.xpub)
        assertEquals(mainnet.receiveDescriptor, reading.receiveDescriptor)
        assertEquals(mainnet.changeDescriptor, reading.changeDescriptor)
        assertEquals(mainnet, reading.verifiedAgainst(thresholdKey))
    }

    @Test
    fun `the room's statement opens the room's wallet`() {
        val wallet = assertNotNull(GroupWallet.of(statement(mainnet), thresholdKey))

        assertEquals(mainnet, wallet.account)
        assertEquals(Chain.Mainnet, wallet.chain)
    }

    @Test
    fun `a statement another key rederives is not this room's wallet`() {
        // Signed by the room, every field well formed -- and naming the xpub of a
        // different group. Whoever signed it, it is not a wallet this room's key holds.
        val foreign = GroupWalletDerivation.account(otherThresholdKey, Chain.Mainnet)

        assertNull(GroupWallet.of(statement(foreign), thresholdKey))
    }

    @Test
    fun `an xpub edited after the fact is refused`() {
        val template = GroupWalletStatementEvent.template(mainnet)
        val other = GroupWalletDerivation.account(thresholdKey, Chain.Mainnet, account = 1)

        assertNull(GroupWallet.of(signed(template.tags, other.xpub), thresholdKey))
    }

    @Test
    fun `a statement the room did not sign is a member's, not the group's`() {
        val template = GroupWalletStatementEvent.template(mainnet)
        val member = PrivateKey(ByteVector32(Hex.decode("09".repeat(32))))

        assertNull(GroupWallet.of(signed(template.tags, template.content, by = member), thresholdKey))
    }

    @Test
    fun `a d tag that disagrees with the fields is not read`() {
        val template = GroupWalletStatementEvent.template(mainnet)
        val tags = template.tags.map { if (it[0] == "d") arrayOf("d", "regtest:0") else it }.toTypedArray()

        assertNull(GroupWalletStatementEvent.read(tags, template.content))
    }

    @Test
    fun `the newest statement for the asked network wins, and another network's is not it`() {
        val regtest = GroupWalletDerivation.account(thresholdKey, Chain.Regtest)
        val older = statement(mainnet, createdAt = 1_700_000_000)
        val newer = statement(mainnet, createdAt = 1_700_000_100)

        val wallet = assertNotNull(
            GroupWallet.newestAmong(listOf(newer, statement(regtest), older), thresholdKey, Chain.Mainnet)
        )
        assertEquals(newer.id, wallet.signedEvent.id)
        assertNull(GroupWallet.newestAmong(listOf(older, newer), thresholdKey, Chain.Signet))
    }

    // ---- A guarded statement ---------------------------------------------

    /**
     * The parent, as a key whose secret the test holds: `certifies` asks only
     * that the room the certificate names as parent signed it.
     */
    private val parentKey = PrivateKey(ByteVector32(Hex.decode("0b".repeat(32))))
    private val parentRoomId = parentKey.publicKey().xOnly().value.toString()
    private val childRoomId = SharedKeyDerivation.marmotGroupId(thresholdKey)
    private val grant = GuardianDerivation.grant(key(0x9a4e47), parentRoomId, emptyList(), childRoomId)

    private fun certificate(guardians: List<GuardianDerivation.Guardian> = grant, childKey: String = thresholdKey): Event {
        val tags = SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = SharedKeyDerivation.marmotGroupId(childKey),
            parentChatRoomId = parentRoomId,
            thresholdPublicKey = childKey,
            adminPublicKeys = listOf("a".repeat(64)),
            name = "Translation team",
            guardians = guardians,
        )
        val content = SharedKeyDerivation.marmotGroupId(childKey)
        val id = EventHasher.hashId(parentRoomId, 1_700_000_000, SubgroupBirthCertificateEvent.KIND, tags, content)
        val sig = Crypto.signSchnorr(ByteVector32(id), parentKey, null).toString()
        return Event(id, parentRoomId, 1_700_000_000, SubgroupBirthCertificateEvent.KIND, tags, content, sig)
    }

    private val guarded = GroupWalletDerivation.account(thresholdKey, Chain.Mainnet, guardians = grant)

    private fun guardedStatement(
        account: GroupWalletDerivation.Account = guarded,
        certificate: Event = certificate(),
        createdAt: Long = 1_700_000_000,
    ): GroupSignedEvent {
        val template = GroupWalletStatementEvent.template(account, certificate, createdAt)
        return signed(template.tags, template.content, createdAt)
    }

    @Test
    fun `a guarded statement reads back as the guarded account, tree and all`() {
        val wallet = assertNotNull(GroupWallet.of(guardedStatement(), thresholdKey))

        assertEquals(guarded, wallet.account)
        assertEquals(grant, wallet.account.guardians)
        assertTrue(wallet.account.receiveDescriptor.contains("and_v(v:pk("), wallet.account.receiveDescriptor)
        // Same internal key as the plain account, different address: only the tree differs.
        val plainAddress = GroupWalletDerivation.derive(mainnet, 0, 0)
        val guardedAddress = GroupWalletDerivation.derive(guarded, 0, 0)
        assertEquals(plainAddress.internalKey, guardedAddress.internalKey)
        assertTrue(plainAddress.address(Chain.Mainnet) != guardedAddress.address(Chain.Mainnet))
        assertTrue(guardedAddress.address(Chain.Mainnet).startsWith("bc1p"))
        assertEquals(guardedAddress.outputKey, guardedAddress.cache.tweakedPublicKey, "the cache signs the key path of the guarded address")
    }

    @Test
    fun `guardians the certificate does not grant open nothing`() {
        val stranger = GuardianDerivation.grant(key(0x5), parentRoomId, emptyList(), childRoomId)
        val wrongGuardians = GroupWalletDerivation.account(thresholdKey, Chain.Mainnet, guardians = stranger)

        assertNull(GroupWallet.of(guardedStatement(account = wrongGuardians), thresholdKey), "guardians the parent never granted")
        assertNull(GroupWallet.of(guardedStatement(certificate = certificate(childKey = otherThresholdKey)), thresholdKey), "a certificate for another child")
    }

    @Test
    fun `a guarded statement with its guardians stripped is no wallet at all`() {
        // What a client that predates guardians sees: descriptors with a tree it
        // does not build. It refuses rather than hand out plain addresses under
        // the guarded account's name.
        val template = GroupWalletStatementEvent.template(guarded, certificate())
        val stripped = template.tags.filterNot { it[0] == GuardianTag.TAG_NAME }.toTypedArray()

        assertNull(GroupWallet.of(signed(stripped, template.content), thresholdKey))
    }

    @Test
    fun `an account opened guarded stays guarded, under its first guardians`() {
        val firstGuarded = guardedStatement(createdAt = 1_700_000_000)
        val laterPlain = statement(mainnet, createdAt = 1_700_000_200)
        val laterOtherGuardians = run {
            // The parent re-certified with other keys; a later statement adopting
            // them would move every address, so it is ignored.
            val other = GuardianDerivation.grant(key(0x77), parentRoomId, emptyList(), childRoomId)
            guardedStatement(
                account = GroupWalletDerivation.account(thresholdKey, Chain.Mainnet, guardians = other),
                certificate = certificate(other),
                createdAt = 1_700_000_300,
            )
        }

        val wallet = assertNotNull(GroupWallet.newestAmong(listOf(laterPlain, firstGuarded, laterOtherGuardians), thresholdKey, Chain.Mainnet))
        assertEquals(firstGuarded.id, wallet.signedEvent.id)
        assertEquals(laterPlain.id, GroupWallet.newestAmong(listOf(laterPlain), thresholdKey, Chain.Mainnet)?.signedEvent?.id)
    }

    // ---- the parent's decision: docs/subgroup-wallet-policy.md -------------

    private val decidedGuarded = WalletPolicyReading.Decided(SubgroupWalletPolicy.GUARDED)
    private val decidedUnguarded = WalletPolicyReading.Decided(SubgroupWalletPolicy.UNGUARDED)

    @Test
    fun `under a guarded policy a plain statement is no wallet, even alone`() {
        // Stronger than rule 1: an older client's plain wallet opens nothing,
        // so no device derives its addresses or watches its coins.
        val plain = statement(mainnet)

        assertNull(GroupWallet.newestAmong(listOf(plain), thresholdKey, Chain.Mainnet, policy = decidedGuarded))

        val guarded = guardedStatement(createdAt = 1_700_000_100)
        assertEquals(
            guarded.id,
            GroupWallet.newestAmong(listOf(plain, guarded), thresholdKey, Chain.Mainnet, policy = decidedGuarded)?.signedEvent?.id,
        )
    }

    @Test
    fun `under an unguarded policy a guarded statement is no wallet`() {
        val plain = statement(mainnet, createdAt = 1_700_000_000)
        val guarded = guardedStatement(createdAt = 1_700_000_100)

        assertEquals(
            plain.id,
            GroupWallet.newestAmong(listOf(plain, guarded), thresholdKey, Chain.Mainnet, policy = decidedUnguarded)?.signedEvent?.id,
            "rule 1 would have picked the guarded one; the parent ruled it out",
        )
    }

    @Test
    fun `an undecided room reads exactly as before policies`() {
        val plain = statement(mainnet, createdAt = 1_700_000_200)
        val guarded = guardedStatement(createdAt = 1_700_000_000)

        listOf(WalletPolicyReading.Undecided, null).forEach { policy ->
            assertEquals(
                guarded.id,
                GroupWallet.newestAmong(listOf(plain, guarded), thresholdKey, Chain.Mainnet, policy = policy)?.signedEvent?.id,
            )
            assertEquals(plain.id, GroupWallet.newestAmong(listOf(plain), thresholdKey, Chain.Mainnet, policy = policy)?.signedEvent?.id)
        }
    }

    @Test
    fun `every network name round trips`() {
        listOf(Chain.Mainnet, Chain.Testnet3, Chain.Testnet4, Chain.Signet, Chain.Regtest).forEach {
            assertEquals(it, GroupWalletStatementEvent.chainNamed(GroupWalletStatementEvent.chainName(it)))
        }
    }
}
