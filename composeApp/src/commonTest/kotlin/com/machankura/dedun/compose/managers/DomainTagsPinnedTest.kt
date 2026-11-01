package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hash domain separators that had no vector of their own, pinned.
 *
 * Each tag is an input to a hash whose output is already on the relays or in a
 * group's key: the Iceberg DKG's session, commitments, seeds and transcript, the
 * co-signers' pool labels, a member wallet's id and the possession proof its
 * request carries. They still say `bennu/` after the app stopped being Bennu,
 * and that is on purpose -- renaming one is a protocol fork, not a rebrand. A
 * failure here is not a test to update: it means a tag, or the order of its
 * inputs, moved.
 */
class DomainTagsPinnedTest {
    private fun key(seed: Int): PrivateKey =
        PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private fun bytes32(fill: Int): ByteVector32 = ByteVector32(ByteArray(32) { fill.toByte() })

    private val params = IcebergDkg.Params.of(t = 2, hostKeys = (1..4).map { key(0x100 + it).publicKey() }, ceremonyId = bytes32(0x11))
    private val rank = params.heldRanks(1).first()
    private fun root(participant: Int) = bytes32(0x20 + participant)

    @Test
    fun `the iceberg session tag is pinned`() {
        assertEquals(PINNED_SESSION, params.sessionId.toHex())
    }

    @Test
    fun `the iceberg contribution tag is pinned`() {
        assertEquals(PINNED_CONTRIBUTION, IcebergDkg.contribution(params, 1, root(1), rank).toHex())
    }

    @Test
    fun `the iceberg commit tag is pinned`() {
        assertEquals(PINNED_COMMIT, IcebergDkg.commitment(params, 1, rank, bytes32(0x33)).toHex())
    }

    @Test
    fun `the iceberg seed tag is pinned`() {
        assertEquals(PINNED_SEED, IcebergDkg.seed(params, rank, listOf(bytes32(0x44), bytes32(0x55))).toHex())
    }

    @Test
    fun `the iceberg transcript tag is pinned`() {
        val commitments = (1..params.n).associateWith { IcebergDkg.round1(params, it, root(it)) }
        assertEquals(PINNED_TRANSCRIPT, IcebergDkg.transcriptHash(params, commitments).toHex())
    }

    @Test
    fun `the iceberg pool tag is pinned`() {
        assertEquals(PINNED_POOL, GroupLightningCoSigner.poolLabel(key(0x600d).publicKey().toString(), counter = 7).toHex())
    }

    @Test
    fun `the member wallet id tag is pinned`() {
        assertEquals(PINNED_WALLET_ID, MemberWalletDerivation.walletId(key(0xbeef).publicKey()))
    }

    @Test
    fun `the member wallet possession tag is pinned`() {
        val message = MemberWalletRequestEvent.possessionMessage(
            memberPublicKey = key(0xcafe).publicKey().toString().drop(2),
            groupChatRoomId = "ab".repeat(32),
            walletKey = key(0xbeef).publicKey(),
            chain = Chain.Mainnet,
        )
        assertEquals(PINNED_POSSESSION, message.toHex())
    }

    private companion object {
        const val PINNED_SESSION = "7b5df627e6023a515f4a57acd1d665406b8edeb9858e1b3b87e0c97e4f6ade6a"
        const val PINNED_CONTRIBUTION = "ffc9bf153e43cbc0cf45216905854f92b29f78b42c36385f37ea4e6db6a35924"
        const val PINNED_COMMIT = "4608ba204c8f66d9b0132b6b815cd7408c03b3483a199c0edd1d686f9e8e9557"
        const val PINNED_SEED = "4827d3758d9ce088164e6f4bc0ba25901ec4cd595849fc65e900491dc33ee48d"
        const val PINNED_TRANSCRIPT = "4973bbc0ab699609bf5c0debf9df4829fd78ef547fd70a3996de5e6c2c1407f8"
        const val PINNED_POOL = "08c292efb5c01f62f9246fd2525103620a5e746a4378ba2a5c669c431c5a6f84"
        const val PINNED_WALLET_ID = "7278e3ae36424b9cfb20b94de872f6ec83925c0d8f32022ad4cf3586c13a09ff"
        const val PINNED_POSSESSION = "b2f82b82caf1c409354185d2b09b2e3220a637b04f6d54882b002d71d1f9d425"
    }
}
