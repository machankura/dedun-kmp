package com.machankura.dedun.compose.identity.recoverycode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.machankura.dedun.compose.identity.recoverycode.RecoveryCodeFormat.Checked
import fr.acinq.bitcoin.crypto.hmac
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every row of the spec's normative vectors (resources/recoverycode/v3_vectors.json, whose
 * README names the commit it was copied from), against the port.
 *
 * The two version 1 key rows are the only ones skipped: a version 1 key is scrypt over a
 * salt from Muun's Emergency Kit, and Dedun never derives one. Their validity and version are
 * still checked.
 */
class RecoveryCodeVectorsJvmTest {

    private val vectors: JsonObject by lazy {
        val text = javaClass.getResource("/recoverycode/v3_vectors.json")?.readText()
            ?: fail("v3_vectors.json is not on the test classpath")
        Json.parseToJsonElement(text).jsonObject
    }

    private fun rows(section: String): List<JsonObject> =
        vectors.getValue(section).jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun problemName(problem: RecoveryCodeProblem): String = problem.name.lowercase()

    @Test
    fun `every validate row`() {
        val rows = rows("validate")
        assertEquals(47, rows.size, "the vector file changed size; check its README")
        rows.forEach { row ->
            val description = row.string("description")
            val input = row.string("input")
            when (val result = RecoveryCodeFormat.version(input)) {
                is Checked.Ok -> {
                    assertTrue(row.getValue("valid").jsonPrimitive.boolean, "$description: accepted")
                    assertEquals(row.getValue("version").jsonPrimitive.int, result.value, description)
                }
                is Checked.Failed -> {
                    assertTrue(!row.getValue("valid").jsonPrimitive.boolean, "$description: refused as ${result.problem}")
                    assertEquals(row.string("error"), problemName(result.problem), description)
                }
            }
        }
    }

    @Test
    fun `every version 2 and 3 private key`() {
        val keyed = rows("validate").filter { it.string("private_key").isNotEmpty() && it.getValue("version").jsonPrimitive.int >= 2 }
        assertEquals(14, keyed.size)
        keyed.forEach { row ->
            val input = row.string("input")
            // The same HMAC as RecoveryCode.rootKey, reached for a version 2 code too, which
            // RecoveryCode itself will not hold.
            val key = fr.acinq.bitcoin.crypto.Digest.sha256().hmac(
                "muun:rc".encodeToByteArray(),
                input.encodeToByteArray(),
                64,
            )
            assertEquals(row.string("private_key"), key.toHexString(), row.string("description"))
            if (row.getValue("version").jsonPrimitive.int == 3) {
                val parsed = RecoveryCode.parse(input) as? RecoveryCode.Parse.Valid
                    ?: fail("${row.string("description")}: did not parse")
                assertEquals(row.string("private_key"), parsed.code.rootKey().toHex())
            }
        }
    }

    @Test
    fun `every normalize row`() {
        val rows = rows("normalize")
        assertEquals(13, rows.size)
        rows.forEach { row ->
            val description = row.string("description")
            when (val result = RecoveryCodeFormat.normalize(row.string("input"))) {
                is Checked.Ok -> assertEquals(row.string("output"), result.value, description)
                is Checked.Failed -> assertEquals(row.string("error"), problemName(result.problem), description)
            }
        }
    }

    @Test
    fun `every locate row`() {
        val rows = rows("locate")
        assertEquals(17, rows.size)
        rows.forEach { row ->
            val description = row.string("description")
            val typo = RecoveryCodeFormat.locateTypo(row.string("input"))
            if (row.getValue("found").jsonPrimitive.boolean) {
                typo ?: fail("$description: no typo found")
                assertEquals(row.getValue("position").jsonPrimitive.int, typo.position, description)
                assertEquals(row.string("corrected"), typo.corrected, description)
            } else {
                assertNull(typo, description)
            }
        }
    }

    /**
     * The same rows through the public door: a found typo is a [RecoveryCode.Parse.Typo]
     * carrying the corrected code, and never a [RecoveryCode.Parse.Valid].
     */
    @Test
    fun `parse reports each located typo as a suggestion`() {
        rows("locate").filter { it.getValue("found").jsonPrimitive.boolean }.forEach { row ->
            val parsed = RecoveryCode.parse(row.string("input"))
            val typo = parsed as? RecoveryCode.Parse.Typo ?: fail("${row.string("description")}: parsed as $parsed")
            assertEquals(row.string("corrected"), typo.suggested.canonical)
            assertEquals(row.getValue("position").jsonPrimitive.int, typo.position)
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
