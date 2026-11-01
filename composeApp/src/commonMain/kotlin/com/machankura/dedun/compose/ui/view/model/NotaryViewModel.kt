package com.machankura.dedun.compose.ui.view.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.repository.MarmotRepository
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageFetcher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.machankura.dedun.compose.nostr.SignInSync
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.database.model.NostrEvent
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class NotaryViewModel(
    val activeIdentityStateFlow: StateFlow<Identity?>,
    val nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
    val chatRepository: com.machankura.dedun.compose.repository.ChatRepository,
    val marmotRepository: MarmotRepository,
    val scope: CoroutineScope,
): ViewModel() {

    companion object {
        private const val TAG = "NotaryViewModel"


        fun factory(
            activeIdentityStateFlow: StateFlow<Identity?>,
            nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
            chatRepository: com.machankura.dedun.compose.repository.ChatRepository,
            marmotRepository: MarmotRepository,
            scope: CoroutineScope,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NotaryViewModel(
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                    marmotRepository = marmotRepository,
                    scope = scope,
                )
            }
        }
    }

    private val logger = Logger.withTag(TAG)

    private fun observeActiveNostrKey() {
        scope.launch {
            // One flow, not a `flatMapLatest` into the node's key manager: the identity
            // carries its key from the moment it exists, so there is nothing to wait for.
            // `distinctUntilChanged` on the key stays -- an identity re-emitted with the same
            // key must not tear down and restart the observers below.
            activeIdentityStateFlow
                .map { identity -> identity?.nostrPrivateKey }
                .distinctUntilChanged()
                .collectLatest { nostrPrivateKey ->
                    if (nostrPrivateKey == null) return@collectLatest

                    val keyPair = KeyPair(
                        privKey = nostrPrivateKey.value.toByteArray()
                    )
                    logger.d("Notary observing: ${keyPair.pubKey.toHexKey()}")

                    // The observers run as children of collectLatest: when the active
                    // key changes they are cancelled, so they never process events for
                    // a stale wallet and never run in duplicate.
                    coroutineScope {
                        launch { observeUnsignedNostrEvents(keyPair) }
                        launch { observeUnsealedGiftWrapPayloads(keyPair) }
                        launch { observeUnprocessedMarmotInnerEvents(keyPair) }
                        launch { observeActiveMarmotKeyPackageBundles(keyPair) }
                    }
                }
        }
    }

    init {
        observeActiveNostrKey()
    }

    /**
     * Where a signed event goes.
     *
     * Everything goes to the default DM relays, as it always has. A KeyPackage also
     * goes to the account's NIP-65 write set, because that is where
     * `transports/nostr.md` has a conformant peer look for it -- "there is no
     * dedicated KeyPackage relay list" -- and a package published only where the
     * legacy kind:10051 points is invisible to them. Union rather than replace, so a
     * peer still on the MIP-era path keeps finding us on the relays it always did.
     */
    private suspend fun publishRelaysFor(event: Event): List<String> {
        val defaults = com.machankura.dedun.compose.nostr.Relays.DefaultDMRelayList
        if (event.kind != KeyPackageEvent.KIND) return defaults.map { it.url }

        val outbox = nostrRepository.getNostrEvent(event.pubKey, AdvertisedRelayListEvent.KIND)
            ?.let { SignInSync.writeRelaysOf(it.tags, alreadyAsked = emptyList()) }
            .orEmpty()

        return KeyPackageFetcher.publishRelaysFor(
            myOutbox = outbox,
            legacyKeyPackageRelayList = defaults,
        ).map { it.url }
    }

    // Keeps one failing item from killing the collector for the rest of the session.
    private inline fun guardNotarization(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e("Notarization failed: $what", e)
        }
    }

    /**
     * Sign and publish everything queued for this key, not just the lowest kind.
     *
     * The last of the notary's three queues to be a single row, and the one already
     * observed to stall: `NostrDao.commitPublishedNostrEvent` carries the account of an
     * indexing throw rolling `signedAt` back, after which nothing queued behind that row
     * was ever signed. That was fixed by moving indexing out of the transaction, which
     * closed the one known way in and left the queue as narrow as it was.
     *
     * `UnsignedNostrEvent.equals` is a plain value comparison, so this was the frozen
     * variant rather than the retrying one: the failed row's re-emission compared equal to
     * the last, `distinctUntilChanged` dropped it, and the collector saw nothing again for
     * the life of the session. Both go, for the reason given on
     * [observeUnprocessedMarmotInnerEvents].
     *
     * Serially and in the query's order: kind 0 is what NavigationViewModel is waiting on,
     * and a replaceable kind queued twice must go out oldest first or the older one wins.
     */
    private suspend fun observeUnsignedNostrEvents(
        keyPair: KeyPair
    ) {
        val tempSigner = NostrSignerSync(
            keyPair
        )
        logger.i { "observeUnsignedNostrEvents: ${keyPair.pubKey.toHexKey()}" }
        nostrRepository.observeUnsignedNostrEvents(
            publicKey = keyPair.pubKey.toHexKey()
        ).collect { unsignedNostrEvents ->
            unsignedNostrEvents.forEach { unsignedNostrEvent ->
                // Per row: a publish that throws rolls back its own transaction and
                // nothing else, so the row stays queued for the next pass and the rest of
                // the account's events still go out.
                guardNotarization("unsigned event ${unsignedNostrEvent.id} (kind ${unsignedNostrEvent.kind})") {
                    logger.d("Unsigned: ${unsignedNostrEvent.kind}")
                    val event = tempSigner.signNormal<Event>(
                        createdAt = unsignedNostrEvent.createdAt.epochSeconds,
                        kind = unsignedNostrEvent.kind,
                        tags = unsignedNostrEvent.tags,
                        content = unsignedNostrEvent.privateTags?.let { PrivateTagsInContent.encryptNip44(it, tempSigner) } ?: unsignedNostrEvent.content
                    )
                    logger.d("Signed: ${event.toJson()}")

                    nostrRepository.publishNostrEvent(
                        unsignedNostrEvent,
                        NostrEvent(
                            id = event.id,
                            pubKey = event.pubKey,
                            kind = event.kind,
                            tags = event.tags,
                            content = event.content,
                            createdAt = Instant.fromEpochSeconds(event.createdAt),
                            sig = event.sig,
                            unsignedNostrEventId = unsignedNostrEvent.id
                        ),
                        relayURLs = publishRelaysFor(event),
                        activeKeyPair = keyPair
                    )
                }
            }
        }
    }


    /**
     * Seal everything queued for sealing, not just the oldest thing.
     *
     * Same shape as [observeUnprocessedMarmotInnerEvents] and a harder stop. A payload
     * `sealGiftWrapPayload` declines to seal keeps `giftWrapSealId` null, so it came back
     * as the head of the next emission -- and `GiftWrapPayload.equals` is a plain value
     * comparison, so `distinctUntilChanged` read that as nothing having changed and
     * dropped it. One refusal and nothing on this queue was collected again for the rest
     * of the session, whatever was queued afterwards.
     */
    private suspend fun observeUnsealedGiftWrapPayloads(
        keyPair: KeyPair
    ) {
        val tempSigner = NostrSignerSync(
            keyPair
        )
        logger.i { "observeUnsealedGiftWrapPayloads: ${keyPair.pubKey.toHexKey()}" }
        chatRepository.observeUnsealedGiftWrapPayloads(
            publicKey = keyPair.pubKey.toHexKey()
        ).collect { giftWrapPayloads ->
            giftWrapPayloads.forEach { giftWrapPayload ->
                guardNotarization("gift wrap payload ${giftWrapPayload.id}") {
                    logger.d("seal and deliver giftWrapPayload: $giftWrapPayload")

                    chatRepository.sealGiftWrapPayload(
                        giftWrapPayload,
                        nostrSignerSync = tempSigner
                    )
                }
            }
        }
    }

    /**
     * Encrypt and send everything a marmot room has queued, not just the oldest thing.
     *
     * The queue used to arrive one row at a time and the row only left it by being sent,
     * so a message that could never be encrypted was a stopper: it came back at the head
     * of every later emission and everything behind it -- in every room this key belongs
     * to -- waited on a send that was never going to happen. Those messages show in the
     * chat as unsealed, and stay that way.
     *
     * `distinctUntilChanged` went with it. A queue re-emits because its table changed,
     * which is the signal to look again; comparing that emission against the last one
     * asks "is this new work?" when the question is "is there work left?". Here it
     * happened to suppress nothing -- `MarmotInnerEvent.equals` compares an `@Ignore`d
     * per-instance Logger, so no two reads of a row are ever equal -- which is not a
     * property to leave a queue standing on. Next door, where the payload's equals is an
     * honest value comparison, it dropped every emission after the first refusal.
     *
     * Serially, in the order the query gives them: each send ratchets its room's MLS
     * state forward and writes it back, and `encryptAndSendMarmotInnerEvent` reads that
     * state fresh per row. Two sends for one room in parallel would encrypt from the same
     * generation and one of them would be undecryptable.
     */
    private suspend fun observeUnprocessedMarmotInnerEvents(
        keyPair: KeyPair
    ) {
        val tempSigner = NostrSignerSync(
            keyPair
        )
        logger.i { "observeUnprocessedMarmotInnerEvents: ${keyPair.pubKey.toHexKey()}" }
        marmotRepository.observeUnprocessedMarmotInnerEvents(
            publicKey = keyPair.pubKey.toHexKey()
        ).collect { marmotInnerEvents ->
            marmotInnerEvents.forEach { marmotInnerEvent ->
                // Per row, so one that cannot be encrypted costs only itself. It stays
                // queued and is tried again on the next pass -- a room whose state has
                // not caught up yet is worth retrying, and one that never will is at
                // least no longer standing in front of anybody.
                guardNotarization("marmot inner event ${marmotInnerEvent.id}") {
                    logger.d("encrypt and broadcast: $marmotInnerEvent")

                    marmotRepository.encryptAndSendMarmotInnerEvent(
                        marmotInnerEvent,
                        nostrSignerSync = tempSigner
                    )
                }
            }
        }
    }


    /**
     * Keeps one publishable bundle per Marmot profile on the relays.
     *
     * A bundle leaves the active set when a Welcome consumes it or the user rotates
     * it, and whichever profile is then left without one gets a fresh package. Per
     * profile rather than "any bundle at all", because a peer on the current profile
     * consumes only the current-profile package and the legacy one still being active
     * would otherwise mean nothing is republished for them.
     */
    private suspend fun observeActiveMarmotKeyPackageBundles(
        keyPair: KeyPair,
    ) {
        logger.i { "observeActiveMarmotKeyPackageBundles: ${keyPair.pubKey.toHexKey()}" }
        marmotRepository.observeActiveMarmotKeyPackageBundles(
            publicKey = keyPair.pubKey.toHexKey()
        ).distinctUntilChanged().collect { activeBundles ->
            val covered = activeBundles.map { it.profileOrLegacy }.toSet()
            val missing = MarmotProfile.entries.filterNot { it in covered }.toSet()

            if (missing.isNotEmpty()) {
                guardNotarization("key package bundle for ${keyPair.pubKey.toHexKey()}") {
                    delay(3.seconds)
                    // TODO: We shouldn't try to create a key package without profile
                    //  we should be check profile in the query to get updates
                    logger.d("Need to create new key package bundle(s): $missing")
                    marmotRepository.publishMarmotKeyPackageBundle(
                        keyPair = keyPair,
                        profiles = missing,
                    )
                }
            }
        }
    }

}
