package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OP_2
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** An onchain payout address, read for one chain: the five templates, the other network, and nonsense. */
class PayoutAddressTest {
    private val key = PrivateKey(ByteVector32(Hex.decode("a11ce".padStart(64, '0')))).publicKey()

    private fun accepted(chain: Chain, address: String) {
        val parsed = assertNotNull(PayoutAddress.parseOrNull(chain, address), address)
        assertEquals(address, parsed.address)
    }

    @Test
    fun `the five templates a wallet hands out are read on their own chain`() {
        listOf(Chain.Mainnet, Chain.Regtest, Chain.Signet).forEach { chain ->
            val hash = chain.chainHash
            accepted(chain, Bitcoin.computeP2PkhAddress(key, hash))
            accepted(chain, Bitcoin.computeP2ShOfP2WpkhAddress(key, hash))
            accepted(chain, Bitcoin.computeP2WpkhAddress(key, hash))
            accepted(chain, Bitcoin.computeBIP86Address(key, hash))
            accepted(chain, Bitcoin.addressFromPublicKeyScript(hash, Script.pay2wsh(Script.pay2pkh(key))).right!!)
        }
    }

    @Test
    fun `another network's address is told apart from nonsense`() {
        val signet = Bitcoin.computeBIP86Address(key, Chain.Signet.chainHash)
        assertEquals(PayoutAddress.Problem.OtherNetwork, PayoutAddress.parse(Chain.Mainnet, signet).left)
        val mainnet = Bitcoin.computeP2PkhAddress(key, Chain.Mainnet.chainHash)
        assertEquals(PayoutAddress.Problem.OtherNetwork, PayoutAddress.parse(Chain.Regtest, mainnet).left)
        assertEquals(PayoutAddress.Problem.NotAnAddress, PayoutAddress.parse(Chain.Mainnet, "bc1qnotanaddress").left)
        assertEquals(PayoutAddress.Problem.NotAnAddress, PayoutAddress.parse(Chain.Mainnet, "alice@example.com").left)
        assertEquals(PayoutAddress.Problem.Empty, PayoutAddress.parse(Chain.Mainnet, "  ").left)
    }

    @Test
    fun `a bitcoin URI gives its address and loses its query`() {
        val address = Bitcoin.computeP2WpkhAddress(key, Chain.Mainnet.chainHash)
        assertEquals(address, PayoutAddress.parseOrNull(Chain.Mainnet, "bitcoin:$address?amount=0.001&label=x")?.address)
        assertEquals(address, PayoutAddress.parseOrNull(Chain.Mainnet, "  BITCOIN:$address ")?.address)
    }

    @Test
    fun `an upper-case bech32 address is written down in lower case`() {
        val address = Bitcoin.computeBIP86Address(key, Chain.Mainnet.chainHash)
        assertEquals(address, PayoutAddress.parseOrNull(Chain.Mainnet, address.uppercase())?.address)
    }

    @Test
    fun `a segwit version nothing can spend yet is not an address`() {
        val future = Bitcoin.addressFromPublicKeyScript(Chain.Mainnet.chainHash, listOf(OP_2, OP_PUSHDATA(ByteArray(32) { 1 }))).right!!
        assertEquals(PayoutAddress.Problem.NotAnAddress, PayoutAddress.parse(Chain.Mainnet, future).left)
    }

    @Test
    fun `two addresses in one field are not an address`() {
        val address = Bitcoin.computeP2WpkhAddress(key, Chain.Mainnet.chainHash)
        assertEquals(PayoutAddress.Problem.NotAnAddress, PayoutAddress.parse(Chain.Mainnet, "$address $address").left)
    }
}
