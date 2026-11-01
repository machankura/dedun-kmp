package com.machankura.dedun.compose.database.dao

import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRotationManager.Companion.KEY_PACKAGE_LIFETIME_SECONDS
import com.vitorpamplona.quartz.marmot.mls.crypto.Ed25519
import com.vitorpamplona.quartz.marmot.mls.crypto.MlsCryptoProvider
import com.vitorpamplona.quartz.marmot.mls.crypto.X25519
import com.vitorpamplona.quartz.marmot.mls.messages.MlsKeyPackage
import com.vitorpamplona.quartz.marmot.mls.tree.Capabilities
import com.vitorpamplona.quartz.marmot.mls.tree.Credential
import com.vitorpamplona.quartz.marmot.mls.tree.Extension
import com.vitorpamplona.quartz.marmot.mls.tree.LeafNode
import com.vitorpamplona.quartz.marmot.mls.tree.LeafNodeSource
import com.vitorpamplona.quartz.marmot.mls.tree.Lifetime
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.utils.TimeUtils
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.marmot.mls.codec.TlsReader
import com.vitorpamplona.quartz.marmot.mls.messages.KeyPackageBundle
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.nostr.Relays
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/**
 * A real MLS key package for [publicKey], built the same way
 * `DatabaseMarmotRepository.generateKeyPackage` builds this device's own. Entirely local:
 * three key generations, a signed leaf node and a signed key package. No relay, no network,
 * nothing to stub.
 */
internal fun marmotKeyPackageFor(publicKey: HexKey): MarmotKeyPackage {
    val initKp = X25519.generateKeyPair()
    val encKp = X25519.generateKeyPair()
    val sigKp = Ed25519.generateKeyPair()
    val now = TimeUtils.now()

    val unsignedLeaf = LeafNode(
        encryptionKey = encKp.publicKey,
        signatureKey = sigKp.publicKey,
        credential = Credential.Basic(publicKey.hexToByteArray()),
        capabilities = Capabilities(
            // LastResort, then NostrGroupData -- the group's RequiredCapabilities rejects a
            // leaf that does not advertise both, so a fixture without them is refused at
            // addMember rather than at decode.
            extensions = listOf(0x000A, 0xF2EE),
            proposals = listOf(0x000A),
        ),
        leafNodeSource = LeafNodeSource.KEY_PACKAGE,
        // Thirty days, the same as the app's own: quartz 1.15.2 refuses a lifetime
        // over 84 days in `KeyPackageUtils.hasValidLifetime`, and the ingest runs
        // that validator, so a fixture that goes through it has to fit.
        lifetime = Lifetime(notBefore = now, notAfter = now + KEY_PACKAGE_LIFETIME_SECONDS),
        extensions = emptyList(),
        signature = ByteArray(0),
    )
    val leafNode = unsignedLeaf.copy(
        signature = MlsCryptoProvider.signWithLabel(
            sigKp.privateKey,
            "LeafNodeTBS",
            unsignedLeaf.encodeTbs(groupId = null, leafIndex = null),
        ),
    )

    val unsigned = MlsKeyPackage(
        initKey = initKp.publicKey,
        leafNode = leafNode,
        extensions = listOf(Extension(extensionType = 0x000A, extensionData = ByteArray(0))),
        signature = ByteArray(0),
    )
    val keyPackage = unsigned.copy(
        signature = MlsCryptoProvider.signWithLabel(
            sigKp.privateKey,
            "KeyPackageTBS",
            unsigned.encodeTbs(),
        ),
    )

    return MarmotKeyPackage(
        id = publicKey,
        publicKey = publicKey,
        tlsEncodedMarmotKeyPackage = keyPackage.toTlsBytes(),
    )
}

/**
 * The same, as a published kind:30443 on the legacy profile: the app's own
 * `KeyPackageEvent.build` tags around a bare KeyPackage, signed by [keyPair].
 *
 * What a peer on an old build of this app publishes, and therefore what the ingest
 * has to keep accepting for as long as such builds exist.
 */
internal fun legacyKeyPackageEventFor(keyPair: KeyPair): KeyPackageEvent {
    val keyPackage = MlsKeyPackage.decodeTls(
        TlsReader(marmotKeyPackageFor(keyPair.pubKey.toHexKey()).tlsEncodedMarmotKeyPackage)
    )
    val template = KeyPackageEvent.build(
        keyPackageBase64 = Base64.encode(keyPackage.toTlsBytes()),
        dTagSlot = KeyPackageUtils.generateRandomDTag(),
        keyPackageRef = keyPackage.reference().toHexKey(),
        relays = Relays.DefaultDMRelayList,
    )
    return NostrSignerSync(keyPair).sign(template)
}

/**
 * A published kind:30443 on the current profile, exactly as quartz's own factory
 * makes one for Amethyst: a leaf carrying the account identity proof [keyPair]
 * signed, the KeyPackage framed in an MLSMessage, and the current-profile tags.
 *
 * This is the shape a peer on Amethyst 1.15.2 or White Noise publishes -- the
 * one the ingest could not decode before docs/marmot-v2.md Phase 1.
 */
internal suspend fun currentProfileKeyPackageEventFor(keyPair: KeyPair): Pair<KeyPackageEvent, KeyPackageBundle> {
    val signer = NostrSignerInternal(keyPair)
    val bundle = CurrentProfileGroupFactory.createKeyPackage(signer)
    val template = KeyPackageEvent.buildCurrentProfileFrom(
        keyPackage = bundle.keyPackage,
        dTagSlot = KeyPackageUtils.generateRandomDTag(),
        clientName = "test",
    )
    return NostrSignerSync(keyPair).sign(template) to bundle
}

/** The app's stored form of a signed event, as the sync funnel would hand it to NostrDao. */
internal fun KeyPackageEvent.asStoredNostrEvent(): NostrEvent = NostrEvent(
    id = id,
    pubKey = pubKey,
    kind = kind,
    tags = tags,
    content = content,
    sig = sig,
    createdAt = Instant.fromEpochSeconds(createdAt),
)
