package com.machankura.dedun.compose.managers

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A key ceremony is started in exactly three places, each of which knows what
 * the ceremony is for.
 *
 * - group creation (`SelectChatRoomTypeViewModel`), a new group's own key;
 * - the subgroup picker (`SelectSubgroupAdminsViewModel`), a subgroup's key,
 *   held in its parent's room;
 * - `CreateSharedKeyViewModel`, an existing NIP-17 group that has no key yet.
 *
 * A fourth used to exist: the ceremony screen offered "Start key ceremony" from
 * the same button slot its approvals used, and a member coming back from
 * approving a subgroup's ceremony opened a second, whole-room one in the
 * parent's room. See docs/ceremony-creation.md.
 *
 * This counts calls rather than proving anything about them, which is the
 * point: it fails the day a fourth appears, so that adding one is a decision
 * somebody makes here rather than a button somebody adds to a screen.
 */
class CeremonyEntryPointsJvmTest {

    private val source: File =
        File("src").takeIf { it.resolve("commonMain").isDirectory } ?: File("composeApp/src")

    @Test
    fun `a ceremony is opened from three screens and no others`() {
        val callers = source.resolve("commonMain/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "dkgRepository.proposeRitual(" in it.readText() }
            .map { it.nameWithoutExtension }
            .toSet()

        assertEquals(
            setOf(
                "SelectChatRoomTypeViewModel",
                "SelectSubgroupAdminsViewModel",
                "CreateSharedKeyViewModel",
            ),
            callers,
        )
    }
}
