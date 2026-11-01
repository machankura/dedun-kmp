package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.extensions.toHex
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.dkg.chill.ChillDKG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ordering rule the ritual is built on, run against real ChillDKG.
 *
 * ChillDKG has no session-params object to agree on out of band: every step
 * takes the host public keys and the threshold and hashes them into the session
 * identity, so a group that orders its participants differently on different
 * devices does not get a weaker key — it gets no key. `ChillDkgRitualManager`
 * has each device derive that order independently by sorting the host keys, and
 * order each round's messages by their sender's host key to match. Nothing in
 * the protocol checks that for us, so it is checked here.
 */
class ChillDkgRitualOrderingTest {
    /** Stand-in nostr identity keys, one per member. */
    private val nostrPrivateKeys = listOf(
        "1c0ffee0000000000000000000000000000000000000000000000000000000a1",
        "2badc0de0000000000000000000000000000000000000000000000000000005b",
        "3deadbee00000000000000000000000000000000000000000000000000000c37"
    ).map { it.hexToBytes() }

    @Test
    fun `sorted host keys give every device the same participant order`() {
        val members = nostrPrivateKeys.map { Member(it) }

        // Every device sorts the host keys it collected. They arrive in whatever
        // order the relays deliver them, so shuffle before sorting to prove the
        // arrival order cannot leak into the result.
        val orders = listOf(
            members,
            members.reversed(),
            listOf(members[1], members[2], members[0])
        ).map { arrival -> participantOrder(arrival) }

        assertEquals(1, orders.distinct().size, "devices derived different participant orders")
    }

    @Test
    fun `a ritual ordered by host key produces one shared key`() {
        val threshold = 2
        val members = nostrPrivateKeys.map { Member(it) }

        // Each device independently derives the participant set the same way the
        // manager does: sort the collected host keys.
        val hostPublicKeys = participantOrder(members.shuffled())
            .map { PublicKey(ByteVector(it.hexToBytes())) }

        val step1 = members.associateWith { member ->
            ChillDKG.participantStep1(
                hostSecretKey = member.hostSecretKey,
                hostPublicKeys = hostPublicKeys,
                threshold = threshold,
                random = ByteVector32(member.round1Random)
            )
        }

        // The coordinator holds the round-1 messages keyed by sender and orders
        // them by that sender's host key, which is `orderedPayloads`' rule.
        val coordinatorStep1 = ChillDKG.coordinatorStep1(
            participantMessages = orderedBySenderHostKey(step1) { it.message },
            hostPublicKeys = hostPublicKeys,
            threshold = threshold
        )
        assertTrue(coordinatorStep1.fault.isOk, "coordinator round 1 faulted")

        val step2 = members.associateWith { member ->
            ChillDKG.participantStep2(
                hostSecretKey = member.hostSecretKey,
                state = step1.getValue(member).state,
                coordinatorMessage = coordinatorStep1.message,
                auxRand = ByteVector32(member.round2AuxRandom)
            ).also { assertTrue(it.fault.isOk, "round 2 faulted for ${member.hostPublicKeyHex.take(8)}") }
        }

        val certified = ChillDKG.coordinatorFinalize(
            state = coordinatorStep1.state,
            certEqSignatures = orderedBySenderHostKey(step2) { ByteVector64(it.certEqSignature) },
            threshold = threshold
        )
        assertTrue(certified.fault.isOk, "certificate faulted")

        val outputs = members.map { member ->
            ChillDKG.participantFinalize(
                state = step2.getValue(member).state,
                certificate = certified.certificate,
                nParticipants = hostPublicKeys.size,
                threshold = threshold
            ).also { assertTrue(it.fault.isOk, "finalize faulted for ${member.hostPublicKeyHex.take(8)}") }
        }

        val thresholdPublicKey = certified.thresholdPublicKey
        assertNotNull(thresholdPublicKey)
        outputs.forEach { output ->
            assertEquals(thresholdPublicKey, output.thresholdPublicKey)
            assertNotNull(output.secretShare)
        }
        assertEquals(members.size, outputs.map { it.secretShare }.distinct().size, "shares are not distinct")
    }

    @Test
    fun `one device ordering participants differently gets no key`() {
        val threshold = 2
        val members = nostrPrivateKeys.map { Member(it) }
        val agreed = participantOrder(members).map { PublicKey(ByteVector(it.hexToBytes())) }

        // The odd one out runs the ritual on the same people in another order. It
        // is the failure this whole ordering rule exists to prevent, so it has to
        // actually fail rather than quietly produce a second key.
        val step1 = members.mapIndexed { index, member ->
            member to ChillDKG.participantStep1(
                hostSecretKey = member.hostSecretKey,
                hostPublicKeys = if (index == 0) agreed.reversed() else agreed,
                threshold = threshold,
                random = ByteVector32(member.round1Random)
            )
        }.toMap()

        val coordinatorStep1 = ChillDKG.coordinatorStep1(
            participantMessages = orderedBySenderHostKey(step1) { it.message },
            hostPublicKeys = agreed,
            threshold = threshold
        )

        val faulted = !coordinatorStep1.fault.isOk || members.any { member ->
            !ChillDKG.participantStep2(
                hostSecretKey = member.hostSecretKey,
                state = step1.getValue(member).state,
                coordinatorMessage = coordinatorStep1.message,
                auxRand = ByteVector32(member.round2AuxRandom)
            ).fault.isOk
        }

        assertTrue(faulted, "a disagreement on participant order went undetected")
    }

    @Test
    fun `host keys are not the nostr keys they come from`() {
        nostrPrivateKeys.forEach { nostrPrivateKey ->
            assertTrue(
                ChillDkgRitualManager.deriveHostSecretKey(nostrPrivateKey).value.toByteArray()
                    .contentEquals(nostrPrivateKey).not(),
                "the host secret key must not be the nostr identity key"
            )
        }

        // Deriving twice from the same seed has to land on the same key, or a
        // reinstall cannot recover the share.
        assertEquals(
            ChillDkgRitualManager.deriveHostPublicKey(nostrPrivateKeys.first()),
            ChillDkgRitualManager.deriveHostPublicKey(nostrPrivateKeys.first())
        )
    }

    /** `hostPublicKeys`' rule: case-folded hex, sorted. */
    private fun participantOrder(members: List<Member>): List<String> =
        members.map { it.hostPublicKeyHex.lowercase() }.sorted()

    /** `orderedPayloads`' rule: each sender's message, in host-key order. */
    private fun <T, R> orderedBySenderHostKey(bySender: Map<Member, T>, message: (T) -> R): List<R> =
        bySender.entries
            .sortedBy { (member, _) -> member.hostPublicKeyHex.lowercase() }
            .map { (_, result) -> message(result) }

    private class Member(nostrPrivateKey: ByteArray) {
        val hostSecretKey = ChillDkgRitualManager.deriveHostSecretKey(nostrPrivateKey)
        val hostPublicKeyHex = ChillDkgRitualManager.deriveHostPublicKey(nostrPrivateKey).value.toHex()

        // Stand in for DkgSession.round1Random / round2AuxRandom, which are fresh
        // per session but fixed for its lifetime.
        val round1Random = ByteVector32(fr.acinq.bitcoin.Crypto.sha256(nostrPrivateKey + 1))
        val round2AuxRandom = ByteVector32(fr.acinq.bitcoin.Crypto.sha256(nostrPrivateKey + 2))
    }

    private companion object {
        fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
