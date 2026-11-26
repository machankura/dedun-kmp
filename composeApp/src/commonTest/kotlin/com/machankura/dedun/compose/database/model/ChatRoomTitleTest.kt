package com.machankura.dedun.compose.database.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A room's name as drawn: a group's in braces, a conversation's as it is (docs/group-name-braces.md, D3). */
class ChatRoomTitleTest {
    private fun room(subject: String?, marmot: Boolean) =
        ChatRoom(id = "a".repeat(64), userPublicKey = "b".repeat(64), subject = subject, description = null, mlsGroupState = if (marmot) "00" else null)

    @Test
    fun `a group is drawn in braces however it was stored`() {
        listOf("Ekklesia", "&Ekklesia", "#Ekklesia").forEach {
            assertEquals("{Ekklesia}", room(it, marmot = true).title, "title of $it")
        }
    }

    @Test
    fun `a conversation is drawn as it is called`() {
        assertEquals("&topic", room("&topic", marmot = false).title)
        assertEquals("Book club", room("Book club", marmot = false).title)
    }

    @Test
    fun `a room with no name has no title`() {
        assertNull(room(null, marmot = true).title)
        assertNull(room(null, marmot = false).title)
    }
}
