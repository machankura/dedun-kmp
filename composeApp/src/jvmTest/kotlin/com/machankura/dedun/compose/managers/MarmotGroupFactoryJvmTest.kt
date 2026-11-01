package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.dao.marmotKeyPackageFor
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.nostr.Relays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one place a Marmot group is minted, on either profile. docs/marmot-v2.md, Phase 4.
 */
class MarmotGroupFactoryJvmTest {
    private val creator = KeyPair()
    private val creatorHex = creator.pubKey.toHexKey()
    private val admin = KeyPair().pubKey.toHexKey()
    // A real point, as a threshold key is; the derivation refuses anything else.
    private val roomId = SharedKeyDerivation.marmotGroupId("02" + KeyPair().pubKey.toHexKey())
    private val relays = Relays.DefaultDMRelayList.map { it.url }

    @Test
    fun `a legacy group is addressed by the room id and names its admins in 0xF2EE`() = runBlocking {
        val created = MarmotGroupFactory.create(
            creator = creator, roomId = roomId, name = "#room", description = "d",
            adminPublicKeys = listOf(admin), relays = relays, profile = MarmotProfile.LEGACY,
        )

        assertEquals(MarmotProfile.LEGACY, created.profile)
        assertEquals(roomId, created.routingId)
        assertEquals(roomId, created.group.roomId())
        assertEquals(roomId, created.group.routingId())
        assertFalse(created.group.currentGroupState().isCurrentProfile)
        assertEquals(setOf(creatorHex, admin), created.group.currentAdminIdentities())
        assertEquals("#room", created.group.roomName())
    }

    @Test
    fun `a current-profile group mints an address apart from the room id and names the room`() = runBlocking {
        val created = MarmotGroupFactory.create(
            creator = creator, roomId = roomId, name = "#room", description = "Admins of room.",
            adminPublicKeys = listOf(admin), relays = relays, profile = MarmotProfile.CURRENT,
        )

        assertEquals(MarmotProfile.CURRENT, created.profile)
        assertNotEquals(roomId, created.routingId)
        assertEquals(64, created.routingId.length)
        assertTrue(created.group.currentGroupState().isCurrentProfile)
        assertNull(created.group.currentMarmotData(), "a current-profile group carries no 0xF2EE")

        assertEquals(created.routingId, created.group.routingId())
        assertEquals(roomId, created.group.roomId(), "the room id does not ride in the group's description")
        assertEquals(setOf(creatorHex, admin), created.group.currentAdminIdentities())
        assertEquals("#room", created.group.roomName())
        assertTrue(created.group.roomDescription()!!.startsWith("Admins of room."))
        assertEquals(relays.sorted(), created.group.roomRelays().sorted())
    }

    @Test
    fun `two current-profile groups never share an address`() = runBlocking {
        val first = MarmotGroupFactory.create(creator, roomId, "#a", null, emptyList(), relays, MarmotProfile.CURRENT)
        val second = MarmotGroupFactory.create(creator, roomId, "#a", null, emptyList(), relays, MarmotProfile.CURRENT)

        assertNotEquals(first.routingId, second.routingId)
    }

    @Test
    fun `a current-profile group needs a creator that can sign`() = runBlocking<Unit> {
        val readOnly = KeyPair(privKey = null, pubKey = creator.pubKey)

        assertFailsWith<IllegalArgumentException> {
            MarmotGroupFactory.create(readOnly, roomId, "#a", null, emptyList(), relays, MarmotProfile.CURRENT)
        }
        // And a legacy one does not.
        MarmotGroupFactory.create(readOnly, roomId, "#a", null, emptyList(), relays, MarmotProfile.LEGACY)
    }

    @Test
    fun `a current-profile leaf fits either group and a legacy leaf fits only a legacy one`() = runBlocking {
        val legacyGroup = MarmotGroupFactory.create(creator, roomId, "#a", null, emptyList(), relays, MarmotProfile.LEGACY).group
        val currentGroup = MarmotGroupFactory.create(creator, roomId, "#a", null, emptyList(), relays, MarmotProfile.CURRENT).group
        val legacyLeaf = KeyPackageUtils.decodeKeyPackage(marmotKeyPackageFor(admin).tlsEncodedMarmotKeyPackage)
        val currentLeaf = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(KeyPair())).keyPackage

        assertTrue(MarmotGroupFactory.leafFits(legacyGroup, legacyLeaf))
        assertTrue(MarmotGroupFactory.leafFits(legacyGroup, currentLeaf))
        assertTrue(MarmotGroupFactory.leafFits(currentGroup, currentLeaf))
        assertFalse(MarmotGroupFactory.leafFits(currentGroup, legacyLeaf))
    }
}
