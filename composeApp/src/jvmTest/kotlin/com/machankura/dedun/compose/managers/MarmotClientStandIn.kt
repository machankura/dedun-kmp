package com.machankura.dedun.compose.managers

import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.marmot.appComponents.GroupProfileV1
import com.vitorpamplona.marmot.client.InMemoryMlsGroupStateStore
import com.vitorpamplona.marmot.client.MarmotManager
import com.vitorpamplona.marmot.client.MarmotPublisher
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlin.io.encoding.Base64
import kotlin.test.assertNotNull
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.nostr.Relays

/**
 * Another Marmot client, played by `MarmotManager` -- the library's own headless client,
 * which is what Amethyst, its desktop app and the `amy` CLI drive.
 *
 * [MarmotStandIn] is the other one: `MlsGroupManager` and the two processors, wired the
 * way `MarmotEngine` wires them. Both are worth having, and they answer different
 * questions. The bare stand-in asks whether two engines agree, and it is the one the
 * convergence and lifecycle tests need, because they reach for a processor directly --
 * `recordLocalCommit` with a state snapshot, `buildAppEvent` with a payload of their own.
 * This one asks the question the bare one cannot: whether the app's own orchestration --
 * `MarmotEngine`, the DAOs, the outbound queue -- reaches the same group as the
 * orchestration the library ships, when the library's client is the one choosing how to
 * invite, seal, frame and send. Every step here is the library's decision, not this
 * test's: `addMemberInvites` picks the commit's shape and `MarmotWelcomeSender` wraps the
 * Welcome, where [MarmotStandIn.inviteApp] assembles the seal and the gift wrap by hand.
 *
 * Deliberately shallow: one store, in memory, and a publisher that says yes. No message
 * store and no KeyPackage store -- both are nullable, and a client that persists nothing
 * is the whole of what these tests need from it. See docs/marmot-kmp.md, Phase 3.
 */
internal class MarmotClientStandIn(val keyPair: KeyPair = KeyPair()) {
    val hex = keyPair.pubKey.toHexKey()
    val signer = NostrSignerInternal(keyPair)

    val client =
        MarmotManager(
            signer = signer,
            store = InMemoryMlsGroupStateStore(),
            // The gate refuses every obligation under the default publisher, so a client
            // with no relay behind it could read a group and never advance one.
            publisher = MarmotPublisher { _, _ -> true },
        )

    /** A current-profile group of this client's own. Returns its routing id. */
    suspend fun createGroup(name: String): HexKey {
        val routingId = RandomInstance.bytes(32).toHexKey()
        client.createCurrentProfileGroup(
            nostrGroupId = routingId,
            relays = Relays.DefaultDMRelayList.map { it.url },
            profile = GroupProfileV1(name = name, description = ""),
        )
        return routingId
    }

    /**
     * Invites the app -- [user], over [db] -- into [routingId] and delivers the Welcome
     * the library wrapped, through the sync funnel. The app's current-profile KeyPackage
     * is published and signed first, because the invite is made against the package as it
     * appears on a relay. Returns the app's room id.
     */
    suspend fun inviteApp(
        db: DedunDatabase,
        marmotRepository: DatabaseMarmotRepository,
        user: KeyPair,
        routingId: HexKey,
    ): HexKey {
        val userHex = user.pubKey.toHexKey()
        marmotRepository.publishMarmotKeyPackageBundle(user, setOf(MarmotProfile.CURRENT))
        val unsigned = db.unsignedNostrEventDao().getAllUnprocessedUnsignedNostrEvents()
            .single { it.kind == KeyPackageEvent.KIND && it.pubKey == userHex }
        val ourEvent = NostrSignerSync(user).signNormal<KeyPackageEvent>(
            createdAt = unsigned.createdAt.epochSeconds,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
        )

        val (_, welcomes) = client.addMemberInvites(
            nostrGroupId = routingId,
            invites = listOf(
                MarmotManager.MemberInvite(
                    memberPubKey = userHex,
                    keyPackageBytes = Base64.decode(ourEvent.content),
                    keyPackageEventId = ourEvent.id,
                ),
            ),
            relays = Relays.DefaultDMRelayList,
        )

        MarmotStandIn.storeInApp(db, user, welcomes.single().giftWrapEvent)
        assertNotNull(db.chatRoomDao().findChatRoomById(routingId), "the app did not join")
        return routingId
    }
}
