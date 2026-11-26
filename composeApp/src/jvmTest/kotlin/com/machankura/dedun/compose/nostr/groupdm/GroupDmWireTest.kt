package com.machankura.dedun.compose.nostr.groupdm

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.frost.Frost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.cryptography.threshold.Dleq
import com.machankura.dedun.compose.cryptography.threshold.EcdhPartial
import com.machankura.dedun.compose.cryptography.threshold.NpubTweak
import com.machankura.dedun.compose.cryptography.threshold.ThresholdEcdh
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.managers.SharedKeyDerivation

/**
 * Phase 3 of docs/group-threshold-ecdh.md: the wire, against quartz's stock NIP-17. A message
 * from a person opens through the admins' shares, and a message from the group opens in a
 * stock client with the recipient's own key.
 */
class GroupDmWireTest {
    private val x = PrivateKey(Crypto.sha256("group-dm-wire".encodeToByteArray()))
    private val keys = Frost.trustedDealerKeygen(x, 5, 3)
    private val tweak = NpubTweak.of(keys.thresholdPublicKey)
    private val group = SharedKeyDerivation.derive(keys.thresholdPublicKey.value.toByteArray().toHex()).hex

    /** The npub's secret, whole: only to stand in for a FROST signature in a test. */
    private val d: PrivateKey = run {
        var q = keys.thresholdPublicKey
        var negate = false
        var b: PrivateKey? = null
        SharedKeyDerivation.derive(keys.thresholdPublicKey.value.toByteArray().toHex()).tweaks.forEach { s ->
            val tau = PrivateKey(s)
            if (q.isOdd()) { q = -q; negate = !negate; b = b?.let { -it } }
            q += tau.publicKey(); b = b?.let { it + tau } ?: tau
        }
        (if (negate) -x else x) + b!!
    }

    /** What three admins' devices would agree on, without any of them holding `d`. */
    private fun thresholdKey(point: String, ids: List<Int> = listOf(0, 2, 4)): ByteArray {
        val p = ThresholdEcdh.pointOf(point)!!
        val partials = ids.associateWith { EcdhPartial.compute(keys.secretShares[it], p) }
        return ThresholdEcdh.conversationKey(ThresholdEcdh.combine(partials, tweak, p))
    }

    @Test
    fun `a message from a person, wrapped by quartz, opens through the admins' shares`() = runBlocking {
        val sender = KeyPair()
        val signer = NostrSignerInternal(sender)
        val rumor = Event(
            id = "", pubKey = sender.pubKey.toHexKey(), createdAt = 1_700_000_000, kind = 14,
            tags = arrayOf(arrayOf("p", group)), content = "dumela", sig = "",
        )
        val seal = SealEvent.create(rumor, group, signer)
        val wrap = GiftWrapEvent.create(seal, group)

        val openedSeal = assertNotNull(GroupDmWrap.open(wrap, thresholdKey(wrap.pubKey)))
        assertEquals(sender.pubKey.toHexKey(), openedSeal.pubKey)
        val opened = assertNotNull(GroupDmSeal.open(openedSeal, thresholdKey(openedSeal.pubKey, listOf(1, 2, 3))))
        assertEquals("dumela", opened.content)
        assertEquals(sender.pubKey.toHexKey(), opened.pubKey)
        assertEquals(listOf(sender.pubKey.toHexKey()), GroupDmConversation.participantsOf(opened, group))
        // The wrong key opens neither.
        assertNull(GroupDmWrap.open(wrap, thresholdKey(openedSeal.pubKey)))
    }

    @Test
    fun `a message from the group opens in quartz's stock unwrap with the recipient's own key`() = runBlocking {
        val recipient = KeyPair()
        val r = recipient.pubKey.toHexKey()
        val rumor = GroupDmSeal.rumor(group, 1_700_000_000, 14, arrayOf(arrayOf("p", r)), "the group says hello")
        val template = GroupDmSeal.build(rumor, thresholdKey(r), createdAt = 1_699_990_000)
        // FROST's signature stands in as a signature by d.
        val signedSeal = NostrSignerSync(KeyPair(privKey = d.value.toByteArray()))
            .sign<Event>(template.createdAt, template.kind, template.tags, template.content)
        assertEquals(group, signedSeal.pubKey)
        val wrap = GiftWrapEvent.create(signedSeal, r)

        val opened = assertNotNull(wrap.unwrapAndUnsealOrNull(NostrSignerInternal(recipient)))
        assertEquals("the group says hello", opened.content)
        assertEquals(group, opened.pubKey)
        assertEquals(rumor.id, opened.id)
    }

    // --- D5

    private fun signedBy(keyPair: KeyPair, kind: Int, tags: Array<Array<String>> = emptyArray()): Event =
        NostrSignerSync(keyPair).sign(1_700_000_000, kind, tags, "x")

