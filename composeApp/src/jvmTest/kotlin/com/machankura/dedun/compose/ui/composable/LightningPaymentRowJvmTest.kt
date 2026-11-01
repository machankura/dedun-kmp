package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.GroupLightningPaymentRow
import com.machankura.dedun.compose.ui.theme.DedunTheme
import kotlin.test.Test

/**
 * The Lightning wallet's payment list says what the node's payments were, as lightning-kmp reports
 * them: a sent amount with its fee already in it, and a channel's close filed as an outgoing payment
 * with no payment hash. Found by the regtest run, which listed a close as "sent" and 20,000 sats with
 * an 84-sat fee as 20,084 sent. docs/group-lightning-transcript.md.
 */
@OptIn(ExperimentalTestApi::class)
class LightningPaymentRowJvmTest {

    private fun row(id: String, incoming: Boolean, amountMsat: Long, feesMsat: Long, paymentHash: String?, succeeded: Boolean) =
        GroupLightningPaymentRow("w", id, incoming, amountMsat, feesMsat, paymentHash, description = null, completedAt = null, succeeded = succeeded)

    @Test
    fun `a payment sent is what reached the recipient, and a close is said as a close`() = runDesktopComposeUiTest {
        setContent {
            DedunTheme {
                Column {
                    PaymentRow(row("in", incoming = true, amountMsat = 96_900_000, feesMsat = 0, paymentHash = "aa".repeat(32), succeeded = true))
                    PaymentRow(row("out", incoming = false, amountMsat = 20_084_000, feesMsat = 84_000, paymentHash = "bb".repeat(32), succeeded = true))
                    PaymentRow(row("closed", incoming = false, amountMsat = 76_816_000, feesMsat = 0, paymentHash = null, succeeded = true))
                    PaymentRow(row("closing", incoming = false, amountMsat = 50_000_000, feesMsat = 0, paymentHash = null, succeeded = false))
                }
            }
        }
        onNodeWithText("Received 96,900 sats").assertIsDisplayed()
        onNodeWithText("Sent 20,000 sats, with a fee of 84 sats").assertIsDisplayed()
        onNodeWithText("A channel closed: 76,816 sats to the onchain wallet").assertIsDisplayed()
        onNodeWithText("A channel is closing: 50,000 sats to the onchain wallet").assertIsDisplayed()
    }

    @Test
    fun `bought liquidity is room to be paid, with what it cost, never money sent`() = runDesktopComposeUiTest {
        setContent {
            DedunTheme {
                PaymentRow(GroupLightningPaymentRow("w", "liquidity:t", incoming = false, amountMsat = 2_521_000, feesMsat = 2_521_000, paymentHash = null, description = null, completedAt = null, succeeded = true, liquiditySat = 200_000))
            }
        }
        onNodeWithText("Inbound liquidity added: 200,000 sats, for 2,521 sats in fees").assertIsDisplayed()
    }

    @Test
    fun `why liquidity cannot be asked for is said in words`() = runDesktopComposeUiTest {
        setContent {
            DedunTheme {
                Column {
                    androidx.compose.material3.Text(unavailableBecause(com.machankura.dedun.compose.wallet.lightning.LiquidityPrice.Availability.NoChannel))
                    androidx.compose.material3.Text(unavailableBecause(com.machankura.dedun.compose.wallet.lightning.LiquidityPrice.Availability.AtTheCap))
                    androidx.compose.material3.Text(unavailableBecause(com.machankura.dedun.compose.wallet.lightning.LiquidityPrice.Availability.NoPrices))
                }
            }
        }
        onNodeWithText("Receive a payment first: it opens the channel.").assertIsDisplayed()
        onNodeWithText("The channel holds the most a channel may on this network for now.").assertIsDisplayed()
        onNodeWithText("The operator hasn't reported what the LSP sells yet.").assertIsDisplayed()
    }
}
