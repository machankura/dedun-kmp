package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.marmot.MarmotInboundProcessor
import com.vitorpamplona.quartz.marmot.MarmotOutboundProcessor
import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.appComponents.GroupProfileV1
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRotationManager
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroupManager
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroupStateStore
import com.vitorpamplona.quartz.marmot.mls.messages.CommitResult
import com.vitorpamplona.quartz.marmot.mls.messages.KeyPackageBundle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.utils.RandomInstance
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.nostr.Relays
import kotlin.io.encoding.Base64
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * Another Marmot client, played by quartz's own managers over a store that forgets
 * nothing while a test runs: what Amethyst is, minus Amethyst. The other side of
 * every two-implementation test in this package.
 */
internal class MarmotStandIn(val keyPair: KeyPair = KeyPair()) {
    val hex = keyPair.pubKey.toHexKey()
    val signer = NostrSignerInternal(keyPair)

    private val store = object : MlsGroupStateStore {
        private val states = mutableMapOf<String, ByteArray>()
        private val retained = mutableMapOf<String, List<ByteArray>>()
        override suspend fun save(nostrGroupId: String, state: ByteArray) { states[nostrGroupId] = state }
        override suspend fun load(nostrGroupId: String): ByteArray? = states[nostrGroupId]
        override suspend fun delete(nostrGroupId: String) { states.remove(nostrGroupId); retained.remove(nostrGroupId) }
        override suspend fun listGroups(): List<String> = states.keys.toList()
        override suspend fun saveRetainedEpochs(nostrGroupId: String, retainedSecrets: List<ByteArray>) { retained[nostrGroupId] = retainedSecrets }
        override suspend fun loadRetainedEpochs(nostrGroupId: String): List<ByteArray> = retained[nostrGroupId].orEmpty()
    }

    val manager = MlsGroupManager(store)
    val inbound = MarmotInboundProcessor(manager, KeyPackageRotationManager())
    val outbound = MarmotOutboundProcessor(manager)

    /** This client's own KeyPackage bundle, once one has been made. */
    lateinit var bundle: KeyPackageBundle

    /**
     * A commit this client makes and applies, told to its convergence engine the way
     * Amethyst tells its own: with the state it was authored against, so a competitor
     * later authored against the same parent is a fork and not an orphan.
     */
    suspend fun commitLocally(routingId: String, commit: suspend () -> CommitResult): CommitResult {
        val pre = assertNotNull(manager.snapshot(routingId))
        val result = commit()
        inbound.recordLocalCommit(routingId, result.framedCommitBytes, pre.groupContext.epoch, pre)
        lastCommit = result
        return result
    }

    /** The newest commit this client made, for the others to be handed. */
    var lastCommit: CommitResult? = null

    /** [lastCommit] as the kind:445 the others receive. */
    suspend fun lastCommitEvent(routingId: String): GroupEvent {
        val commit = assertNotNull(lastCommit)
        return outbound.buildCommitEvent(routingId, commit.framedCommitBytes, commit.preCommitExporterSecret).signedEvent
    }

    suspend fun makeBundle(): KeyPackageBundle =
        CurrentProfileGroupFactory.createKeyPackage(signer).also { bundle = it }

    /** A current-profile group of this client's own, tracked. Returns its routing id. */
    suspend fun createGroup(name: String): String {
        val routingId = RandomInstance.bytes(32)
        val group = CurrentProfileGroupFactory.createGroup(
            signer = signer,
            nostrGroupId = routingId,
            relays = Relays.DefaultDMRelayList.map { it.url },
            profile = GroupProfileV1(name = name, description = ""),
        )
        val hex = routingId.toHexKey()
        manager.adoptGroup(hex, group)
        inbound.trackGroup(hex)
        return hex
    }

    /**
     * Invites the app -- [user], over [db] -- into [routingId], delivering the Welcome
     * the way it arrives: a kind:444 rumor, sealed by this client, wrapped by a
     * throwaway key and p-tagged to the app, through the sync funnel. The app's
     * current-profile package is published first. Returns the app's room id.
     */
    suspend fun inviteApp(db: DedunDatabase, marmotRepository: DatabaseMarmotRepository, user: KeyPair, routingId: String): String {
        val userHex = user.pubKey.toHexKey()
        marmotRepository.publishMarmotKeyPackageBundle(user, setOf(MarmotProfile.CURRENT))
        val unsigned = db.unsignedNostrEventDao().getAllUnprocessedUnsignedNostrEvents()
            .single { it.kind == KeyPackageEvent.KIND && it.pubKey == userHex }
        val ourEvent = NostrSignerSync(user).signNormal<KeyPackageEvent>(
            createdAt = unsigned.createdAt.epochSeconds, kind = unsigned.kind, tags = unsigned.tags, content = unsigned.content,
        )
        val ourKeyPackage = KeyPackageUtils.decodeKeyPackage(Base64.decode(ourEvent.content))

        val commit = commitLocally(routingId) { manager.addMember(routingId, ourKeyPackage.toTlsBytes()) }
        val welcomeTemplate = WelcomeEvent.build(
            welcomeBase64 = Base64.encode(assertNotNull(commit.welcomeBytes)),
            keyPackageEventId = ourEvent.id,
            relays = Relays.DefaultDMRelayList,
            nostrGroupId = routingId,
        )
        val sync = NostrSignerSync(keyPair)
        val rumor = RumorAssembler.assembleRumor<WelcomeEvent>(pubKey = hex, ev = welcomeTemplate)
        val seal = sync.signNormal<SealedRumorEvent>(
            createdAt = rumor.createdAt, kind = SealedRumorEvent.KIND, tags = emptyArray(),
            content = sync.nip44Encrypt(rumor.toJson(), userHex),
        )
        val wrap = GiftWrapEvent.create(event = seal, recipientPubKey = userHex, createdAt = rumor.createdAt)
        storeInApp(db, user, wrap)
        assertNotNull(db.chatRoomDao().findChatRoomById(routingId), "the app did not join")
        return routingId
    }

    companion object {
        /** Seeds the profile rows a member needs before the app can hold one as a participant. */
        suspend fun seedProfile(db: DedunDatabase, publicKey: String, name: String = publicKey.take(8)) {
            val eventId = publicKey.take(32).padEnd(64, 'e')
            db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = publicKey, userName = name, nostrEventId = eventId))
        }

        /** [event] as the sync funnel hands it to the app. */
        suspend fun storeInApp(db: DedunDatabase, user: KeyPair, event: Event, relay: String = "wss://relay.example") =
            db.nostrDao().storeNostrEvent(
                nostrEvent = NostrEvent(
                    id = event.id, pubKey = event.pubKey, kind = event.kind, tags = event.tags,
                    content = event.content, sig = event.sig, createdAt = Instant.fromEpochSeconds(event.createdAt),
                ),
                relayURL = relay, synchronizationRelayURLs = listOf(relay), level = 0, activeKeyPair = user,
            )
    }
}
