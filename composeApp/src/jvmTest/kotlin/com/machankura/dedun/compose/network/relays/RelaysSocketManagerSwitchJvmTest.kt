package com.machankura.dedun.compose.network.relays

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.machankura.dedun.compose.database.model.Relay
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.toWalletId
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.network.sockets.NostrSocketClient
import com.machankura.dedun.compose.network.sockets.NostrSocketClientFactory
import com.machankura.dedun.compose.network.sockets.SocketConnectionClosedCallback
import com.machankura.dedun.compose.network.sockets.SocketConnectionOpenedCallback
import com.machankura.dedun.compose.network.sockets.SocketConnectionReopenedCallback
import com.machankura.dedun.compose.repository.CachingImportRepository
import com.machankura.dedun.compose.repository.RelayRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The relay observer follows the identity that is open, and stops following the one
 * that was.
 *
 * Before Phase 2 of docs/multiple-profiles.md it kept one observer per pubkey and only
 * ever cancelled the one for the pubkey being started, so after a switch the previous
 * identity's observer was still feeding the pool -- and the pool followed whichever
 * relay list emitted last. The regression this pins is exactly that: after switching
 * from A to B, a re-emission of A's relay list changes nothing.
 */
class RelaysSocketManagerSwitchJvmTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    private val keyA = PrivateKey(ByteVector32("0a".repeat(32)))
    private val keyB = PrivateKey(ByteVector32("0b".repeat(32)))

    /** Each identity's relay list, as the database would emit it. */
    private val relayLists = mutableMapOf<String, MutableStateFlow<List<Relay>>>()

    private val relayRepository = object : RelayRepository {
        override suspend fun observePublicKeyRelays(publicKey: String): Flow<List<Relay>> =
            relayLists.getOrPut(publicKey) { MutableStateFlow(emptyList()) }
    }

    private fun prefsStore(name: String) = PreferenceDataStoreFactory.createWithPath {
        temporaryFolder.newFolder().resolve("$name.preferences_pb").path.toPath()
    }

    private fun identity(key: PrivateKey) = Identity.signing(
        id = key.publicKey().xOnly().toWalletId(),
        kind = IdentityKind.NostrSecret,
        nostrPrivateKey = key,
        userPrefs = UserPrefs(prefsStore("user")),
        internalPrefs = InternalPrefs(prefsStore("internal")),
        business = null,
    )

    private fun relays(key: PrivateKey, vararg urls: String) =
        urls.map { Relay(publicKey = key.nostrPublicKeyHex(), type = "user", url = it, read = true, write = true) }

    private fun emit(key: PrivateKey, vararg urls: String) {
        relayLists.getOrPut(key.nostrPublicKeyHex()) { MutableStateFlow(emptyList()) }.value = relays(key, *urls)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun RelaysSocketManager.awaitRelays(expected: Set<String>) = withTimeout(5_000) {
        while (relayUrls != expected) delay(20)
    }

    @Test
    fun `after a switch the pool follows the new identity, and the old one's list no longer moves it`() = runBlocking<Unit> {
        val activeIdentity = MutableStateFlow<Identity?>(null)
        emit(keyA, "wss://a-one.example", "wss://a-two.example")
        emit(keyB, "wss://b.example")
        val manager = RelaysSocketManager(
            activeIdentityStateFlow = activeIdentity,
            nostrSocketClientFactory = SwitchFakeSocketClientFactory(),
            cachingImportRepository = CachingImportRepository.NO_OP_CACHING_IMPORT_REPOSITORY,
            relayRepository = relayRepository,
            scope = scope,
        )

        activeIdentity.value = identity(keyA)
        manager.awaitRelays(setOf("wss://a-one.example", "wss://a-two.example"))

        // The switch: cleared, then the next one.
        activeIdentity.value = null
        activeIdentity.value = identity(keyB)
        manager.awaitRelays(setOf("wss://b.example"))

        // A's list moves on -- as it would when a sync for A's rows lands -- and the pool
        // must not care: A is not open.
        emit(keyA, "wss://a-three.example")
        delay(500)
        assertEquals(setOf("wss://b.example"), manager.relayUrls)

        // B's list moves on, and the pool does follow.
        emit(keyB, "wss://b.example", "wss://b-two.example")
        manager.awaitRelays(setOf("wss://b.example", "wss://b-two.example"))
    }

    @Test
    fun `an identity whose list is still empty leaves the pool as it was`() = runBlocking<Unit> {
        val activeIdentity = MutableStateFlow<Identity?>(null)
        emit(keyA, "wss://a.example")
        val manager = RelaysSocketManager(
            activeIdentityStateFlow = activeIdentity,
            nostrSocketClientFactory = SwitchFakeSocketClientFactory(),
            cachingImportRepository = CachingImportRepository.NO_OP_CACHING_IMPORT_REPOSITORY,
            relayRepository = relayRepository,
            scope = scope,
        )
        activeIdentity.value = identity(keyA)
        manager.awaitRelays(setOf("wss://a.example"))

        // B has no relay list yet: nothing is known to replace A's with, and the pool
        // keeps what it had -- the behaviour `updateRelayPools` has always had for an
        // empty list, unchanged by the switch.
        activeIdentity.value = null
        activeIdentity.value = identity(keyB)
        delay(500)
        assertEquals(setOf("wss://a.example"), manager.relayUrls)

        emit(keyB, "wss://b.example")
        manager.awaitRelays(setOf("wss://b.example"))
    }
}

private class SwitchFakeSocketClientFactory : NostrSocketClientFactory {
    override fun create(
        wssUrl: String,
        incomingCompressionEnabled: Boolean,
        onSocketConnectionOpened: SocketConnectionOpenedCallback?,
        onSocketConnectionClosed: SocketConnectionClosedCallback?,
        onSocketConnectionReopened: SocketConnectionReopenedCallback?,
    ): NostrSocketClient = SwitchFakeNostrSocketClient(wssUrl)
}

private class SwitchFakeNostrSocketClient(override val socketUrl: String) : NostrSocketClient {
    private val _incomingMessages = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 8)
    override val incomingMessages: SharedFlow<NostrIncomingMessage> = _incomingMessages.asSharedFlow()
    override var autoReconnect: Boolean = false
    override suspend fun close() = Unit
    override suspend fun ensureSocketConnectionOrThrow() = Unit
    override suspend fun sendMESSAGE(text: String, ensureSessionBeforeSend: Boolean) = Unit
    override suspend fun sendAUTH(signedEvent: JsonObject) = Unit
    override suspend fun sendCLOSE(subscriptionId: String) = Unit
    override suspend fun sendCOUNT(data: JsonObject): String = "unused"
    override suspend fun sendEVENT(signedEvent: JsonObject) = Unit
    override suspend fun sendREQ(subscriptionId: String, data: JsonObject) = Unit
}
