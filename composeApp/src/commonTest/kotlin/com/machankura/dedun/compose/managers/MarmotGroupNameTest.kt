package com.machankura.dedun.compose.managers

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The convention, checked at the functions that apply it: [MarmotGroupName.bare] for what
 * is stored, [MarmotGroupName.display] for what is drawn (docs/group-name-braces.md, D1).
 *
 * The `&` and `#` inputs are the marks rooms were stored with before, on purpose: they are
 * what an older Dedun or a Dedun peer still mints, and what old certificates carry.
 */
class MarmotGroupNameTest {
    /** Stored or received, then bare, then drawn: D1's table, row by row. */
    private val table = listOf(
        Triple("Ekklesia", "Ekklesia", "{Ekklesia}"),
        Triple("&Ekklesia", "Ekklesia", "{Ekklesia}"),
        Triple("#Ekklesia", "Ekklesia", "{Ekklesia}"),
        Triple("{Ekklesia}", "Ekklesia", "{Ekklesia}"),
        Triple("# & #Ekklesia", "Ekklesia", "{Ekklesia}"),
        Triple("{{Ekklesia}}", "Ekklesia", "{Ekklesia}"),
        Triple("{ Ekklesia }", "Ekklesia", "{Ekklesia}"),
        Triple("{Ekklesia", "Ekklesia", "{Ekklesia}"),
        Triple("  Ekklesia  ", "Ekklesia", "{Ekklesia}"),
        Triple("Book club", "Book club", "{Book club}"),
        Triple("Arts & crafts", "Arts & crafts", "{Arts & crafts}"),
        Triple("#Room #2", "Room #2", "{Room #2}"),
        Triple("Set {a}", "Set {a}", "{Set {a}}"),
        Triple("Ekklesia}", "Ekklesia}", "{Ekklesia}}"),
    )

    @Test
    fun `every spelling is stored bare`() {
        table.forEach { (input, bare, _) -> assertEquals(bare, MarmotGroupName.bare(input), "bare($input)") }
    }

    @Test
    fun `every spelling is drawn in braces`() {
        table.forEach { (input, _, drawn) -> assertEquals(drawn, MarmotGroupName.display(input), "display($input)") }
    }

    @Test
    fun `bare is idempotent`() {
        table.forEach { (input, _, _) ->
            val once = MarmotGroupName.bare(input)
            assertEquals(once, MarmotGroupName.bare(once), "bare(bare($input))")
        }
    }

    @Test
    fun `drawing a drawn name changes nothing`() {
        table.forEach { (input, _, _) ->
            val once = MarmotGroupName.display(input)
            assertEquals(once, MarmotGroupName.display(once), "display(display($input))")
        }
    }

    @Test
    fun `an old name and a new name are the same name`() {
        val names = listOf("Translators", "&Translators", "#Translators", "{Translators}")

        assertEquals(setOf("Translators"), names.map(MarmotGroupName::bare).toSet())
        assertEquals(setOf("{Translators}"), names.map(MarmotGroupName::display).toSet())
    }

    @Test
    fun `a blank name is left alone rather than turned into bare marks`() {
        listOf("   " to "", "&" to "&", "#" to "#", "{}" to "{}", "{" to "{").forEach { (input, expected) ->
            assertEquals(expected, MarmotGroupName.bare(input), "bare($input)")
            assertEquals(expected, MarmotGroupName.display(input), "display($input)")
        }
    }

    @Test
    fun `the stored mark is still the old one until the writers store bare`() {
        assertEquals("&Ekklesia", MarmotGroupName.of("Ekklesia"))
        assertEquals("&Ekklesia", MarmotGroupName.of("#Ekklesia"))
        assertEquals("&Ekklesia", MarmotGroupName.of("&Ekklesia"))
        assertEquals(MarmotGroupName.of("Translators"), MarmotGroupName.of(MarmotGroupName.of("Translators")))
        assertEquals("&", MarmotGroupName.of("&"))
    }
}
