package com.machankura.dedun.compose.managers

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The convention, checked at the one function that applies it.
 *
 * Idempotence is the property worth a test. The name a subgroup ends up with
 * passes through here four times -- typed, carried on the ceremony room, signed
 * into the birth certificate, normalised again at creation -- and a rule that
 * added an `&` each time would leave the room called something its parent never
 * certified.
 *
 * The `#` inputs are the mark rooms carried before docs/group-name-prefix.md, on
 * purpose: they are what an older Dedun or a Dedun peer still mints.
 */
class MarmotGroupNameTest {
    @Test
    fun `a plain name gets the prefix`() {
        assertEquals("&Ekklesia", MarmotGroupName.of("Ekklesia"))
    }

    @Test
    fun `a name that already has the prefix keeps exactly one`() {
        assertEquals("&Ekklesia", MarmotGroupName.of("&Ekklesia"))
        assertEquals("&Ekklesia", MarmotGroupName.of("&&&Ekklesia"))
    }

    @Test
    fun `the old mark is replaced rather than kept`() {
        assertEquals("&Ekklesia", MarmotGroupName.of("#Ekklesia"))
        assertEquals("&Ekklesia", MarmotGroupName.of("###Ekklesia"))
        assertEquals("&Ekklesia", MarmotGroupName.of("# & #Ekklesia"))
    }

    @Test
    fun `an old name and a new name normalise to the same thing`() {
        assertEquals(MarmotGroupName.of("&Translators"), MarmotGroupName.of("#Translators"))
    }

    @Test
    fun `applying it twice says the same thing as applying it once`() {
        val once = MarmotGroupName.of("Translators")

        assertEquals(once, MarmotGroupName.of(once))
    }

    @Test
    fun `surrounding space is not part of the name`() {
        assertEquals("&Ekklesia", MarmotGroupName.of("  Ekklesia  "))
        assertEquals("&Ekklesia", MarmotGroupName.of("& Ekklesia"))
        assertEquals("&Ekklesia", MarmotGroupName.of("# Ekklesia"))
    }

    @Test
    fun `space inside the name is`() {
        assertEquals("&Book club", MarmotGroupName.of("Book club"))
    }

    @Test
    fun `only a leading mark is a mark`() {
        assertEquals("&Arts & crafts", MarmotGroupName.of("Arts & crafts"))
        assertEquals("&Room #2", MarmotGroupName.of("#Room #2"))
    }

    @Test
    fun `a blank name is left alone rather than turned into a bare mark`() {
        assertEquals("", MarmotGroupName.of("   "))
        assertEquals("&", MarmotGroupName.of("&"))
        assertEquals("#", MarmotGroupName.of("#"))
    }
}
