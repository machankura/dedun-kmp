package com.machankura.dedun.compose.text

import androidx.compose.foundation.text.input.TextFieldState
import fr.acinq.bitcoin.Satoshi
import kotlin.test.Test
import kotlin.test.assertEquals

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
