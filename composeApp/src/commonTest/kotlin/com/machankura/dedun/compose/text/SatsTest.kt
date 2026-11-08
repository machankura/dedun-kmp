package com.machankura.dedun.compose.text

import androidx.compose.foundation.text.input.TextFieldState
import fr.acinq.bitcoin.Satoshi
import fr.acinq.phoenix.data.ExchangeRate
import fr.acinq.phoenix.data.FiatCurrency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An amount typed in sats: read when it can only mean one number, refused when
 * it could mean two. The cases that matter are the decimals -- a reader that
 * drops the separator multiplies the amount by ten for every digit after it.
 */
class SatsTest {
    private fun sats(amount: Long) = SatsInput.Sats(Satoshi(amount))

    @Test
    fun `grouped and ungrouped amounts read the same`() {
        assertEquals(sats(40_000), parseSats("40000"))
        assertEquals(sats(40_000), parseSats("40,000"))
        assertEquals(sats(40_000), parseSats("40 000"))
        assertEquals(sats(40_000), parseSats("40 000"))
        assertEquals(sats(40_000), parseSats("40 000"))
        assertEquals(sats(40_000), parseSats("40'000"))
        assertEquals(sats(40_000), parseSats("40_000"))
        assertEquals(sats(1_250_000), parseSats(" 1,250,000 "))
        assertEquals(sats(7), parseSats("007"))
        assertEquals(sats(0), parseSats("0"))
    }

    @Test
    fun `a decimal is refused rather than dropped`() {
        assertEquals(SatsInput.NotWhole, parseSats("21000.5"), "not 210,005")
        assertEquals(SatsInput.NotWhole, parseSats("0.0005"), "a bitcoin amount, not 5 sats")
        assertEquals(SatsInput.NotWhole, parseSats("40,5"), "forty and a half where a comma is the decimal point")
        assertEquals(SatsInput.NotWhole, parseSats("40,50"))
        assertEquals(SatsInput.NotWhole, parseSats("1 000,5"))
    }

    @Test
    fun `grouping that groups nothing, and letters, are unreadable`() {
        assertEquals(SatsInput.Unreadable, parseSats("40,0000"))
        assertEquals(SatsInput.Unreadable, parseSats("4 0"))
        assertEquals(SatsInput.Unreadable, parseSats("1234,567"))
        assertEquals(SatsInput.Unreadable, parseSats("40,,000"))
        assertEquals(SatsInput.Unreadable, parseSats(",500"))
        assertEquals(SatsInput.Unreadable, parseSats("40k"))
        assertEquals(SatsInput.Unreadable, parseSats("-40"))
        assertEquals(SatsInput.Unreadable, parseSats("٤٠"), "digits, but not ones the amount can be written in")
    }

    @Test
    fun `nothing typed is empty, not zero`() {
        assertEquals(SatsInput.Empty, parseSats(""))
        assertEquals(SatsInput.Empty, parseSats("   "))
    }

    @Test
    fun `there are only so many sats`() {
        assertEquals(sats(MAX_SATS.sat), parseSats("2,100,000,000,000,000"))
        assertEquals(SatsInput.TooLarge, parseSats("2100000000000001"))
        assertEquals(SatsInput.TooLarge, parseSats("9".repeat(40)), "past a Long, without overflowing")
        assertEquals(sats(5), parseSats("0".repeat(40) + "5"), "leading zeros are not digits that count")
    }

    // ---------------------------------------------------------------------
    // The local-currency line.
    // ---------------------------------------------------------------------

    private fun rate(price: Double, currency: FiatCurrency = FiatCurrency.ZAR) =
        ExchangeRate.BitcoinPriceRate(fiatCurrency = currency, price = price, source = "test", timestampMillis = 0)

    /** One sat at [price] per bitcoin, which is how the guide's sub-unit examples are reached. */
    private fun oneSatAt(price: Double) = Satoshi(1).inLocalCurrency(rate(price, FiatCurrency.USD))

    /**
     * The guide's four round values, which keep the digits they have and take no `~`.
     *
     * https://bitcoin.design/guide/designing-products/units-and-symbols/#formatting-very-small-amounts
     * `$0.3 -> $0.30` (at least 2 digits because cent are based on 100), `$0.03 -> $0.03`,
     * `$0.003 -> $0.003` (always show the digit if lower than 1/100), `$0.0003 -> $0.0003`.
     */
    @Test
    fun `a round value keeps its own digits, and at least two of them`() {
        assertEquals("0.30 USD", oneSatAt(30_000_000.0), "at least two digits, because cents are hundredths")
        assertEquals("0.03 USD", oneSatAt(3_000_000.0), "not padded to 0.030")
        assertEquals("0.003 USD", oneSatAt(300_000.0), "the digit is shown although it is below 1/100")
        assertEquals("0.0003 USD", oneSatAt(30_000.0))
    }

