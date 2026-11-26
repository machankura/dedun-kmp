package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseGroupDmRepository
import com.machankura.dedun.compose.ui.view.state.GroupDmPending
import com.machankura.dedun.compose.ui.view.state.GroupDmRow

/**
 * Phase 8 of docs/group-threshold-ecdh.md, read through the repositories the screens read: who
 * sees which row on the group's screen, what the list says before anybody has written, and
 * what a message still being opened waits on, by name.
 */
class GroupDmScreenJvmTest {
    private val net = GroupDmNetwork(n = 4, t = 2)
    private val a get() = net.devices[0]
    private val b get() = net.devices[1]
    private val d get() = net.devices[3]

    @BeforeTest
    fun setUp() = runBlocking {
        ExperimentalFeatures.groupDirectMessagesOverride = true
        net.admins = net.members.take(3).toSet()
        net.install()
        net.all()
        Unit
    }

    @AfterTest
    fun tearDown() {
        net.close()
        ExperimentalFeatures.groupDirectMessagesOverride = null
    }

    private fun chat(device: GroupDmNetwork.Device) = DatabaseChatRepository(device.db, CoroutineScope(Dispatchers.IO))

    @Test
    fun `the rows by role, before and after the group opens its messages`(): Unit = runBlocking {
        assertEquals(GroupDmRow.OpenMessages, chat(a).groupDmRow(net.roomId, a.publicKey), "an admin is offered to open them")
        assertNull(chat(d).groupDmRow(net.roomId, d.publicKey), "a member who is not an admin sees nothing yet")

        net.openMessages()
        assertEquals(GroupDmRow.Messages(unread = 0), chat(a).groupDmRow(net.roomId, a.publicKey))
        assertEquals(GroupDmRow.ReceivesMessages, chat(d).groupDmRow(net.roomId, d.publicKey), "the 10050 is public, and only that")

        ExperimentalFeatures.groupDirectMessagesOverride = false
        assertNull(chat(a).groupDmRow(net.roomId, a.publicKey), "off, the feature shows nothing")
    }

    @Test
    fun `nobody has written yet, and then one message waits on one more admin, by name`(): Unit = runBlocking {
        net.openMessages()
        val repository = DatabaseGroupDmRepository(a.db)
        assertEquals(emptyList(), repository.observeConversations(net.roomId).first())
        assertEquals(GroupDmPending(), repository.observePending(net.roomId).first())

        // Only A's app is open: A answers, and the message waits on one more.
        val wrap = net.wrapFrom(KeyPair(), "dumela")
        net.receive(wrap, a)
        val pending = repository.observePending(net.roomId).first()
        assertEquals(1, pending.waiting)
        assertEquals(1, pending.moreAdminsNeeded)
        assertEquals(listOf(b.publicKey, net.devices[2].publicKey).sorted().map { it.take(8) }, pending.notYetAnsweredBy.sorted())

        // B opens the app: the message opens, and is unread.
        net.receive(wrap, b)
        net.pump(between = listOf(a, b))
        assertEquals(GroupDmPending(), repository.observePending(net.roomId).first())
        val conversation = repository.observeConversations(net.roomId).first().single()
        assertEquals("dumela", conversation.lastLine)
        assertEquals(1, conversation.unread)
        assertEquals(GroupDmRow.Messages(unread = 1), chat(a).groupDmRow(net.roomId, a.publicKey))

        repository.markRead(net.roomId, conversation.conversationId)
        assertEquals(0, repository.observeConversations(net.roomId).first().single().unread)
        val lines = repository.observeConversation(net.roomId, conversation.conversationId).first()
        assertEquals(listOf("dumela"), lines.map { it.text })
    }

    @Test
    fun `a member who is not an admin reads no conversation`(): Unit = runBlocking {
        net.openMessages()
        assertEquals(false, DatabaseGroupDmRepository(d.db).isCurrentAdmin(net.roomId, d.publicKey))
        assertEquals(true, DatabaseGroupDmRepository(a.db).isCurrentAdmin(net.roomId, a.publicKey))
    }
}
