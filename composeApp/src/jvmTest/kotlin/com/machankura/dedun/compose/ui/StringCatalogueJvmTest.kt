package com.machankura.dedun.compose.ui

import kotlinx.coroutines.runBlocking
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.currently_no_messages_have_been_shared
import dedun.composeapp.generated.resources.don_t_sign
import dedun.composeapp.generated.resources.something_went_wrong
import dedun.composeapp.generated.resources.recovered_of_still_unreadable
import dedun.composeapp.generated.resources.sent_a_private_message_to
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getPluralString
import dedun.composeapp.generated.resources.about_n_hours
import dedun.composeapp.generated.resources.n_blocks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The string catalogue reads back what the source used to say.
 *
 * Moving 315 literals out of composables and into `strings.xml` is a rename that a
 * compiler cannot check: every call site still compiles if a resource holds the wrong
 * text, and the mistake surfaces as wrong words on a screen nobody has opened.
 *
 * The escapes are the part worth a test rather than an assumption. A string going into an
 * XML resource needs `'` escaped as `\'` and `%` doubled, and the generated `.cvr` stores
 * the escaped form verbatim -- base64 of `We couldn\'t find...`. Whether the backslash is
 * still there when `stringResource` hands the text to a `Text` is a property of the
 * resources library, not of the extraction, and the failure mode is an app that literally
 * displays `Don\'t sign`.
 *
 * `getString` is the non-composable reader for the same resources, so this can assert it
 * without a composition.
 */
class StringCatalogueJvmTest {

    @Test
    fun `an apostrophe survives the round trip through the catalogue`() = runBlocking {
        assertEquals("Don't sign", getString(Res.string.don_t_sign))
    }

    @Test
    fun `an escaped newline comes back as a newline`() = runBlocking {
        assertEquals(
            "Currently no messages have been shared.\nBreak the ice.",
            getString(Res.string.currently_no_messages_have_been_shared),
        )
    }

    @Test
    fun `a plain string comes back unchanged`() = runBlocking {
        assertEquals("Something went wrong", getString(Res.string.something_went_wrong))
    }

    @Test
    fun `a format string substitutes its arguments in order`() = runBlocking {
        // The interpolated strings became `%1$s` format strings, and the argument order
        // is decided by where each `${…}` sat in the template. A transposition compiles
        // and reads plausibly -- "Recovered 3 of 12" against "Recovered 12 of 3" -- so
        // the ordering is worth asserting on a multi-argument one.
        assertEquals(
            "Recovered 3 of 12 · 9 still unreadable.",
            getString(Res.string.recovered_of_still_unreadable, "3", "12", "9", "."),
        )
        assertEquals(
            "Ada sent a private message to Grace",
            getString(Res.string.sent_a_private_message_to, "Ada", "Grace"),
        )
    }

    @Test
    fun `a unicode escape was decoded into the catalogue`() = runBlocking {
        // `·` is Kotlin source syntax with no meaning in XML, so the extractor
        // decodes it and the resource holds the character. If it had been left alone the
        // app would render the six characters of the escape.
        assertEquals(
            "Recovered 3 of 12 · 9 still unreadable",
            getString(Res.string.recovered_of_still_unreadable, "3", "12", "9", ""),
        )
    }

    @Test
    fun `a plural picks its form by the count, and substitutes it`() = runBlocking {
        // The catalogue's first plurals, for a guardian's delay. A plain
        // `%1$d hours` reads "about 1 hours", and no audit catches that.
        assertEquals("about 1 hour", getPluralString(Res.plurals.about_n_hours, 1, 1))
        assertEquals("about 36 hours", getPluralString(Res.plurals.about_n_hours, 36, 36))
        assertEquals("1 block", getPluralString(Res.plurals.n_blocks, 1, "1"))
        assertEquals("65,535 blocks", getPluralString(Res.plurals.n_blocks, 65_535, "65,535"))
    }
}
