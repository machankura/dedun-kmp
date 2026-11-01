package com.machankura.dedun.compose.text

import com.machankura.dedun.compose.text.LightningAddress.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every form D5 of `docs/group-payout-destination.md` accepts, and every one it refuses with its own reason. */
class LightningAddressTest {

    private fun address(input: String): LightningAddress = (LightningAddress.parse(input) as Parsed.Address).address

    @Test
    fun `a plain address reads`() {
        assertEquals(LightningAddress("alice", "example.com"), address("alice@example.com"))
        assertEquals("https://example.com/.well-known/lnurlp/alice", address("alice@example.com").wellKnownUrl)
    }

    @Test
    fun `LUD-16's whole alphabet is a name`() {
        assertEquals("a.b_c+d-1@pay.example.co.za", address("a.b_c+d-1@pay.example.co.za").toString())
    }

    @Test
    fun `case, spaces around it and the lightning scheme are forgiven`() {
        assertEquals("alice@example.com", address("  Alice@Example.COM \n").toString())
        assertEquals("alice@example.com", address("lightning:alice@example.com").toString())
        assertEquals("alice@example.com", address("LIGHTNING:alice@example.com").toString())
        assertEquals("alice@example.com", address("alice@example.com.").toString())
    }

    @Test
    fun `nothing is empty`() {
        assertEquals(Parsed.Empty, LightningAddress.parse(""))
        assertEquals(Parsed.Empty, LightningAddress.parse("   "))
    }

    @Test
    fun `what is not an address says so`() {
        listOf(
            "alice",
            "@example.com",
            "alice@",
            "alice@example",
            "alice@@example.com",
            "alice@exa mple.com",
            "alice@example.com bob@example.com",
            "alice@example.com\nsecond line",
            "al!ce@example.com",
            "alice@-example.com",
            "alice@example.com:8080",
            "alice@127.0.0.1",
            "https://example.com/.well-known/lnurlp/alice",
            "bitcoin:alice@example.com",
        ).forEach { assertEquals(Parsed.NotAnAddress, LightningAddress.parse(it), it) }
    }

    @Test
    fun `an LNURL is not an address`() {
        assertEquals(Parsed.Lnurl, LightningAddress.parse("LNURL1DP68GURN8GHJ7UM9WFMXJCM99E3K7MF0V9CXJ0M385EKVCENXC6R2C35XVUKXEFCV5MKVV34X5EKZD3EV56NYD3HXQURZEPEXEJXXEPNXSCRVWFNV9NXZCN9XQ6XYEFHVGCXXCMYXYMNSERXFQ5FNS"))
        assertEquals(Parsed.Lnurl, LightningAddress.parse("lnurlp://example.com/pay/alice"))
        assertEquals(Parsed.Lnurl, LightningAddress.parse("lightning:lnurl1dp68gurn8ghj7"))
    }

    @Test
    fun `an offer is not an address`() {
        assertEquals(Parsed.Offer, LightningAddress.parse("lno1pg257enxv4ezqcneype82um50ynhxgrwdajx283qfwdpl28qqmc78ymlvhmxcsywdk5wrjnj36jryg488qwlrnzyjczs"))
    }

    @Test
    fun `an invoice is not an address`() {
        assertEquals(Parsed.Invoice, LightningAddress.parse("lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq"))
        assertEquals(Parsed.Invoice, LightningAddress.parse("lightning:lntb1u1pwz5w78pp5e8w8cr5c30xzws92v36sk45znhjn098rtc4pea6ertnmvu25ng3sdpywd6hyetyvf5hgueqv3jk6meqd9h8vmmfvdjsxqrrssy29mzkzjfq27u67evzu893heqex737dhcapvcuantkztg6pnk77nrm72y7z0rs47wzc09vcnugk2ve6sr2ewvcrtqnh3yttv847qqvqpvv398"))
        assertEquals(Parsed.Invoice, LightningAddress.parse("lnbcrt10u1pnxyz"))
    }

    @Test
    fun `a BIP-353 name is not an address`() {
        assertEquals(Parsed.Bip353, LightningAddress.parse("₿alice@example.com"))
        assertEquals(Parsed.Bip353, LightningAddress.parse("%E2%82%BFalice@example.com"))
    }

    @Test
    fun `an onion domain is refused for its own reason`() {
        assertEquals(Parsed.OnionDomain, LightningAddress.parse("alice@pay2wqdkbyb6j4evl6fbhoy7n5l5yoofkjg7i6fmhkhtpt2vqfp3ad.onion"))
    }
}
