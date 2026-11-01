package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.marmot.mls.messages.KeyPackageBundle
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.nostr.Relays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A Welcome finds the bundle it was encrypted to by the KeyPackageRef inside it,
 * not by anything the inviter wrote around it.
 */
class WelcomeBundleMatchJvmTest {
    private val creator = KeyPair()
    private val joiner = KeyPair()

    private fun legacyGroup(): MlsGroup = MlsGroup.create(
        identity = creator.pubKey.toHexKey().hexToByteArray(),
        initialExtensions = listOf(
            MarmotGroupData.bootstrap(
                nostrGroupId = "a".repeat(64),
                creatorPubKey = creator.pubKey.toHexKey(),
                outboxRelays = Relays.DefaultDMRelayList.map { it.url },
            ).toExtension()
        ),
    )

    private suspend fun bundle(): KeyPackageBundle =
        CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(joiner))

    @Test
    fun `the bundle the welcome names is chosen whatever order the candidates come in`() = runBlocking {
        val first = bundle()
        val second = bundle()
        val welcome = legacyGroup().addMember(second.keyPackage.toTlsBytes()).welcomeBytes!!

        assertSame(second, WelcomeBundleMatch.select(welcome, listOf(first, second)) { it.keyPackage.toTlsBytes() })
        assertSame(second, WelcomeBundleMatch.select(welcome, listOf(second, first)) { it.keyPackage.toTlsBytes() })
    }

    @Test
    fun `a welcome addressed to no bundle of ours chooses none`() = runBlocking {
        val ours = bundle()
        val someoneElses = bundle()
        val welcome = legacyGroup().addMember(someoneElses.keyPackage.toTlsBytes()).welcomeBytes!!

        assertNull(WelcomeBundleMatch.select(welcome, listOf(ours)) { it.keyPackage.toTlsBytes() })
    }

    /**
     * One unreadable row must not hide the one that matches: the caller has rows
     * written by every build that ever ran, and the oldest of them are the least
     * likely to decode.
     */
    @Test
    fun `a candidate that does not decode is skipped rather than fatal`() = runBlocking {
        val target = bundle()
        val welcome = legacyGroup().addMember(target.keyPackage.toTlsBytes()).welcomeBytes!!
        val candidates = listOf("garbage" to ByteArray(3), "target" to target.keyPackage.toTlsBytes())

        assertEquals("target", WelcomeBundleMatch.select(welcome, candidates) { it.second }?.first)
    }

    @Test
    fun `bytes that are not a welcome address nothing`() {
        assertTrue(WelcomeBundleMatch.addressedRefs(ByteArray(0)).isEmpty())
        assertTrue(WelcomeBundleMatch.addressedRefs(ByteArray(16) { 1 }).isEmpty())
    }
}
