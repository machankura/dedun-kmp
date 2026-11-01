package com.machankura.dedun.compose.database.repository

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.appComponents.AppComponentIds
import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.appComponents.GroupProfileV1
import com.vitorpamplona.quartz.marmot.appComponents.accountIdentityProof.AccountIdentityProofV2
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.marmot.mip01Groups.MlsCiphersuite
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mls.codec.TlsReader
import com.vitorpamplona.quartz.marmot.mls.components.AppDataDictionary
import com.vitorpamplona.quartz.marmot.mls.messages.MlsKeyPackage
import com.vitorpamplona.quartz.marmot.mls.tree.Credential
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.utils.RandomInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.nostr.Relays
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * This identity's own KeyPackages, on both profiles, and the join they make possible.
 *
 * docs/marmot-v2.md, Phase 3. The current-profile package is what a peer on Amethyst
 * 1.15.2 or White Noise can add to a group -- a leaf carrying the account identity
 * proof, the KeyPackage framed in an MLSMessage, the current-profile tags -- and the
 * validator a peer runs on it is `KeyPackageUtils.isCryptographicallyValid`, so that
 * is what these run. The last test is the one that fails first whenever the leaf
 * shape drifts: a group made by quartz's own factory, standing in for Amethyst, adds
 * this app's package, and the app joins from the Welcome into a room keyed by the
 * routing id in the group's `0x8004` component.
 */
class CurrentProfileKeyPackageJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )
    private val scope = CoroutineScope(Dispatchers.Default)
    private val marmotRepository = DatabaseMarmotRepository(db, scope)

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair()
    private val userHex = user.pubKey.toHexKey()
    private val relay = "wss://relay.example"

    /** What the notary does with a queued kind:30443: signs it as the identity. */
    private suspend fun signedKeyPackageEvents(): List<KeyPackageEvent> =
        db.unsignedNostrEventDao().getAllUnprocessedUnsignedNostrEvents()
            .filter { it.kind == KeyPackageEvent.KIND && it.pubKey == userHex }
            .map { unsigned ->
                NostrSignerSync(user).signNormal<KeyPackageEvent>(
                    createdAt = unsigned.createdAt.epochSeconds,
                    kind = unsigned.kind,
                    tags = unsigned.tags,
                    content = unsigned.content,
                )
            }

    @Test
    fun `one package per profile, the legacy one stamped a second later`() = runBlocking {
        marmotRepository.publishMarmotKeyPackageBundle(user)

        val events = signedKeyPackageEvents()
        val current = assertNotNull(events.singleOrNull { it.isCurrentProfile() }, "no current-profile package")
        val legacy = assertNotNull(events.singleOrNull { !it.isCurrentProfile() }, "no legacy package")

        // An inviter picks the peer's newest, and an old build cannot decode a framed
        // one, so the legacy package has to be the newest for as long as it exists.
        assertEquals(current.createdAt + 1, legacy.createdAt)

        val bundles = db.marmotKeyPackageBundleDao().getAllMarmotKeyPackageBundles(userHex)
        assertEquals(setOf(MarmotProfile.CURRENT, MarmotProfile.LEGACY), bundles.map { it.profileOrLegacy }.toSet())
        assertEquals(setOf(current.id, legacy.id), bundles.map { it.id }.toSet(), "bundles are not keyed on the events' ids")
    }

    @Test
    fun `both packages pass the validator a peer runs`() = runBlocking {
        marmotRepository.publishMarmotKeyPackageBundle(user)

        signedKeyPackageEvents().forEach { event ->
            assertTrue(KeyPackageUtils.isValid(event), "${event.tags.toList().map { it.toList() }} is not a valid kind:30443")
            assertTrue(KeyPackageUtils.isCryptographicallyValid(event), "the ${if (event.isCurrentProfile()) "current" else "legacy"} package does not verify")
        }
    }

    @Test
    fun `the current-profile leaf carries a proof the account key signed`() = runBlocking {
        marmotRepository.publishMarmotKeyPackageBundle(user, setOf(MarmotProfile.CURRENT))

        val event = signedKeyPackageEvents().single()
        val keyPackage = KeyPackageUtils.decodeKeyPackage(Base64.decode(event.content))
        val credential = keyPackage.leafNode.credential as Credential.Basic
        assertEquals(userHex, credential.identity.toHexKey())

        val dictionary = assertNotNull(AppDataDictionary.fromExtensions(keyPackage.leafNode.extensions), "the leaf has no app data dictionary")
        assertTrue(
            AccountIdentityProofV2.isValid(
                componentData = dictionary[AppComponentIds.ACCOUNT_IDENTITY_PROOF_V2],
                credentialIdentity = credential.identity,
                mlsSignatureKey = keyPackage.leafNode.signatureKey,
                ciphersuite = MlsCiphersuite.DEFAULT,
            ),
            "the identity proof does not bind this leaf to the account",
        )
        // Advertised because the reference client requires it of every leaf it invites.
        assertTrue(event.appComponents().orEmpty().any { it.equals(AppComponentIds.toHex(AppComponentIds.AGENT_TEXT_STREAM_QUIC_V1), true) })
    }

    @Test
    fun `a rotation republishes into the same slot`() = runBlocking {
        marmotRepository.publishMarmotKeyPackageBundle(user)
        val firstSlots = db.marmotKeyPackageBundleDao().getAllMarmotKeyPackageBundles(userHex)
            .associate { it.profileOrLegacy to it.dTag }

        marmotRepository.publishMarmotKeyPackageBundle(user)
        val bundles = db.marmotKeyPackageBundleDao().getAllMarmotKeyPackageBundles(userHex)

        assertEquals(4, bundles.size)
        MarmotProfile.entries.forEach { profile ->
            val slots = bundles.filter { it.profileOrLegacy == profile }.map { it.dTag }.toSet()
            assertEquals(setOf(firstSlots[profile]), slots, "$profile did not keep its slot across a rotation")
        }
    }

    /**
     * The Amethyst case, end to end. The stand-in creates a current-profile group with
     * quartz's own factory, adds this app's published package, and the app is handed the
     * Welcome the way it arrives: as a NIP-59 gift wrap through the sync funnel.
     */
    @Test
    fun `a current-profile group made elsewhere can invite this app, and it joins`() = runBlocking {
        marmotRepository.publishMarmotKeyPackageBundle(user, setOf(MarmotProfile.CURRENT))
        val ourEvent = signedKeyPackageEvents().single()
        val ourKeyPackage = KeyPackageUtils.decodeKeyPackage(Base64.decode(ourEvent.content))
        // Participant rows are keyed onto Profile, and Profile onto its kind 0.
        db.nostrEventDao().upsert(
            NostrEvent(id = "a".repeat(64), pubKey = userHex, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128))
        )
        db.profileDao().upsert(Profile(publicKey = userHex, userName = "us", nostrEventId = "a".repeat(64)))

        // Amethyst, with a fresh account and a random routing id, as the spec requires.
        val inviter = KeyPair()
        val inviterHex = inviter.pubKey.toHexKey()
        val routingId = RandomInstance.bytes(32)
        val group = CurrentProfileGroupFactory.createGroup(
            signer = NostrSignerInternal(inviter),
            nostrGroupId = routingId,
            relays = Relays.DefaultDMRelayList.map { it.url },
            profile = GroupProfileV1(name = "made elsewhere", description = "by the reference factory"),
        )
        val commit = group.addMember(ourKeyPackage.toTlsBytes())
        val welcomeBytes = assertNotNull(commit.welcomeBytes, "adding a member produced no Welcome")

        // The Welcome as it travels: a kind:444 rumor, sealed by the inviter, wrapped by
        // a throwaway key, p-tagged to us.
        val inviterSigner = NostrSignerSync(inviter)
        val welcomeTemplate = WelcomeEvent.build(
            welcomeBase64 = Base64.encode(welcomeBytes),
            keyPackageEventId = ourEvent.id,
            relays = Relays.DefaultDMRelayList,
            nostrGroupId = routingId.toHexKey(),
        )
        val rumor = RumorAssembler.assembleRumor<WelcomeEvent>(pubKey = inviterHex, ev = welcomeTemplate)
        val seal = inviterSigner.signNormal<SealedRumorEvent>(
            createdAt = rumor.createdAt,
            kind = SealedRumorEvent.KIND,
            tags = emptyArray(),
            content = inviterSigner.nip44Encrypt(rumor.toJson(), userHex),
        )
        val wrap = GiftWrapEvent.create(event = seal, recipientPubKey = userHex, createdAt = rumor.createdAt)

        db.nostrDao().storeNostrEvent(
            nostrEvent = NostrEvent(
                id = wrap.id, pubKey = wrap.pubKey, kind = wrap.kind, tags = wrap.tags,
                content = wrap.content, sig = wrap.sig, createdAt = Instant.fromEpochSeconds(wrap.createdAt),
            ),
            relayURL = relay,
            synchronizationRelayURLs = listOf(relay),
            level = 0,
            activeKeyPair = user,
        )

        // The room is keyed on the routing id from the group's 0x8004 component, and its
        // restored state is a current-profile group that names the inviter as admin.
        val room = assertNotNull(db.chatRoomDao().findChatRoomById(routingId.toHexKey()), "no room was made for the routing id")
        assertEquals("made elsewhere", room.chatRoom.subject)
        val restored = assertNotNull(room.chatRoom.toMlsGroup(), "the joined state does not restore")
        assertTrue(restored.currentGroupState().isCurrentProfile, "the restored group is not on the current profile")
        assertEquals(setOf(inviterHex), restored.currentAdminIdentities())
        assertEquals(setOf(inviterHex, userHex), restored.currentMemberIdentities())
        assertEquals(group.epoch, restored.epoch)

        val participants = db.participantDao().findParticipantsByChatRoomId(routingId.toHexKey())
        assertEquals(setOf(inviterHex, userHex), participants.map { it.participantPublicKey }.toSet())
    }
}
