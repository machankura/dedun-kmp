package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.scannable
import com.machankura.dedun.compose.ui.theme.DedunTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a QR code is called, and what it encodes -- which are two different strings.
 *
 * Both were one string before Phase 4 of `docs/group-wavelength-redesign.md`: every one of the ten
 * call sites passed its payload as the content description, so a screen reader spelled out a bech32
 * address or a bolt11 invoice character by character. Sixty seconds of letters that say nothing about
 * which code a member is holding up, and the longest thing on the screen.
 */
@OptIn(ExperimentalTestApi::class)
class QRCodeViewJvmTest {

    @Test
    fun `the description is a name for the code, not the thing it encodes`() = runDesktopComposeUiTest(400, 600) {
        val payload = "bitcoin:bcrt1p" + "q".repeat(58)
        setContent { DedunTheme { QRCodeView(payload, "QR code for your Wavelength address") } }
        onNodeWithContentDescription("QR code for your Wavelength address").assertIsDisplayed()
        onAllNodesWithContentDescription(payload).assertCountEquals(0)
    }

    /**
     * bech32 is defined over one case, and uppercase lets the encoder use QR's alphanumeric mode --
     * a smaller, denser symbol that scans from further away. What a member reads and copies is the
     * text beside the code, untouched.
     */
    @Test
    fun `a bech32 payload is uppercased for the scanner`() {
        assertEquals("BCRT1PQQQQ", scannable("bcrt1pqqqq"))
        assertEquals("BITCOIN:BCRT1PQQQQ", scannable("bitcoin:bcrt1pqqqq"), "a URI scheme is case-insensitive")
        assertEquals("LIGHTNING:LNBC1THEINVOICE", scannable("lightning:lnbc1theinvoice"))
        assertEquals("NPUB1ABC", scannable("npub1abc"))
    }

    /**
     * Anything whose case might mean something is encoded exactly as given. A BIP 21 `?label=` is the
     * case that matters: uppercasing it would send somebody a different label.
     */
    @Test
    fun `a payload whose case means something is left alone`() {
        assertEquals("bitcoin:bcrt1pqqqq?label=ada", scannable("bitcoin:bcrt1pqqqq?label=ada"))
        assertEquals("bitcoin:bcrt1pQQQQ", scannable("bitcoin:bcrt1pQQQQ"), "mixed case is not bech32")
        assertEquals("some-other-payload", scannable("some-other-payload"), "a hyphen is not in the alphabet")
        assertEquals("", scannable(""))
    }
}
