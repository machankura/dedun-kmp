package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi
import kotlin.test.Test
import kotlin.test.assertEquals

/** The deadline the wallet screen states -- `docs/bark-wallet.md`, D12. */
class ArkExpiryTest {
    @Test
    fun `the threshold is Bark's own default, a day on mainnet and 12 blocks elsewhere`() {
        assertEquals(144, ArkExpiry.thresholdFor(ArkNetwork.Mainnet))
        assertEquals(12, ArkExpiry.thresholdFor(ArkNetwork.Signet))
        assertEquals(12, ArkExpiry.thresholdFor(ArkNetwork.Regtest))
    }

    @Test
    fun `fine, then renew soon from the threshold, then expired at the height`() {
        assertEquals(ArkExpiry.Nothing, ArkExpiry.of(null, 900_000, 144))
        assertEquals(ArkExpiry.Fine(145), ArkExpiry.of(900_145, 900_000, 144))
        assertEquals(ArkExpiry.RenewSoon(144), ArkExpiry.of(900_144, 900_000, 144))
        assertEquals(ArkExpiry.RenewSoon(1), ArkExpiry.of(900_001, 900_000, 144))
        assertEquals(ArkExpiry.Expired, ArkExpiry.of(900_000, 900_000, 144))
        assertEquals(ArkExpiry.Expired, ArkExpiry.of(899_000, 900_000, 144))
    }

    @Test
    fun `pending money is every bucket but the spendable one and the onchain wallet`() {
        val balance = ArkBalance(
            spendable = Satoshi(1_000),
            pendingLightningSend = Satoshi(1),
            claimableLightningReceive = Satoshi(2),
            pendingInRound = Satoshi(4),
            pendingBoard = Satoshi(8),
            pendingExit = Satoshi(16),
            pendingOffboard = Satoshi(32),
            pendingArkSend = Satoshi(64),
            onchain = Satoshi(5_000),
        )
        assertEquals(Satoshi(127), balance.pending)
    }

    @Test
    fun `a request pays by its own preference when it can, and by the first usable option when not`() {
        val ark = ArkPaymentOption(ArkPaymentMethod.ArkAddress("tark1"), requiresAmount = true, problems = listOf("another server"))
        val lightning = ArkPaymentOption(ArkPaymentMethod.Bolt11("lntbs1"), requiresAmount = false)
        assertEquals(lightning, ArkPaymentRequest(null, null, listOf(ark, lightning), preferred = ark).chosen)
        assertEquals(lightning, ArkPaymentRequest(null, null, listOf(ark, lightning), preferred = null).chosen)
        assertEquals(ArkRail.Lightning, lightning.method.rail)
    }
}