    /**
     * The guide's four uneven values, rounded to two non-zero digits and marked.
     *
     * `$0.38878830 -> ~$0.39`, `$0.038878830 -> ~$0.039`, `$0.0038878830 -> ~$0.0039`,
     * `$0.00038878830 -> ~$0.00039`. Two *non-zero* digits, not two decimal places: the second
     * example has three places and the fourth has five.
     */
    @Test
    fun `an uneven value rounds to two non-zero digits and says it was rounded`() {
        assertEquals("~0.39 USD", oneSatAt(38_878_830.0))
        assertEquals("~0.039 USD", oneSatAt(3_887_883.0))
        assertEquals("~0.0039 USD", oneSatAt(388_788.30))
        assertEquals("~0.00039 USD", oneSatAt(38_878.830))
    }

    /**
     * The value the guide names as the problem: "€0.0000431 is rounded to €0.00 by most formatting
     * libraries". It gives no corrected output, so this is the guide's own two-non-zero-digit rule
     * applied to it -- which is the whole reason this function exists rather than a currency formatter.
     */
    @Test
    fun `a value far below one cent is not rounded away to zero`() {
        assertEquals("~0.000043 USD", oneSatAt(4_310.0))
    }

    /**
     * At or above one unit the guide is silent and this is the app's rule: two decimal places,
     * grouped, with the mark only where rounding lost something.
     */
    @Test
    fun `at or above one unit it is two decimal places, grouped`() {
        assertEquals("200.00 ZAR", Satoshi(10_000).inLocalCurrency(rate(2_000_000.0)), "exact, so no mark")
        assertEquals("246.90 ZAR", Satoshi(12_345).inLocalCurrency(rate(2_000_000.0)), "the trailing zero stays: two places is the floor")
        assertEquals("~1,234.57 ZAR", Satoshi(100_000).inLocalCurrency(rate(1_234_567.0)))
        assertEquals("0.00 ZAR", Satoshi(0).inLocalCurrency(rate(2_000_000.0)), "nothing is exactly nothing")
    }

    @Test
    fun `the currency is the rate's, said as its code`() {
        assertEquals("200.00 USD", Satoshi(10_000).inLocalCurrency(rate(2_000_000.0, FiatCurrency.USD)))
        assertEquals("200.00 EUR", Satoshi(10_000).inLocalCurrency(rate(2_000_000.0, FiatCurrency.EUR)))
    }

    /** Nothing rather than a guess: this line is never the figure a decision reads. */
    @Test
    fun `no rate, and no rate worth the name, show nothing at all`() {
        assertNull(Satoshi(10_000).inLocalCurrency(null))
        assertNull(Satoshi(10_000).inLocalCurrency(rate(0.0)), "a price of zero is not a rate")
        assertNull(Satoshi(10_000).inLocalCurrency(rate(-1.0)))
        assertNull(Satoshi(10_000).inLocalCurrency(rate(Double.NaN)))
        assertNull(Satoshi(10_000).inLocalCurrency(rate(Double.POSITIVE_INFINITY)))
    }

    /**
     * Past ten trillion of a currency the scaled integer no longer fits a double exactly, so the
     * figure would be wrong in its last digits. Nothing rather than a wrong figure. The group's
     * mainnet cap is 100,000 sats, so this is reachable only by a broken rate.
     */
    @Test
    fun `a figure too large to be held exactly is not shown`() {
        assertNull(MAX_SATS.inLocalCurrency(rate(2_000_000.0)))
        assertEquals("21,000,000.00 ZAR", MAX_SATS.inLocalCurrency(rate(1.0)), "the same amount under a rate that fits")
    }

    /** The sign leads the digits, and the mark leads the sign. */
    @Test
    fun `a negative amount keeps its sign`() {
        assertEquals("~-0.39 USD", Satoshi(-1).inLocalCurrency(rate(38_878_830.0, FiatCurrency.USD)))
    }

    private fun shown(typed: String): String {
        val state = TextFieldState(typed)
        state.edit { with(GroupedSats) { transformOutput() } }
        return state.text.toString()
    }

    @Test
    fun `the field shows plain digits grouped, and anything else as it was typed`() {
        assertEquals("400", shown("400"))
        assertEquals("4,000", shown("4000"))
        assertEquals("40,000", shown("40000"))
        assertEquals("1,250,000", shown("1250000"))
        assertEquals("40,5", shown("40,5"), "regrouping would show a number it is not")
        assertEquals("21000.5", shown("21000.5"))
        assertEquals("", shown(""))
    }
}