    @Test
    fun `evidence is refused unless the point signed something of the right shape`() = runBlocking {
        val person = KeyPair()
        val p = person.pubKey.toHexKey()
        val toGroup = GiftWrapEvent.create(signedBy(person, 13), group)
        assertNull(Evidence.check(EcdhPurpose.WRAP, toGroup.pubKey, toGroup, group))

        val toSomeoneElse = GiftWrapEvent.create(signedBy(person, 13), p)
        assertEquals(Evidence.Refusal.NOT_TO_GROUP, Evidence.check(EcdhPurpose.WRAP, toSomeoneElse.pubKey, toSomeoneElse, group))

        val seal = signedBy(person, 13)
        assertNull(Evidence.check(EcdhPurpose.SEAL, p, seal, group))
        val unsigned = Event(seal.id, seal.pubKey, seal.createdAt, seal.kind, seal.tags, seal.content, "")
        assertEquals(Evidence.Refusal.BAD_SIGNATURE, Evidence.check(EcdhPurpose.SEAL, p, unsigned, group))
        assertEquals(Evidence.Refusal.WRONG_KIND, Evidence.check(EcdhPurpose.SEAL, p, signedBy(person, 1), group))

        assertNull(Evidence.check(EcdhPurpose.RECIPIENT, p, signedBy(person, 0), group))
        assertEquals(Evidence.Refusal.WRONG_KEY, Evidence.check(EcdhPurpose.RECIPIENT, p, signedBy(KeyPair(), 0), group))
        val groupSigned = NostrSignerSync(KeyPair(privKey = d.value.toByteArray())).sign<Event>(1_700_000_000, 0, emptyArray(), "{}")
        assertEquals(Evidence.Refusal.THE_GROUP_ITSELF, Evidence.check(EcdhPurpose.RECIPIENT, group, groupSigned, group))
    }

    // --- the payload, the manifest and the admin seal

    private val hostA = PrivateKey(Crypto.sha256("host-a".encodeToByteArray()))
    private val hostB = PrivateKey(Crypto.sha256("host-b".encodeToByteArray()))
    private val hostC = PrivateKey(Crypto.sha256("host-c".encodeToByteArray()))
    private fun hex(k: PrivateKey) = k.publicKey().value.toHex()

    @Test
    fun `an admin seal opens for the admins it was sealed to and nobody else`() {
        val sealed = AdminSeal.seal("partials", hostA, listOf(hex(hostA), hex(hostB)))
        assertEquals("partials", AdminSeal.open(sealed, hostB, hex(hostB), hex(hostA)))
        assertEquals("partials", AdminSeal.open(sealed, hostA, hex(hostA), hex(hostA)))
        assertNull(AdminSeal.open(sealed, hostC, hex(hostC), hex(hostA)), "no slot")
        assertNull(AdminSeal.open(sealed, hostB, hex(hostB), hex(hostC)), "another sender")
        val roundTrip = AdminSeal.Sealed(sealed.content, AdminSeal.parseSlots(GroupEcdhEvents.partialsTags(sealed)))
        assertEquals("partials", AdminSeal.open(roundTrip, hostB, hex(hostB), hex(hostA)))
    }

    @Test
    fun `a payload round-trips, and only its own encoding decodes`() {
        val w = KeyPair().pubKey.toHexKey()
        val p = ThresholdEcdh.pointOf(w)!!
        val partial = EcdhPartial.compute(keys.secretShares[0], p)
        val proof = Dleq.prove(keys.secretShares[0], keys.publicShares[0], p, partial, Dleq.context(group, 0, 0), ByteArray(32))
        val evidence = signedBy(KeyPair(), 0)
        val payload = GroupEcdhPayload(
            generation = 0, from = 0,
            items = listOf(
                GroupEcdhPayload.Item(w, EcdhPurpose.WRAP, ref = "a".repeat(64), partial = partial, proof = proof),
                GroupEcdhPayload.Item(evidence.pubKey, EcdhPurpose.RECIPIENT, evidence = evidence, partial = partial, proof = proof),
            ),
        )
        val text = payload.encode()
        val decoded = assertNotNull(GroupEcdhPayload.decode(text))
        assertEquals(text, decoded.encode())
        assertEquals(evidence.id, decoded.items[1].evidence?.id)
        assertNull(GroupEcdhPayload.decode(text.replace("\"v\":1", "\"v\": 1")), "another spelling")
        assertNull(GroupEcdhPayload.decode(text.replace("\"purpose\":\"wrap\"", "\"purpose\":\"recipient\"")), "a recipient with a ref")
        assertNull(GroupEcdhPayload.decode("""{"v":1,"generation":0,"from":0,"items":[]}"""), "nothing in it")
    }

    @Test
    fun `a manifest round-trips, and only its own encoding decodes`() {
        val evidence = signedBy(KeyPair(), 0)
        val manifest = GroupDmManifest(listOf(GroupDmManifest.Seal(evidence.pubKey, evidence)), replyingTo = null)
        val text = manifest.encode()
        assertEquals(text, GroupDmManifest.decode(text)?.encode())
        assertNull(GroupDmManifest.decode(text.replace("null", "\"zz\"")))
        val reply = GroupDmManifest(manifest.seals, replyingTo = "b".repeat(64))
        assertEquals("b".repeat(64), GroupDmManifest.decode(reply.encode())?.replyingTo)

        val sealed = AdminSeal.seal(text, hostA, listOf(hex(hostB)))
        val tags = manifest.toTags(sealed)
        val carried = assertNotNull(GroupDmManifest.sealedIn(tags))
        assertEquals(text, AdminSeal.open(carried, hostB, hex(hostB), hex(hostA)))
    }

    @Test
    fun `a conversation is its correspondents, in any order, without the group`() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        val one = GroupDmSeal.rumor(a, 1, 14, arrayOf(arrayOf("p", group), arrayOf("p", b)), "hi")
        val two = GroupDmSeal.rumor(b, 2, 14, arrayOf(arrayOf("p", a), arrayOf("p", group)), "hi")
        assertEquals(GroupDmConversation.idOf(one, group), GroupDmConversation.idOf(two, group))
        assertEquals(listOf(a, b), GroupDmConversation.participantsOf(one, group))
    }
}
