package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.extensions.toHex
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.machankura.dedun.compose.database.model.FrostSigningSession

/**
 * The derivation every member's device has to agree on, run against real FROST
 * tweaks.
 *
 * Nothing here checks arithmetic -- libsecp256k1 does that. What is checked is the
 * property the group depends on: two devices holding the same threshold key and
 * the same path arrive at the same key, without exchanging anything. If that ever
 * stops being true, members create rival admin rooms and neither can see the
 * other, so it is worth a test that fails loudly rather than a comment.
 */
class SharedKeyDerivationTest {
    /** Stands in for a ceremony's output. Any valid point will do. */
    private val thresholdPublicKey = PrivateKey(
        Hex.decode("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
    ).publicKey().value.toHex()

    @Test
    fun `the same key and path always give the same room`() {
        val first = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
        val second = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)

        assertEquals(first, second)
    }

    @Test
    fun `a derived room id is a 32-byte x-only key`() {
        val id = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)

        // Marmot wants 32 bytes for nostrGroupId and nostr wants 32 bytes for an
        // x-only key. That these are the same length is what lets the room's id and
        // the identity the group signs with be one value.
        assertEquals(64, id.length)
        assertTrue(id.all { it in "0123456789abcdef" }, "not lowercase hex: $id")
    }

    @Test
    fun `different paths give different keys`() {
        val admins = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, listOf(9420L, 0L, 0L))
        val sibling = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, listOf(9420L, 0L, 1L))
        val other = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, listOf(9421L, 0L, 0L))

        assertNotEquals(admins, sibling)
        assertNotEquals(admins, other)
    }

    @Test
    fun `each step commits to the key it tweaks so depth is not interchangeable`() {
        // Index 0 applied once is not index 0 applied three times: the scalar binds
        // the key being tweaked, so a shorter path cannot collide with a deeper one
        // that happens to end on the same indices.
        val shallow = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, listOf(0L))
        val deep = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, listOf(0L, 0L, 0L))

        assertNotEquals(shallow, deep)
    }

    @Test
    fun `a different group key gives a different room`() {
        val otherKey = PrivateKey(
            Hex.decode("2badc0de0000000000000000000000000000000000000000000000000000005b")
        ).publicKey().value.toHex()

        assertNotEquals(
            SharedKeyDerivation.marmotGroupId(thresholdPublicKey),
            SharedKeyDerivation.marmotGroupId(otherKey)
        )
    }

    @Test
    fun `a room description round-trips its path`() {
        val description = SharedKeyDerivation.describe("Admins of Ubuntu Collective.")

        assertTrue(description.startsWith("Admins of Ubuntu Collective."), description)
        assertEquals(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH, SharedKeyDerivation.parsePath(description))
    }

    @Test
    fun `a path survives someone editing the rest of the description`() {
        // The marker sits on its own line precisely so the prose around it is not
        // load-bearing -- a member renaming or rewriting the description should not
        // cost the group the record of how its key was derived.
        val edited = "Totally different wording.\n\n" +
            SharedKeyDerivation.describe("ignored").lines().last()

        assertEquals(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH, SharedKeyDerivation.parsePath(edited))
    }

    @Test
    fun `descriptions without a path parse to null rather than a guess`() {
        assertEquals(null, SharedKeyDerivation.parsePath(null))
        assertEquals(null, SharedKeyDerivation.parsePath(""))
        assertEquals(null, SharedKeyDerivation.parsePath("Admins of something."))
    }

    @Test
    fun `a hardened path is refused not silently walked`() {
        // Hardened derivation needs the parent private key, which nobody in a
        // threshold group has. A path claiming one was never walked, so acting on it
        // would mean deriving something different from what the room says.
        assertEquals(null, SharedKeyDerivation.parsePath("Shared key path: m/9420'/0/0"))
    }

    @Test
    fun `formatted paths read the way they are written`() {
        assertEquals("m/9420/0/0", SharedKeyDerivation.formatPath())
        assertEquals("m/9420/0/1", SharedKeyDerivation.formatPath(listOf(9420L, 0L, 1L)))
    }

    @Test
    fun `the cache tracks the same key the derivation returns`() {
        // A signing session is created from the cache, not from the public key, so
        // the two disagreeing would mean signatures verifying against something
        // other than the room's id.
        val derived = SharedKeyDerivation.derive(thresholdPublicKey)

        assertEquals(derived.publicKey, derived.cache.tweakedPublicKey)
    }

    @Test
    fun `a walk of no steps lands on the threshold key itself`() {
        // What a signing session with no path signs as: a row written before that
        // column existed, or a room that is not derived from the key at all. Both
        // have to reach the untweaked key, or a session caught by the migration
        // would change which key it signs as between two of its own rounds.
        val nowhere = SharedKeyDerivation.derive(thresholdPublicKey, emptyList())

        assertEquals(
            PublicKey(ByteVector(thresholdPublicKey.hexToByteArray())).xOnly().value.toHex(),
            nowhere.hex
        )
        assertEquals(nowhere.publicKey, nowhere.cache.tweakedPublicKey)

        // And it is not any room: the admin path walks somewhere else entirely.
        assertNotEquals(nowhere.hex, SharedKeyDerivation.marmotGroupId(thresholdPublicKey))
    }

    @Test
    fun `a session with no derivation path walks nowhere`() {
        // The column is nullable and null means "no derivation", which has to read
        // back as the empty walk above rather than as the default path.
        assertEquals(emptyList(), session(derivationPath = null).pathIndices())
        assertEquals(
            SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH,
            session(derivationPath = "m/9420/0/0").pathIndices()
        )
        // An unwalkable string is not a path anybody signed at either.
        assertEquals(emptyList(), session(derivationPath = "m/9420'/0/0").pathIndices())
    }

    private fun session(derivationPath: String?) = FrostSigningSession(
        id = "s".repeat(64),
        chatRoomId = "room",
        coordinatorPublicKey = "c".repeat(64),
        userPublicKey = "u".repeat(64),
        dkgSessionId = "k".repeat(64),
        threshold = 2,
        participantCount = 3,
        signerId = 0,
        derivationPath = derivationPath
    )
}
