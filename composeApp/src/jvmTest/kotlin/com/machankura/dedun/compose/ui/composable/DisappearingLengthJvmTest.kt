package com.machankura.dedun.compose.ui.composable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.machankura.dedun.compose.nostr.DisappearingMessages
import com.machankura.dedun.compose.ui.composable.DisappearingLength.Unit.DAYS
import com.machankura.dedun.compose.ui.composable.DisappearingLength.Unit.HOURS
import com.machankura.dedun.compose.ui.composable.DisappearingLength.Unit.MINUTES
import com.machankura.dedun.compose.ui.composable.DisappearingLength.Unit.WEEKS

/** docs/disappearing-messages.md, Phase 6: how a length reads, and what the custom dialog accepts (D3). */
class DisappearingLengthJvmTest {

    @Test
    fun `a length reads in the largest unit it is a whole number of`() {
        assertNull(DisappearingLength.split(DisappearingMessages.OFF))
        assertEquals(HOURS to 1L, DisappearingLength.split(DisappearingMessages.HOUR))
        assertEquals(HOURS to 8L, DisappearingLength.split(8 * DisappearingMessages.HOUR))
        assertEquals(DAYS to 1L, DisappearingLength.split(DisappearingMessages.DAY))
        assertEquals(WEEKS to 1L, DisappearingLength.split(7 * DisappearingMessages.DAY))
        assertEquals(WEEKS to 4L, DisappearingLength.split(28 * DisappearingMessages.DAY))
        assertEquals(MINUTES to 90L, DisappearingLength.split(90 * DisappearingMessages.MINUTE))
        assertEquals(DAYS to 365L, DisappearingLength.split(DisappearingMessages.MAX_SECONDS))
    }

    @Test
    fun `the custom dialog takes whole numbers within five minutes and a year`() {
        assertEquals(5 * DisappearingMessages.MINUTE, DisappearingLength.parse("5", MINUTES))
        assertEquals(36 * DisappearingMessages.HOUR, DisappearingLength.parse(" 36 ", HOURS))
        assertEquals(DisappearingMessages.MAX_SECONDS, DisappearingLength.parse("365", DAYS))
        assertNull(DisappearingLength.parse("4", MINUTES))
        assertNull(DisappearingLength.parse("366", DAYS))
        assertNull(DisappearingLength.parse("0", HOURS))
        assertNull(DisappearingLength.parse("", HOURS))
        assertNull(DisappearingLength.parse("1.5", HOURS))
        assertNull(DisappearingLength.parse("99999999999999", DAYS))
    }
}
