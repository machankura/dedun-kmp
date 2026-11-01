package com.machankura.dedun.compose.managers

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.TweakCache
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The group wallet's derivation, pinned.
 *
 * Three kinds of assertion, and they guard different things. BIP86's own vectors
 * prove the tail *is* BIP86. The pinned vector freezes the account step -- the
 * tag, the byte order of the chain hash, the order of the HMAC's inputs -- so a
 * change to any of them fails here rather than moving a group's coins. And the
 * parity sweep exists because a parity mistake in a cache passes half the time.
 */
class GroupWalletDerivationTest {
    /** A threshold key nobody needs the secret of: the test is about public derivation. */
    private fun thresholdKey(seed: Int): String =
        PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0')))).publicKey().toString()

    private val k = thresholdKey(0x1c0ffee)

    @Test
    fun `the tail below an account key is BIP86's`() {
        // m/86'/0'/0' of "abandon ... about", from BIP86 itself.
        val (_, accountKey) = DeterministicWallet.ExtendedPublicKey.decode(
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
        )
        val cache = TweakCache.create(accountKey.publicKey)

        val vectors = listOf(
            Triple(0L to 0L, "cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115", "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"),
            Triple(0L to 1L, "83dfe85a3151d2517290da461fe2815591ef69f2b18a2ce63f01697a8b313145", "bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh"),
            Triple(1L to 0L, "399f1b2f4393f29a18c937859c5dd8a77350103157eb880f02e8c08214277cef", "bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7"),
        )

        vectors.forEach { (path, internalKey, address) ->
            val derived = GroupWalletDerivation.deriveBelow(accountKey, cache, path.first, path.second)
            assertEquals(internalKey, derived.internalKey.value.toHex(), "internal key at ${path.first}/${path.second}")
            assertEquals(address, derived.address(Chain.Mainnet), "address at ${path.first}/${path.second}")
            // Through the cache, not only the points: this is the key a session signs for.
            assertEquals(derived.outputKey, derived.cache.tweakedPublicKey)
        }
    }

    @Test
    fun `the account step is pinned`() {
        val mainnet = GroupWalletDerivation.account(k, Chain.Mainnet)
        val regtest = GroupWalletDerivation.account(k, Chain.Regtest)
        val second = GroupWalletDerivation.account(k, Chain.Mainnet, account = 1)

        // Frozen. A failure here is not a test to update: it means every group's
        // coins would be at different addresses after this change, and the ones
        // already received would be found by nobody.
        assertEquals(
            "xpub661MyMwAqRbcEjmHgZBQ6WrHydBtiy96F6Lh8tj8vTzDYvMyQoqe1yLFytfUoqEkwRd9j8xqY1PZD25cnXn5LjMh5QLbywNqV3qse8Lxfdu",
            mainnet.xpub
        )
        assertEquals(
            "tr([935b59f0]xpub661MyMwAqRbcEjmHgZBQ6WrHydBtiy96F6Lh8tj8vTzDYvMyQoqe1yLFytfUoqEkwRd9j8xqY1PZD25cnXn5LjMh5QLbywNqV3qse8Lxfdu/0/*)#mfhfa9w0",
            mainnet.receiveDescriptor
        )
        assertEquals(
            "tr([935b59f0]xpub661MyMwAqRbcEjmHgZBQ6WrHydBtiy96F6Lh8tj8vTzDYvMyQoqe1yLFytfUoqEkwRd9j8xqY1PZD25cnXn5LjMh5QLbywNqV3qse8Lxfdu/1/*)#2ajgqs7h",
            mainnet.changeDescriptor
        )
        assertEquals(
            "bc1p5v8nnu750wmu3sg5yee2kmj466d44dmr2cd0e0a4frwyqessayhqs442qs",
            GroupWalletDerivation.derive(mainnet, 0, 0).address(Chain.Mainnet)
        )

        assertEquals(
            "tpubD6NzVbkrYhZ4WoTbDgosM5KFsbzdUShJQHoMYsCSeSSTtniVCq92YbNb3vt2Ywg4njZ2JNeFtNbQStabeYpnXAUVzxYm9behJDvp5YhTvgE",
            regtest.xpub
        )
        assertEquals(
            "bcrt1pkr33dle8ttv5p96se2dl0ujqxhkuxpw0cgapaa7g0578g5nctr9qwsyt55",
            GroupWalletDerivation.derive(regtest, 0, 0).address(Chain.Regtest)
        )

        assertEquals(
            "xpub661MyMwAqRbcF6PNpyzQvz1jPnpXZ785MJciQPZwyP2AhD9Gs6A2Acn9BnPHb2TUWku5CDARAwCpioDdPvrmjXWcAQbHWCXUZhW1VRyxDA2",
            second.xpub
        )
        assertEquals(
            "bc1ptkqtyu8z5qm2l25p26nfr5ahugjw87vgc4yudevveysjkytw7caqtfjgrg",
            GroupWalletDerivation.derive(second, 0, 0).address(Chain.Mainnet)
        )
    }

