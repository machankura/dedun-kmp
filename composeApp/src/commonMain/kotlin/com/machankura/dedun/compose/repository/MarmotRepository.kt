package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.MarmotKeyPackageBundle
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.flow.Flow

interface MarmotRepository {

    /**
     * Publish this identity's KeyPackages -- one per profile in [profiles], both by
     * default -- and keep the bundles that back them. Needs a [keyPair] that can sign:
     * the current profile's leaf carries a proof only the account key can make.
     */
    suspend fun publishMarmotKeyPackageBundle(
        keyPair: KeyPair,
        profiles: Set<MarmotProfile> = MarmotProfile.entries.toSet(),
    )

    suspend fun rotateMarmotKeyPackageBundle(marmotKeyPackageBundle: MarmotKeyPackageBundle)

    suspend fun observeMarmotKeyPackageBundles(publicKey: HexKey): Flow<List<MarmotKeyPackageBundle>>

    suspend fun getMarmotKeyPackageBundles(publicKey: HexKey): List<MarmotKeyPackageBundle>

    /**
     * The whole outbound backlog for [publicKey], oldest first.
     *
     * See `MarmotInnerEventDao.observeUnprocessedMarmotInnerEvents` for why this is a
     * list: a row that cannot be sent used to be the entire queue. Callers work
     * through it in order, and a row that fails costs only itself.
     */
    suspend fun observeUnprocessedMarmotInnerEvents(publicKey: HexKey): Flow<List<MarmotInnerEvent>>

    /** The bundles still publishable for [publicKey]: neither consumed nor rotated, any profile. */
    suspend fun observeActiveMarmotKeyPackageBundles(publicKey: HexKey): Flow<List<MarmotKeyPackageBundle>>

    suspend fun encryptAndSendMarmotInnerEvent(marmotInnerEvent: MarmotInnerEvent, nostrSignerSync: NostrSignerSync)

    companion object {
        val NO_OP_MARMOT_KEY_PACKAGE_BUNDLE = object: MarmotRepository {
            override suspend fun publishMarmotKeyPackageBundle(
                keyPair: KeyPair,
                profiles: Set<MarmotProfile>,
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun rotateMarmotKeyPackageBundle(marmotKeyPackageBundle: MarmotKeyPackageBundle) {
                TODO("Not yet implemented")
            }

            override suspend fun observeMarmotKeyPackageBundles(publicKey: HexKey): Flow<List<MarmotKeyPackageBundle>> {
                TODO("Not yet implemented")
            }

            override suspend fun getMarmotKeyPackageBundles(publicKey: HexKey): List<MarmotKeyPackageBundle> {
                return emptyList()
            }

            override suspend fun observeUnprocessedMarmotInnerEvents(publicKey: HexKey): Flow<List<MarmotInnerEvent>> {
                TODO("Not yet implemented")
            }

            override suspend fun observeActiveMarmotKeyPackageBundles(publicKey: HexKey): Flow<List<MarmotKeyPackageBundle>> {
                TODO("Not yet implemented")
            }

            override suspend fun encryptAndSendMarmotInnerEvent(
                marmotInnerEvent: MarmotInnerEvent,
                nostrSignerSync: NostrSignerSync
            ) {
                TODO("Not yet implemented")
            }
        }
    }
}