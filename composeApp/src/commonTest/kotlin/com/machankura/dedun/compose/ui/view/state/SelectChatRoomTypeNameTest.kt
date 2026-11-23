package com.machankura.dedun.compose.ui.view.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A group's name is taken whatever mark it was stored under (docs/group-name-braces.md, D8).
 *
 * The `&` and `#` names are on purpose: rooms made before the braces were drawn were stored
 * under them, and a device that has not run v55 yet still holds them.
 */
class SelectChatRoomTypeNameTest {
    private fun loaded(vararg names: String?) =
        SelectChatRoomTypeUIState.Loaded(members = emptyList(), existingGroups = names.map { SelectChatRoomTypeUIState.ExistingGroup(it) })

    @Test
    fun `a name stored under an old mark is the same name typed bare`() {
        assertTrue(loaded("&Ekklesia").sharesNameWith("Ekklesia"))
        assertTrue(loaded("#Ekklesia").sharesNameWith("ekklesia"))
    }

    @Test
    fun `a name typed in braces is the same name stored bare`() {
        assertTrue(loaded("Ekklesia").sharesNameWith("{Ekklesia}"))
    }

    @Test
    fun `a different name is not taken`() {
        assertFalse(loaded("Ekklesia", null).sharesNameWith("Agora"))
    }
}
