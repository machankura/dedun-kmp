package com.machankura.dedun.compose.ui.composable

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * No screen shows the group's shared receive address: every address a member is handed is on
 * their own deposit branch, so whatever is paid to it is theirs (`docs/member-deposits.md`,
 * Phase 5). `GroupWalletState.receiveAddress` stays for the funding window and the tests, and
 * `receiveAddresses` for listing what was paid before deposits were attributed -- from a view
 * model, filtered to used ones -- but a composable reaching for either is a new way to show an
 * address that is nobody's.
 *
 * Except a member's own guarded wallet: one owner, so every deposit into it is theirs already,
 * and the plan leaves it as it is ("What this does not do").
 */
class OnlyAttributedAddressesJvmTest {

    @Test
    fun `no composable reads the shared receive address`() {
        val root = (File("src").takeIf { it.resolve("commonMain").isDirectory } ?: File("composeApp/src"))
            .resolve("commonMain/kotlin/com/machankura/dedun/compose/ui/composable")
        val offenders = root.walkTopDown().filter { it.extension == "kt" && it.name !in EXEMPT }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (Regex("\\breceiveAddress(es)?\\b").containsMatchIn(line)) "${file.name}:${index + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertEquals(emptyList(), offenders, "show a member's own deposit address instead: GroupWalletState.depositAddressOf")
    }

    private companion object {
        val EXEMPT = setOf("MemberWalletScreen.kt")
    }
}
