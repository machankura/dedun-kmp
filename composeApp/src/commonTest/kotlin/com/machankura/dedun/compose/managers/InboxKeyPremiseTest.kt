package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.dkg.chill.ChillDKG
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The premise docs/group-inbox.md (D2) rests on, run against real ChillDKG: a ceremony at
 * threshold 1 leaves every participant holding the *same* secret, and that secret is the
 * discrete log of the ceremony's threshold public key.
 *
 * At `t = 1` every participant's polynomial is a constant, so every share is the sum of
 * everyone's constants. If the library ever refuses `t = 1`, or hands out different shares,
 * the inbox key cannot be made this way and the plan's fallback (a `t = n` ceremony and a
 * reveal round) applies instead.
 */
class InboxKeyPremiseTest {
    private val admins = listOf(
        "1c0ffee0000000000000000000000000000000000000000000000000000000a1",
        "2badc0de0000000000000000000000000000000000000000000000000000005b",
        "3deadbee00000000000000000000000000000000000000000000000000000c37",
    ).map { Admin(it.hexToBytes()) }

    @Test
    fun `a threshold-1 ceremony leaves every admin the same secret`() {
        val outputs = ceremony(admins)

        val secrets = outputs.map { assertNotNull(it.secretShare).value.toByteArray().toList() }
        assertEquals(1, secrets.distinct().size, "the admins came out of the ceremony holding different secrets")
    }

    @Test
    fun `that secret is the key the ceremony announces`() {
        val outputs = ceremony(admins)

        outputs.forEach { output ->
            val secret = assertNotNull(output.secretShare)
            assertEquals(assertNotNull(output.thresholdPublicKey), secret.publicKey())
        }
    }

    @Test
    fun `a NIP-44 conversation with the inbox key works whichever way round it is computed`() {
        // The key goes in a 10044 as an x-only hex key, which drops the parity. A sender
        // computes ECDH against the even point; the admins compute it with `e`, whatever the
        // ceremony's key's parity was. Both land on the same x, which is all NIP-44 uses.
        val outputs = ceremony(admins)
        val e = assertNotNull(outputs.first().secretShare)
        val inboxXOnly = e.publicKey().xOnly().value.toByteArray()

        val sender = PrivateKey(Crypto.sha256("a stranger".encodeToByteArray()))
        val senderXOnly = sender.publicKey().xOnly().value.toByteArray()

        val nip44 = Nip44v2()
        val fromSender = nip44.computeConversationKey(sender.value.toByteArray(), inboxXOnly)
        val fromAdmins = nip44.computeConversationKey(e.value.toByteArray(), senderXOnly)
        assertContentEquals(fromSender, fromAdmins)

        val payload = nip44.encrypt("hello, group", fromSender).encodePayload()
        assertEquals("hello, group", nip44.decrypt(payload, fromAdmins))
    }

    /** One ceremony, every step in host-key order, as `ChillDkgRitualOrderingTest` runs one. */
    private fun ceremony(members: List<Admin>): List<fr.acinq.bitcoin.crypto.dkg.chill.ParticipantFinalizeResult> {
        val threshold = 1
        val ordered = members.sortedBy { it.hostPublicKey.toHex() }
        val hostPublicKeys = ordered.map { it.hostPublicKey }

        val step1 = ordered.map { admin ->
            ChillDKG.participantStep1(admin.hostSecretKey, hostPublicKeys, threshold, admin.round1Random)
        }
        val coordinator = ChillDKG.coordinatorStep1(step1.map { it.message }, hostPublicKeys, threshold)
        assertTrue(coordinator.fault.isOk, "coordinator round 1 faulted")

        val step2 = ordered.mapIndexed { index, admin ->
            ChillDKG.participantStep2(admin.hostSecretKey, step1[index].state, coordinator.message, admin.round2AuxRandom)
                .also { assertTrue(it.fault.isOk, "round 2 faulted") }
        }
        val certified = ChillDKG.coordinatorFinalize(
            coordinator.state,
            step2.map { ByteVector64(it.certEqSignature) },
            threshold,
        )
        assertTrue(certified.fault.isOk, "certificate faulted")

        return step2.map { result ->
            ChillDKG.participantFinalize(result.state, certified.certificate, ordered.size, threshold)
                .also { assertTrue(it.fault.isOk, "finalize faulted") }
        }
    }

    private class Admin(nostrPrivateKey: ByteArray) {
        val hostSecretKey = ChillDkgRitualManager.deriveHostSecretKey(nostrPrivateKey)
        val hostPublicKey: PublicKey = ChillDKG.hostPublicKey(hostSecretKey)
        val round1Random = ByteVector32(Crypto.sha256(nostrPrivateKey + 1))
        val round2AuxRandom = ByteVector32(Crypto.sha256(nostrPrivateKey + 2))
    }

    private companion object {
        fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
