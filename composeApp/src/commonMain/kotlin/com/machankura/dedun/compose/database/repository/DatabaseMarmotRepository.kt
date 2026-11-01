package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.MarmotKeyPackageBundle
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.exceptions.MarmotMissingChatGroupException
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.MarmotRepository
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRotationManager.Companion.KEY_PACKAGE_LIFETIME_SECONDS
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.marmot.mls.crypto.Ed25519
import com.vitorpamplona.quartz.marmot.mls.crypto.MlsCryptoProvider
import com.vitorpamplona.quartz.marmot.mls.crypto.X25519
import com.vitorpamplona.quartz.marmot.mls.messages.KeyPackageBundle
import com.vitorpamplona.quartz.marmot.mls.messages.MlsKeyPackage
import com.vitorpamplona.quartz.marmot.mls.tree.Capabilities
import com.vitorpamplona.quartz.marmot.mls.tree.Credential
import com.vitorpamplona.quartz.marmot.mls.tree.Extension
import com.vitorpamplona.quartz.marmot.mls.tree.LeafNode
import com.vitorpamplona.quartz.marmot.mls.tree.LeafNodeSource
import com.vitorpamplona.quartz.marmot.mls.tree.Lifetime
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/**
 * Based on MlsGroupManager in quarts...
 */