    @Test
    fun `both parities derive, at the account and below it`() {
        val accountParities = mutableSetOf<Boolean>()
        val childParities = mutableSetOf<Boolean>()

        (1..16).forEach { seed ->
            val account = GroupWalletDerivation.account(thresholdKey(seed), Chain.Mainnet)
            accountParities += account.extendedPublicKey.publicKey.isEven()

            (0L..3L).forEach { index ->
                listOf(GroupWalletDerivation.RECEIVE, GroupWalletDerivation.CHANGE).forEach { change ->
                    // derive refuses to return if the cache and the points disagree,
                    // so reaching the end of this loop is the assertion.
                    val derived = GroupWalletDerivation.derive(account, change, index)
                    childParities += account.extendedPublicKey.derivePublicKey(change).derivePublicKey(index).publicKey.isEven()
                    assertEquals(derived.outputKey, derived.cache.tweakedPublicKey)
                }
            }
        }

        assertEquals(setOf(true, false), accountParities, "the sweep has to cover both parities of the account key")
        assertEquals(setOf(true, false), childParities, "the sweep has to cover both parities of a child key")
    }

    @Test
    fun `the wallet shares no key with the room or with itself`() {
        val room = SharedKeyDerivation.derive(k).publicKey
        val mainnet = GroupWalletDerivation.account(k, Chain.Mainnet)
        val receive = GroupWalletDerivation.derive(mainnet, GroupWalletDerivation.RECEIVE, 0)

        assertNotEquals(room, receive.outputKey, "the room's key")
        assertNotEquals(room, receive.internalKey, "the room's key, untweaked")
        assertNotEquals(
            mainnet.extendedPublicKey.publicKey.xOnly(),
            fr.acinq.bitcoin.PublicKey.fromHex(k).xOnly(),
            "the account key is not the threshold key, so the xpub does not reveal it"
        )

        val keys = listOf(
            receive.outputKey,
            GroupWalletDerivation.derive(mainnet, GroupWalletDerivation.CHANGE, 0).outputKey,
            GroupWalletDerivation.derive(mainnet, GroupWalletDerivation.RECEIVE, 1).outputKey,
            GroupWalletDerivation.derive(GroupWalletDerivation.account(k, Chain.Regtest), GroupWalletDerivation.RECEIVE, 0).outputKey,
            GroupWalletDerivation.derive(GroupWalletDerivation.account(k, Chain.Mainnet, account = 1), GroupWalletDerivation.RECEIVE, 0).outputKey,
        )
        assertEquals(keys.size, keys.toSet().size, "receive, change, index, network and account each give a different key")
    }

    @Test
    fun `hardened and out-of-range steps are refused`() {
        val account = GroupWalletDerivation.account(k, Chain.Mainnet)

        assertFailsWith<IllegalArgumentException> { GroupWalletDerivation.derive(account, 2, 0) }
        assertFailsWith<IllegalArgumentException> { GroupWalletDerivation.derive(account, 0, DeterministicWallet.hardened(0)) }
        assertFailsWith<IllegalArgumentException> { GroupWalletDerivation.derive(account, 0, -1) }
        assertFailsWith<IllegalArgumentException> { GroupWalletDerivation.account(k, Chain.Mainnet, DeterministicWallet.hardened(0)) }
    }

    @Test
    fun `descriptors carry the account's own fingerprint and a checksum`() {
        val account = GroupWalletDerivation.account(k, Chain.Mainnet)
        val pattern = Regex("""tr\(\[[0-9a-f]{8}]xpub[1-9A-HJ-NP-Za-km-z]+/[01]/\*\)#[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{8}""")

        assertTrue(pattern.matches(account.receiveDescriptor), account.receiveDescriptor)
        assertTrue(pattern.matches(account.changeDescriptor), account.changeDescriptor)
        assertTrue(GroupWalletDerivation.account(k, Chain.Regtest).xpub.startsWith("tpub"))
    }

    private fun ByteVector32.toHex(): String = toString()
}
