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
import java.io.File
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

    private val source: File =
        File("src").takeIf { it.resolve("commonMain").isDirectory } ?: File("composeApp/src")

    private val catalogue: List<Pair<String, String>> by lazy {
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(source.resolve("commonMain/composeResources/values/strings.xml").readText())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    }

    /**
     * The feature's own strings, plus any string a Wavelength screen borrows from elsewhere: a
     * word is jargon because of the screen it is on, not because of the prefix on its key.
     */
    private val wavelengthStrings: List<Pair<String, String>> by lazy {
        val borrowed = source.resolve("commonMain/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && ("Wavelength" in it.name || "GroupPayouts" in it.name) }
            .flatMap { file -> Regex("""Res\.string\.([a-z0-9_]+)""").findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()
        catalogue.filter { (key, _) ->
            key.startsWith("wavelength_") || key.startsWith("group_wavelength_") || key in borrowed
        }
    }

    /** Every "operator" in [value] that is not the tail of "Ark operator". */
    private fun saysBareOperator(value: String) =
        Regex("operator", RegexOption.IGNORE_CASE)
            .findAll(value)
            .any { !value.substring(0, it.range.first).endsWith("Ark ") }

    /**
     * One name per party, in the strings the Wavelength screens show.
     *
     * Two words were naming things a member has no way to know. *daemon* is what the code calls
     * the process that runs the wallet, and on a screen it is a word about this app's internals.
     * A bare *operator* was worse: eight strings said *Ark operator* for the server the coins
     * live with, four said *the operator* for the member the group elected to keep the wallet
     * running, and three said *operator* for the server without the *Ark* -- two parties a word
     * apart, one of them the second figure on the balance card.
     *
     * The same rule is in `docs/scripts/m3-jargon.py`, which is what `m3Audit` reports and what
     * fails `./gradlew check`. It is here as well because this is the half that fails in the
     * loop a developer actually runs, and a budget enforced only by a script somebody remembers
     * is the state this rule was written to leave.
     */
    @Test
    fun `no string a Wavelength screen shows says daemon`() {
        assertEquals(
            emptyList(),
            wavelengthStrings.filter { (_, value) -> value.contains("daemon", ignoreCase = true) }.map { it.first },
            "a device is a device on a screen; see docs/scripts/m3-jargon.py --list",
        )
    }

    @Test
    fun `a Wavelength string names the Ark operator or whoever runs the wallet, never a bare operator`() {
        assertEquals(
            emptyList(),
            wavelengthStrings.filter { (_, value) -> saysBareOperator(value) }.map { it.first },
            "say \"Ark operator\" for the server or \"whoever runs the wallet\" for the member; " +
                "see docs/scripts/m3-jargon.py --list",
        )
    }

    /**
     * Every state these screens can get stuck in says two things: what happened, and what to do.
     *
     * The guide's error copy has four parts -- what went wrong, where the money stands, how it is
     * fixed or avoided, what to do next -- and before Phase 5 these messages had the first and
     * nothing else. Two sentence endings is a crude proxy for the rest, and it is the one a script can
     * keep: a new state with a single sentence fails here.
     *
     * The distinctness half is the regression that mattered. Four of the wallet's ten refusals shared
     * one sentence behind an `else`, and two of the daemon's seven shared another -- *wait* and
     * *somebody has to volunteer*, which are opposite instructions. `group_wavelength_refused_chain`
     * is the one deliberate reuse: the wallet refuses to sign for a network this build does not
     * offer, and the device refuses to run it, which is one fact said once.
     */
    @Test
    fun `every stuck state says what happened and what to do, and no two say the same`() {
        val keys = listOf(
            // GroupWavelengthWalletManager.Refusal, all ten.
            "group_wavelength_refused_unreadable", "group_wavelength_refused_chain",
            "group_wavelength_refused_no_key", "group_wavelength_refused_size",
            "group_wavelength_refused_no_onchain", "group_wavelength_refused_holders",
            "group_wavelength_refused_ceremony", "group_wavelength_refused_ceremony_disagrees",
            "group_wavelength_refused_waiting_seed", "group_wavelength_refused_keys",
            // GroupWavelengthDaemonStatus, the nine that are not a refusal.
            "group_wavelength_daemon_not_on_device", "group_wavelength_daemon_catching_up",
            "group_wavelength_daemon_waiting_for_signers", "group_wavelength_daemon_opening",
            "group_wavelength_daemon_recovering", "group_wavelength_daemon_running",
            "group_wavelength_daemon_paused", "group_wavelength_daemon_busy_s",
            "group_wavelength_daemon_failed",
            // GroupWavelengthDaemon.Refusal, the six with a string of their own.
            "group_wavelength_daemon_no_seed", "group_wavelength_daemon_keys_disagree",
            "group_wavelength_daemon_signers_not_ready", "group_wavelength_daemon_opened_as_another",
            "group_wavelength_daemon_behind", "group_wavelength_daemon_nobody_runs_it",
        )
        val values = catalogue.filter { it.first in keys }
        assertEquals(keys.size, values.size, "a key in this list is missing from the catalogue")

        val oneSentence = values.filter { (_, value) -> value.count { it in ".!?\u2026" } < 2 }
        assertEquals(emptyList(), oneSentence.map { it.first }, "says what happened and nothing about what to do")

        val byValue = values.groupBy { it.second }.filterValues { it.size > 1 }
        assertEquals(emptyMap(), byValue.mapValues { (_, same) -> same.map { it.first } }, "two states saying the same thing")
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