class DatabaseMarmotRepository(
    val database: DedunDatabase,
    val scope: CoroutineScope
): MarmotRepository {
    private val logger = Logger.withTag(TAG)

    /**
     * Publish this identity's KeyPackages, one per profile in [profiles], and keep the
     * bundles that back them.
     *
     * The current-profile package is what a peer on Amethyst 1.15.2 or White Noise
     * can add to a group: a leaf carrying the account identity proof -- the account
     * key signing a kind-450 template, never published, that names the leaf's own
     * signature key -- and the KeyPackage framed in an MLSMessage. Only the account
     * signer can make that proof, and it has to be made before the leaf is built, so
     * this takes the [keyPair] and not the public key it used to. The legacy package
     * stays for as long as builds that decode kind:30443 content as a bare KeyPackage
     * are inviting people; docs/marmot-v2.md Phase 9 retires it.
     *
     * **The legacy package is stamped one second after the current-profile one.** An
     * inviter picks a peer's newest package by `created_at`, and an old build then
     * fails to decode a framed one, so the legacy package has to be the newest for as
     * long as it is published at all.
     *
     * Each profile has a slot whose d-tag is minted once and reused on every rotation,
     * so a replacement supersedes the previous event on relays instead of accumulating
     * beside it. A bundle from before slots were recorded has none and gets a fresh
     * one, once.
     */
    override suspend fun publishMarmotKeyPackageBundle(
        keyPair: KeyPair,
        profiles: Set<MarmotProfile>,
    ) {
        requireNotNull(keyPair.privKey) { "Publishing a key package needs an identity that can sign" }
        val publicKey = keyPair.pubKey.toHexKey()
        logger.d("publishMarmotKeyPackageBundle: $publicKey $profiles")

        val identity = publicKey.hexToByteArray()
        val now = TimeUtils.now()

        if (MarmotProfile.CURRENT in profiles) {
            val bundle = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(keyPair))
            val dTag = slotDTagFor(publicKey, MarmotProfile.CURRENT)
            val template = KeyPackageEvent.buildCurrentProfileFrom(
                keyPackage = bundle.keyPackage,
                dTagSlot = dTag,
                clientName = CLIENT_NAME,
                createdAt = now,
            )
            queueKeyPackage(publicKey, template, bundle, MarmotProfile.CURRENT, dTag)
        }

        if (MarmotProfile.LEGACY in profiles) {
            val bundle = generateKeyPackage(identity)
            val dTag = slotDTagFor(publicKey, MarmotProfile.LEGACY)
            val template = KeyPackageEvent.build(
                keyPackageBase64 = Base64.encode(bundle.keyPackage.toTlsBytes()),
                dTagSlot = dTag,
                keyPackageRef = bundle.keyPackage.reference().toHexKey(),
                relays = Relays.DefaultDMRelayList,
                clientName = CLIENT_NAME,
                createdAt = now + 1,
            )
            queueKeyPackage(publicKey, template, bundle, MarmotProfile.LEGACY, dTag)
        }
    }

    /** The slot [profile]'s packages are published under: the one already in use, or a fresh one. */
    private suspend fun slotDTagFor(publicKey: HexKey, profile: MarmotProfile): String =
        database.marmotKeyPackageBundleDao()
            .getAllMarmotKeyPackageBundles(publicKey)
            .firstOrNull { it.profileOrLegacy == profile && it.dTag != null }
            ?.dTag
            ?: KeyPackageUtils.generateRandomDTag()

    /**
     * Hands the notary the kind:30443 to sign and keeps the private half.
     *
     * The bundle's row is keyed on the event id the notary will produce, computed
     * here from the template, so a Welcome that names it by `e` tag finds it; the
     * refs inside the Welcome find it regardless -- see WelcomeBundleMatch.
     */
    private suspend fun queueKeyPackage(
        publicKey: HexKey,
        template: EventTemplate<KeyPackageEvent>,
        bundle: KeyPackageBundle,
        profile: MarmotProfile,
        dTag: String,
    ) {
        database.unsignedNostrEventDao().upsert(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = template.kind,
                tags = template.tags,
                createdAt = Instant.fromEpochSeconds(template.createdAt),
                content = template.content
            )
        )

        val id = EventHasher.hashId(
            pubKey = publicKey,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content
        )
        logger.d("keyPackageEventId: $id (${profile.wireName}, slot $dTag)")

        // ⚠️ SECURITY / KNOWN VULNERABILITY (H1: MLS private keys stored in
        //  plaintext at rest). Despite the `ncryptsec*` field names, the values
        //  below are RAW hex private keys (MLS init / encryption / signature).
        //  Any DB compromise — device backup, forensic extraction, another app
        //  on a rooted/jailbroken device — yields the group's decryption and
        //  signing keys, defeating E2E confidentiality and enabling
        //  impersonation. The same applies to `mlsGroupState` persisted in
        //  MarmotInboundManager.persistGroup (see the warning there).
        //  TODO(security): Encrypt these at rest with a platform-backed
        //   key-encryption-key (Android Keystore / iOS Keychain / a JVM secret
        //   store) — the intended ncryptsec scheme — before this ships.
        database.marmotKeyPackageBundleDao().upsert(
            MarmotKeyPackageBundle(
                id = id,
                publicKey = publicKey,
                // Bare, whichever profile: the Welcome path and the ref matcher decode
                // through KeyPackageUtils.decodeKeyPackage, which reads either, and the
                // published event frames it itself when the profile calls for that.
                tlsEncodedMarmotKeyPackage = bundle.keyPackage.toTlsBytes(),
                ncryptsecInitPrivateKey = bundle.initPrivateKey.toHex(), // TODO(security): encrypt at rest, do not store raw
                ncryptsecEncryptionPrivateKey = bundle.encryptionPrivateKey.toHex(), // TODO(security): encrypt at rest, do not store raw
                ncryptsecSignaturePrivateKey = bundle.signaturePrivateKey.toHex(), // TODO(security): encrypt at rest, do not store raw
                profile = profile.wireName,
                dTag = dTag,
            )
        )
    }

    override suspend fun rotateMarmotKeyPackageBundle(marmotKeyPackageBundle: MarmotKeyPackageBundle) {
        database.marmotKeyPackageBundleDao().upsert(
            marmotKeyPackageBundle.copy(
                rotated = true
            )
        )
    }

    override suspend fun observeMarmotKeyPackageBundles(publicKey: HexKey): Flow<List<MarmotKeyPackageBundle>> {
        return database.marmotKeyPackageBundleDao().observeAllMarmotKeyPackageBundles(publicKey)
    }

    override suspend fun getMarmotKeyPackageBundles(publicKey: HexKey): List<MarmotKeyPackageBundle> {
        return database.marmotKeyPackageBundleDao().getAllMarmotKeyPackageBundles(publicKey)
    }

    override suspend fun observeUnprocessedMarmotInnerEvents(publicKey: HexKey): Flow<List<MarmotInnerEvent>> {
        return database.marmotInnerEventDao().observeUnprocessedMarmotInnerEvents(publicKey)
    }

    override suspend fun observeActiveMarmotKeyPackageBundles(publicKey: HexKey): Flow<List<MarmotKeyPackageBundle>> {
        return database.marmotKeyPackageBundleDao().observeActiveMarmotKeyPackageBundles(publicKey)
    }

    /**
     * Both lookups used to be `?.let`, so a row for a room this device holds no MLS state
     * for returned quietly, having done nothing. Nothing marks such a row, so it stayed
     * queued and was handed back on the next pass, forever -- and while the queue was a
     * single row, forever meant every message queued behind it too. Saying so is the same
     * call `MarmotOutboundDao.inviteMemberToChatRoom` makes, for the same reason: there is
     * no group to encrypt to, and reporting success is a lie the sender acts on.
     */
    override suspend fun encryptAndSendMarmotInnerEvent(
        marmotInnerEvent: MarmotInnerEvent,
        nostrSignerSync: NostrSignerSync
    ) {
        val localChatRoom = database.chatRoomDao().findChatRoomById(marmotInnerEvent.chatRoomId)
            ?: throw MarmotMissingChatGroupException(
                "Cannot send ${marmotInnerEvent.id}: no chat room ${marmotInnerEvent.chatRoomId}"
            )

        val mlsGroup = localChatRoom.chatRoom.toMlsGroup()
            ?: throw MarmotMissingChatGroupException(
                "Cannot send ${marmotInnerEvent.id}: ${marmotInnerEvent.chatRoomId} holds no MLS group state"
            )

        logger.d("mlsGroup: $mlsGroup")

        database.marmotOutboundDao().encryptAndSendMarmotInnerEvent(
            localChatRoom = localChatRoom,
            mlsGroup = mlsGroup,
            marmotInnerEvent = marmotInnerEvent,
            nostrSignerSync = nostrSignerSync
        )
    }

    private fun generateKeyPackage(
        identity: ByteArray,
        dTagSlot: String = KeyPackageUtils.PRIMARY_SLOT,
    ): KeyPackageBundle {
        val initKp = X25519.generateKeyPair()
        val encKp = X25519.generateKeyPair()
        val sigKp = Ed25519.generateKeyPair()

        val leafNode =
            buildKeyPackageLeafNode(
                encryptionKey = encKp.publicKey,
                signatureKey = sigKp.publicKey,
                identity = identity,
                signingKey = sigKp.privateKey,
            )

        val unsigned =
            MlsKeyPackage(
                initKey = initKp.publicKey,
                leafNode = leafNode,
                // LastResort (0x000A) marks this as a last-resort KeyPackage per MLS Extensions draft.
                // MDK always marks KeyPackages as last-resort via .mark_as_last_resort().
                extensions = listOf(
                    Extension(
                        extensionType = 0x000A,
                        extensionData = ByteArray(0)
                    )
                ),
                signature = ByteArray(0),
            )
        val keyPackage =
            unsigned.copy(
                signature =
                    MlsCryptoProvider.signWithLabel(
                        sigKp.privateKey,
                        "KeyPackageTBS",
                        unsigned.encodeTbs(),
                    ),
            )

        val bundle = KeyPackageBundle(
            keyPackage,
            initPrivateKey = initKp.privateKey,
            encryptionPrivateKey = encKp.privateKey,
            signaturePrivateKey = sigKp.privateKey
        )

        return bundle
    }


    private fun buildKeyPackageLeafNode(
        encryptionKey: ByteArray,
        signatureKey: ByteArray,
        identity: ByteArray,
        signingKey: ByteArray,
    ): LeafNode {
        val now = TimeUtils.now()
        val unsigned =
            LeafNode(
                encryptionKey = encryptionKey,
                signatureKey = signatureKey,
                credential = Credential.Basic(identity),
                capabilities =
                    Capabilities(
                        extensions =
                            listOf(
                                0x000A, // LastResort (required by OpenMLS validation)
                                0xF2EE, // NostrGroupData (required by group's RequiredCapabilities)
                            ),
                        proposals =
                            listOf(
                                0x000A, // SelfRemove (required by group's RequiredCapabilities)
                            ),
                    ),
                leafNodeSource = LeafNodeSource.KEY_PACKAGE,
                lifetime = Lifetime(
                    notBefore = now,
                    notAfter = now + KEY_PACKAGE_LIFETIME_SECONDS
                ),
                extensions = emptyList(),
                signature = ByteArray(0),
            )

        val tbs = unsigned.encodeTbs(groupId = null, leafIndex = null)
        val signature = MlsCryptoProvider.signWithLabel(signingKey, "LeafNodeTBS", tbs)
        return unsigned.copy(signature = signature)
    }

    companion object {
        private const val TAG = "DatabaseMarmotRepository"

        /** The `client` tag on every kind:30443 this app publishes. */
        const val CLIENT_NAME = "bennu"
    }
}