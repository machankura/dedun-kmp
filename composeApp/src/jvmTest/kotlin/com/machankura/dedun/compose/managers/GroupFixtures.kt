package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.vitorpamplona.quartz.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.quartz.marmot.mls.crypto.Ed25519
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import fr.acinq.bitcoin.crypto.frost.TweakCache
import fr.acinq.bitcoin.ByteVector64
import kotlin.time.Instant

/**
 * Groups with real keys, for the tests that need a group's signature on
 * something: a trusted-dealer key per group, its room id, and a 2-of-3 FROST
 * signature as the room. The shape `SubgroupManagerJvmTest` builds inline, kept
 * in one place for the member-wallet tests, which need a parent, a group and a
 * certificate between them in nearly every case.
 */
internal object GroupFixtures {
    private const val PARTICIPANTS = 3
    private const val THRESHOLD = 2

    val path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH

    fun keyMaterial(secret: String): KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32(secret.padStart(64, '0'))),
        nParticipants = PARTICIPANTS,
        threshold = THRESHOLD,
    )

    fun thresholdKeyOf(material: KeyMaterial): HexKey = material.thresholdPublicKey.value.toHex()

    fun roomOf(material: KeyMaterial): String = SharedKeyDerivation.marmotGroupId(thresholdKeyOf(material), path)

    /** [kind] over [tags] and [content], signed by [material]'s quorum as its room. */
    fun signedAsRoom(material: KeyMaterial, kind: Int, tags: Array<Array<String>>, content: String, createdAt: Long = 1_700_000_000): Event {
        val pubKey = roomOf(material)
        val id = EventHasher.hashId(pubKey = pubKey, createdAt = createdAt, kind = kind, tags = tags, content = content)
        return Event(id = id, pubKey = pubKey, createdAt = createdAt, kind = kind, tags = tags, content = content, sig = signature(material, id))
    }

    /**
     * [group]'s policy on its members' wallets, signed by its room: open at
     * [delay], or closed when [delay] is null. `docs/member-wallet-delay.md`.
     */
    fun memberWalletPolicy(group: KeyMaterial, delay: Long?, createdAt: Long = 1_700_000_000): Event {
        val template = delay?.let { MemberWalletPolicyEvent.open(it, createdAt) } ?: MemberWalletPolicyEvent.closed(createdAt)
        return signedAsRoom(group, template.kind, template.tags, template.content, createdAt)
    }

    /**
     * [group] opened to its members' wallets at [delay]: the policy every member
     * wallet test needs before a member can ask, since a group with none does
     * not guard members' wallets. `docs/member-wallet-delay.md`, D8.
     */
    suspend fun openToMembers(db: DedunDatabase, group: KeyMaterial, delay: Long = GuardianDerivation.DEFAULT_DELAY, createdAt: Long = 1_600_000_000) {
        if (db.chatRoomDao().findChatRoomById(roomOf(group)) == null) seedRoom(db, roomOf(group))
        filePolicy(db, group, memberWalletPolicy(group, delay, createdAt))
    }

    /** [policy] filed as [group]'s room files a signed event: a `GroupSignedEvent` row. */
    suspend fun filePolicy(db: DedunDatabase, group: KeyMaterial, policy: Event) {
        db.groupSignedEventDao().upsert(
            GroupSignedEvent.fromEvent(policy, chatRoomId = roomOf(group), derivationPath = SharedKeyDerivation.formatPath(path), frostSigningSessionId = null)
        )
    }

    /**
     * [parent]'s certificate for [child], granting [guardians] under [policy] --
     * the grant is the caller's, so a test can hand over a wrong one.
     */
    fun birthCertificate(
        parent: KeyMaterial,
        child: KeyMaterial,
        guardians: List<GuardianDerivation.Guardian>,
        policy: SubgroupWalletPolicy? = if (guardians.isEmpty()) SubgroupWalletPolicy.UNGUARDED else SubgroupWalletPolicy.GUARDED,
        admins: List<HexKey> = listOf("a".repeat(64)),
        name: String = "#Child",
    ): Event = signedAsRoom(
        material = parent,
        kind = SubgroupBirthCertificateEvent.KIND,
        tags = SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = roomOf(child),
            parentChatRoomId = roomOf(parent),
            thresholdPublicKey = thresholdKeyOf(child),
            adminPublicKeys = admins,
            name = name,
            path = path,
            guardians = guardians,
            walletPolicy = policy,
        ),
        content = roomOf(child),
    )

    /** [parent]'s guarded certificate for [child], with exactly the grant a parent with no parent makes. */
    fun guardedCertificate(parent: KeyMaterial, child: KeyMaterial): Event =
        birthCertificate(parent, child, GuardianDerivation.grant(thresholdKeyOf(parent), roomOf(parent), emptyList(), roomOf(child)))

    /** [material]'s verified key state on this device, carrying [certificate] when it is somebody's subgroup. */
    suspend fun fileKeyState(db: DedunDatabase, material: KeyMaterial, certificate: Event? = null) {
        seedRoom(db, roomOf(material))
        db.groupKeyStateDao().upsert(
            GroupKeyState(
                chatRoomId = roomOf(material),
                dkgSessionId = "ceremony-" + roomOf(material).take(8),
                thresholdPublicKey = thresholdKeyOf(material),
                derivationPath = SharedKeyDerivation.formatPath(path),
                announcedBy = roomOf(material),
                announcedAt = Instant.fromEpochSeconds(1_700_000_000),
                parentChatRoomId = certificate?.let { SubgroupBirthCertificateEvent.parseParentChatRoomId(it.tags) },
                birthCertificateJson = certificate?.toJson(),
            )
        )
    }

    /** The device's own profile, which every room row names. */
    val owner: HexKey = "0e".repeat(32)

    suspend fun seedProfile(db: DedunDatabase, publicKey: HexKey, name: String = "member") {
        if (db.profileDao().getProfileByPublicKey(publicKey) != null) return
        val nostrEventId = publicKey.take(63) + "f"
        db.nostrEventDao().upsert(
            NostrEvent(id = nostrEventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128))
        )
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = name, nostrEventId = nostrEventId))
    }

    /** A room row for [chatRoomId], if there is none: what a key state, a signed event or a session hangs off. */
    suspend fun seedRoom(db: DedunDatabase, chatRoomId: String, subject: String? = null) {
        seedProfile(db, owner)
        if (db.chatRoomDao().findChatRoomById(chatRoomId) != null) return
        db.chatRoomDao().upsert(ChatRoom(id = chatRoomId, userPublicKey = owner, subject = subject, description = null, mlsGroupState = null))
    }

    /**
     * [material]'s Marmot room on this device, with [owner] holding a share of its
     * key and every one of [members] a participant: a room this device can open
     * a signing session in. Built as a real room is -- the id derived from the
     * group's own key -- for the reason `SubgroupManagerJvmTest.parentWith` gives.
     */
    suspend fun signingRoom(
        db: DedunDatabase,
        material: KeyMaterial,
        members: List<HexKey>,
        subject: String = "#Ekklesia",
        holdsShare: Boolean = true,
    ): LocalChatRoom {
        val roomId = roomOf(material)
        (members + owner).forEach { seedProfile(db, it) }

        val metadata = MarmotGroupData(
            nostrGroupId = roomId,
            name = subject,
            description = SharedKeyDerivation.describe("Admins of $subject."),
            adminPubkeys = members + owner,
            relays = Relays.DefaultDMRelayList.map { it.url },
        )
        val mlsGroup = MlsGroup.create(KeyPair().pubKey, Ed25519.generateKeyPair().privateKey, listOf(metadata.toExtension()))
        db.chatRoomDao().upsert(
            ChatRoom(id = roomId, userPublicKey = owner, subject = subject, description = metadata.description, mlsGroupState = mlsGroup.saveState().encodeTls().toHex())
        )
        db.participantDao().upsert((members + owner).map { Participant(participantPublicKey = it, chatRoomId = roomId, relayHint = null) })

        if (!holdsShare) return requireNotNull(db.chatRoomDao().findChatRoomById(roomId))

        val ceremonyId = "signing-" + roomId.take(8)
        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId,
                chatRoomId = roomId,
                coordinatorPublicKey = owner,
                userPublicKey = owner,
                threshold = THRESHOLD,
                participantCount = members.size + 1,
                stage = DkgRitualStage.COMPLETE,
                hostPublicKey = owner,
                round1Random = "aa".repeat(32),
                round2AuxRandom = "bb".repeat(32),
                thresholdPublicKey = thresholdKeyOf(material),
                secretShare = "cc".repeat(32),
            )
        )
        (members + owner).forEachIndexed { index, member ->
            db.dkgSessionDao().upsert(
                DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = "0${index + 1}".repeat(33))
            )
        }
        return requireNotNull(db.chatRoomDao().findChatRoomById(roomId))
    }

    private fun signature(material: KeyMaterial, eventId: String): String =
        signWith(material, SharedKeyDerivation.derive(thresholdKeyOf(material), path).cache, ByteVector(eventId.hexToByteArray())).toHex()

    /**
     * A FROST signature by [material]'s quorum over [message] under [cache] -- a leaf's, an address's,
     * the room's: 2-of-3 by default, or [threshold] of [participants] for a group of another size.
     */
    fun signWith(
        material: KeyMaterial,
        cache: TweakCache,
        message: ByteVector,
        participants: Int = PARTICIPANTS,
        threshold: Int = THRESHOLD,
    ): ByteVector64 {
        val signerIds = (0 until threshold).toList()

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("a".repeat(63) + "${signerId + 1}"),
                secretShare = material.secretShares[signerId],
                publicShare = material.publicShares[signerId],
                tweakedThresholdPublicKey = cache.tweakedPublicKey,
                message = message,
                extraInput = null,
            )
        }
        val session = Session.create(
            aggregatedNonce = IndividualNonce.aggregate(nonces.map { it.second }).right!!,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { material.publicShares[it] },
            nParticipants = participants,
            threshold = threshold,
            tweakCache = cache,
            message = message,
        )
        val partials = signerIds.mapIndexed { position, signerId ->
            session.sign(nonces[position].first, material.secretShares[signerId], signerId.toUInt()).right!!
        }
        return session.aggregateSigs(partials).right!!
    }
}
