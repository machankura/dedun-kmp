package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.managers.MemberWalletDerivation
import com.machankura.dedun.compose.managers.SharedKeyDerivation
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

/**
 * A member's request, read and believed on everything that can be checked
 * without the group's key -- one mutation per check, each of which has to be
 * enough on its own to refuse it.
 */
class MemberWalletRequestEventTest {
    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val member = privateKey(0x3e3be7)
    private val walletPrivateKey = privateKey(0xa11ce)
    private val walletKey = walletPrivateKey.publicKey()
    private val walletId = MemberWalletDerivation.walletId(walletKey)

    private val groupKey = privateKey(0x6a0b).publicKey().toString()
    private val groupRoom = SharedKeyDerivation.marmotGroupId(groupKey)
    private val parentKey = privateKey(0x9a9e).publicKey().toString()
    private val parentRoom = SharedKeyDerivation.marmotGroupId(parentKey)

    private val grant = GuardianDerivation.grant(
        groupKey, groupRoom, GuardianDerivation.grant(parentKey, parentRoom, emptyList(), groupRoom), walletId,
    )
    private val account = MemberWalletDerivation.account(walletKey, chain, grant)

    private fun request(label: String? = "Savings", group: String = groupRoom) =
        MemberWalletRequestEvent.build(member, group, walletPrivateKey, account, label, createdAt = 1_700_000_000)

    /** [event] with [tags], re-signed by the member, so only the tags are wrong. */
    private fun resigned(event: Event, tags: Array<Array<String>> = event.tags, content: String = event.content): Event {
        val id = EventHasher.hashId(event.pubKey, event.createdAt, event.kind, tags, content)
        val sig = Crypto.signSchnorr(ByteVector32(id), member, null).toHex()
        return Event(id, event.pubKey, event.createdAt, event.kind, tags, content, sig)
    }

    private fun Event.replacing(name: String, vararg value: String): Array<Array<String>> =
        tags.map { if (it[0] == name) arrayOf(name, *value) else it }.toTypedArray()

    @Test
    fun `a request reads and is believed`() {
        val verified = assertNotNull(MemberWalletRequestEvent.verified(request()))
        assertEquals(member.xOnlyPublicKey().value.toHex(), verified.reading.memberPublicKey)
        assertEquals(groupRoom, verified.reading.groupChatRoomId)
        assertEquals(walletId, verified.reading.walletId)
        assertEquals(grant, verified.reading.guardians)
        assertEquals("Savings", verified.reading.label)
        assertEquals(account, verified.account)
    }

    @Test
    fun `a request with no label reads`() {
        assertNull(assertNotNull(MemberWalletRequestEvent.verified(request(label = null))).reading.label)
        assertNull(MemberWalletRequestEvent.verified(request(label = "  ")).let { it?.reading?.label })
    }

    @Test
    fun `a request somebody else signed is refused`() {
        val signedByAnother = request().let { Event(it.id, it.pubKey, it.createdAt, it.kind, it.tags, it.content, "0".repeat(128)) }
        assertNull(MemberWalletRequestEvent.verified(signedByAnother))
    }

    @Test
    fun `a wallet id that is not the key's is refused`() {
        val event = request()
        assertNull(MemberWalletRequestEvent.verified(resigned(event, event.replacing("wallet", "f".repeat(64)))))
    }

    @Test
    fun `a possession signature by another key is refused`() {
        val event = request()
        val other = Crypto.signSchnorr(ByteVector32("ab".repeat(32)), privateKey(0xb0b), null).toHex()
        assertNull(MemberWalletRequestEvent.verified(resigned(event, event.replacing("possession", other))))
    }

    @Test
    fun `a request that leaves out the group it asks is refused`() {
        // Signed for the group, but asking a stranger: the nearest guardian is not the group asked.
        val event = request()
        val stranger = SharedKeyDerivation.marmotGroupId(privateKey(0x57a9).publicKey().toString())
        assertNull(MemberWalletRequestEvent.verified(resigned(event, event.replacing("h", stranger))))
    }

    @Test
    fun `a descriptor that does not rederive is refused`() {
        val event = request()
        val plain = MemberWalletDerivation.account(walletKey, chain, emptyList())
        val tags = event.tags.map { if (it[0] == "descriptor" && it[2] == "receive") arrayOf("descriptor", plain.receiveDescriptor, "receive") else it }
        assertNull(MemberWalletRequestEvent.verified(resigned(event, tags.toTypedArray())))
        // The xpub does not depend on the guardians, so it is another key's that does not rederive.
        val another = MemberWalletDerivation.account(privateKey(0xb0b).publicKey(), chain, grant)
        assertNull(MemberWalletRequestEvent.verified(resigned(event, content = another.xpub)))
    }

    @Test
    fun `two wallet tags, or none, do not read`() {
        val event = request()
        assertNull(MemberWalletRequestEvent.read(resigned(event, event.tags + arrayOf(arrayOf("wallet", walletId)))))
        assertNull(MemberWalletRequestEvent.read(resigned(event, event.tags.filterNot { it[0] == "wallet" }.toTypedArray())))
    }

    @Test
    fun `a request travels whole, sent only by the member who signed it`() {
        val signed = request()
        fun inner(sender: String) = Event("1".repeat(64), sender, signed.createdAt, MemberWalletRequestEvent.KIND, emptyArray(), signed.toJson(), "")

        assertEquals(signed.id, MemberWalletRequestEvent.carried(inner(signed.pubKey))?.id)
        assertNotNull(MemberWalletRequestEvent.verified(MemberWalletRequestEvent.carried(inner(signed.pubKey))!!), "the signature survives the trip")
        assertNull(MemberWalletRequestEvent.carried(inner("2".repeat(64))), "nobody passes a request on")
    }
}
