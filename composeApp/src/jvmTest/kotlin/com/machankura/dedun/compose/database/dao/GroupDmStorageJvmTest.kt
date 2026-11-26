package com.machankura.dedun.compose.database.dao

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.GroupConversationKey
import com.machankura.dedun.compose.database.model.GroupDmMessage
import com.machankura.dedun.compose.database.model.GroupDmWrap
import com.machankura.dedun.compose.database.model.GroupEcdhPartial
import com.machankura.dedun.compose.database.model.GroupEcdhRound
import com.machankura.dedun.compose.managers.SigningProposalHarness
import com.machankura.dedun.compose.nostr.groupdm.EcdhPurpose

/**
 * Phase 4 of docs/group-threshold-ecdh.md: redelivery of a partial, a wrap or a rumor changes
 * nothing, and wiping a room takes its kept keys and its mail with it.
 */
class GroupDmStorageJvmTest {
    private val harness = SigningProposalHarness()

    @AfterTest
    fun close() = harness.close()

    private val roundId = "r".repeat(64).replace('r', '1')

    @Test
    fun `redelivery is a no-op`() = runBlocking {
        val db = harness.openDevice(harness.members[0])
        val dao = db.groupDmDao()
        val round = GroupEcdhRound(id = roundId, chatRoomId = harness.roomId, generation = 0, point = "a".repeat(64), purpose = EcdhPurpose.WRAP, ref = "b".repeat(64))
        assertEquals(true, dao.insertRound(round) > 0)
        assertEquals(-1L, dao.insertRound(round.copy(point = "c".repeat(64))), "the same round, not a second")
        assertEquals("a".repeat(64), dao.getRound(roundId)?.point)

        val partial = GroupEcdhPartial(roundId, 1, harness.members[1], "02" + "d".repeat(64), "e".repeat(128), verified = true)
        assertEquals(true, dao.insertPartial(partial) > 0)
        assertEquals(-1L, dao.insertPartial(partial.copy(partial = "03" + "f".repeat(64))))
        assertEquals(listOf("02" + "d".repeat(64)), dao.getPartials(roundId).map { it.partial })

        val wrap = GroupDmWrap(id = "b".repeat(64), chatRoomId = harness.roomId, wrapJson = "{}", createdAt = Instant.fromEpochSeconds(1))
        assertEquals(true, dao.insertWrap(wrap) > 0)
        assertEquals(-1L, dao.insertWrap(wrap))

        val message = GroupDmMessage("9".repeat(64), harness.roomId, "c".repeat(64), "a".repeat(64), "{}", Instant.fromEpochSeconds(2), outgoing = false, wrapId = wrap.id)
        assertEquals(true, dao.insertMessage(message) > 0)
        assertEquals(-1L, dao.insertMessage(message.copy(wrapId = "8".repeat(64))), "the same rumor in another wrap")
        assertEquals(wrap.id, dao.getMessage(message.rumorId)?.wrapId)
    }

    @Test
    fun `wiping a room removes its keys, its mail and its rounds`() = runBlocking {
        val db = harness.openDevice(harness.members[0])
        val dao = db.groupDmDao()
        dao.insertRound(GroupEcdhRound(id = roundId, chatRoomId = harness.roomId, generation = 0, point = "a".repeat(64), purpose = EcdhPurpose.SEAL, ref = "b".repeat(64)))
        dao.insertPartial(GroupEcdhPartial(roundId, 1, harness.members[1], "02" + "d".repeat(64), "e".repeat(128), verified = true))
        dao.insertConversationKey(GroupConversationKey(harness.roomId, "a".repeat(64), "5".repeat(64), roundId))
        dao.insertWrap(GroupDmWrap(id = "b".repeat(64), chatRoomId = harness.roomId, wrapJson = "{}", createdAt = Instant.fromEpochSeconds(1)))
        dao.insertMessage(GroupDmMessage("9".repeat(64), harness.roomId, "c".repeat(64), "a".repeat(64), "{}", Instant.fromEpochSeconds(2), outgoing = false))
        assertNotNull(dao.getConversationKey(harness.roomId, "a".repeat(64)))
        assertEquals(false, dao.getConversationKey(harness.roomId, "a".repeat(64)).toString().contains("5555"), "a kept key never prints")

        dao.wipeRoom(harness.roomId)

        assertNull(dao.getConversationKey(harness.roomId, "a".repeat(64)))
        assertEquals(emptyList(), dao.getMessages(harness.roomId))
        assertNull(dao.getWrap("b".repeat(64)))
        assertNull(dao.getRound(roundId))
        assertEquals(emptyList(), dao.getPartials(roundId))
    }
}
