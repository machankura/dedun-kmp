package com.machankura.dedun.compose.managers

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The convention, checked at the one function that applies it.
 *
 * Idempotence is the property worth a test. The name a subgroup ends up with
 * passes through here four times -- typed, carried on the ceremony room, signed
 * into the birth certificate, normalised again at creation -- and a rule that
 * added a `#` each time would leave the room called something its parent never
 * certified.
 */
class MarmotGroupNameTest {
    @Test
    fun `a plain name gets the prefix`() {
        assertEquals("#Ekklesia", MarmotGroupName.of("Ekklesia"))
    }

    @Test
    fun `a name that already has the prefix keeps exactly one`() {
        assertEquals("#Ekklesia", MarmotGroupName.of("#Ekklesia"))
        assertEquals("#Ekklesia", MarmotGroupName.of("###Ekklesia"))
    }

    @Test
    fun `applying it twice says the same thing as applying it once`() {
        val once = MarmotGroupName.of("Translators")

        assertEquals(once, MarmotGroupName.of(once))
    }

    @Test
    fun `surrounding space is not part of the name`() {
        assertEquals("#Ekklesia", MarmotGroupName.of("  Ekklesia  "))
        assertEquals("#Ekklesia", MarmotGroupName.of("# Ekklesia"))
    }

    @Test
    fun `space inside the name is`() {
        assertEquals("#Book club", MarmotGroupName.of("Book club"))
    }

    @Test
    fun `a blank name is left alone rather than turned into a bare hash`() {
        assertEquals("", MarmotGroupName.of("   "))
        assertEquals("#", MarmotGroupName.of("#"))
    }
}
