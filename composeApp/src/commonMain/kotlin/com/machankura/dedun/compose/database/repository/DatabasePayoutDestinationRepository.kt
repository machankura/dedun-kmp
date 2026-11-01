package com.machankura.dedun.compose.database.repository

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.GroupPayoutDestinations
import com.machankura.dedun.compose.managers.GroupWalletManager
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.network.LightningAddressResolver
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.Sendable
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.UnusedAddressCheck
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class DatabasePayoutDestinationRepository(
    private val database: DedunDatabase,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
    /** The identity's group wallets, which come and go with its Electrum client. */
    private val wallets: () -> GroupOnchainWallets? = { null },
    private val resolver: LightningAddressResolver = LightningAddressResolver(),
) : PayoutDestinationRepository {
    private val logger = Logger.withTag(TAG)

    override suspend fun standing(chatRoomId: String, chain: Chain): PayoutDestinationRepository.Standing? = try {
        val identity = activeIdentity.value
        database.chatRoomDao().findChatRoomById(chatRoomId)?.let { room ->
            val disbanded = room.chatRoom.takeIf { it.marmotProfile() == MarmotProfile.CURRENT }
                ?.toMlsGroup()?.currentGroupState()?.isDisbanded == true
            val me = identity?.nostrPublicKey
            PayoutDestinationRepository.Standing(
                isGroup = !disbanded && MemberWalletManager.couldGuard(database, room),
                canSet = identity?.canSign == true,
                current = me?.let { GroupPayoutDestinations.destinationOf(database, chatRoomId, chain, it) },
                profileLightningAddress = me?.let { database.profileDao().getProfileByPublicKey(it)?.lud16 },
            )
        }
    } catch (e: Throwable) {
        logger.e("Error reading where $chatRoomId pays this profile", e)
        null
    }

    override suspend fun destinationsIn(chatRoomId: String, chain: Chain): Map<HexKey, GroupPayoutDestination> = try {
        GroupPayoutDestinations.destinationsIn(database, chatRoomId, chain)
    } catch (e: Throwable) {
        logger.e("Error reading where $chatRoomId pays its members", e)
        emptyMap()
    }

    override suspend fun resolve(address: LightningAddress): LightningAddressResolver.Resolution {
        // No identity is no profile to protect, and no profile to set anything for either.
        val tor = activeIdentity.value?.userPrefs?.getIsTorEnabled?.first() ?: true
        return resolver.resolve(address, torEnabled = tor)
    }

    override suspend fun usageOf(chatRoomId: String, chain: Chain, script: ByteVector): UnusedAddressCheck.Usage {
        val wallets = wallets() ?: return UnusedAddressCheck.Usage.Unknown
        // The group's own addresses are those its wallet has handed out: wait a moment for its
        // first state, and check without it rather than not at all.
        val own = runCatching {
            GroupWalletManager.walletFor(database, chatRoomId, chain)?.let { wallet ->
                withTimeoutOrNull(OWN_WALLET_WAIT) { wallets.walletFor(wallet.account).state.first() }
            }
        }.getOrNull()
        return wallets.usageOf(chain, script, own)
    }

    override suspend fun set(chatRoomId: String, chain: Chain, lightningAddress: LightningAddress, sendable: Sendable?, onchainAddress: String?) {
        val key = activeIdentity.value?.nostrPrivateKey ?: throw IllegalStateException("This profile cannot sign")
        val signed = GroupPayoutDestinationEvent.build(key, chatRoomId, chain, lightningAddress, sendable, onchainAddress)
        GroupPayoutDestinations.publish(database, chatRoomId, signed)
    }

    override suspend fun remove(chatRoomId: String, chain: Chain) {
        val key = activeIdentity.value?.nostrPrivateKey ?: throw IllegalStateException("This profile cannot sign")
        GroupPayoutDestinations.publish(database, chatRoomId, GroupPayoutDestinationEvent.buildRemoved(key, chatRoomId, chain))
    }

    companion object {
        private const val TAG = "DatabasePayoutDestinationRepository"
        private val OWN_WALLET_WAIT = 5.seconds
    }
}
