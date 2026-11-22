package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository

/**
 * A room joined from a Welcome is called by the same rule as a room this device made.
 *
 * The name a Welcome carries is whatever its creator wrote: `#name` from an older Dedun or a
 * Dedun peer, a bare name from a client that marks nothing, `&name` from a Dedun on the
 * current rule. Each of them is a Marmot room, and each lands as `&name`, so the mark on a
 * row says the same thing whoever made the room. docs/group-name-prefix.md, Phase 2.
 *
 * The group is made and the invite sent by the library's own client, so the name reaches the
 * app through the same Welcome path a real peer's would.
 */
class MarmotGroupNameWelcomeJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )
    private val marmotRepository = DatabaseMarmotRepository(db, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair()
    private val userHex = user.pubKey.toHexKey()

    private val peer = MarmotClientStandIn()

    /** The subject the app gives a room the peer made as [name] and invited it into. */
    private suspend fun joinedAs(name: String): String? {
        MarmotStandIn.seedProfile(db, userHex, "us")
        MarmotStandIn.seedProfile(db, peer.hex, "peer")
        val roomId = peer.inviteApp(db, marmotRepository, user, peer.createGroup(name))
        return assertNotNull(db.chatRoomDao().findChatRoomById(roomId)).chatRoom.subject
    }

    @Test
    fun `a room named under the old mark is joined under the new one`() = runBlocking {
        // `#` on purpose: what an older Dedun or a Dedun peer still mints.
        assertEquals("&Ekklesia", joinedAs("#Ekklesia"))
    }

    @Test
    fun `a room another client named without a mark is joined with one`() = runBlocking {
        assertEquals("&Ekklesia", joinedAs("Ekklesia"))
    }

    @Test
    fun `a room already under the mark is joined as it is`() = runBlocking {
        assertEquals("&Ekklesia", joinedAs("&Ekklesia"))
    }

    @Test
    fun `a room with no name is not given one`() = runBlocking {
        assertEquals(null, joinedAs(""))
    }
}
