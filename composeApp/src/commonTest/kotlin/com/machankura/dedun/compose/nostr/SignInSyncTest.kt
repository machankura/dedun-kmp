package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whom the sign-in sync asks, and how it reads a relay list to ask further.
 *
 * The bootstrap set is the load-bearing change of Phase 5: it used to be one relay, ours,
 * which is the right answer only for a profile this app made. The hop test pins the
 * outbox reading -- write relays, not read relays -- because asking a user's *read*
 * relays for their own events is the mistake the inbox/outbox split exists to name.
 */
class SignInSyncTest {

    private val publicKey = "a".repeat(64)
    private fun relay(url: String) = RelayUrlNormalizer.normalize(url)

    @Test
    fun `the bootstrap asks every indexer and our own relay once each`() {
        val bootstrap = SignInSync.bootstrapRelays

        assertTrue(bootstrap.containsAll(Relays.DefaultIndexerRelayList), "the indexers hold everyone's kinds 0, 3 and 10002")
        assertTrue(Relays.ephemeral in bootstrap, "a profile created here is found here")
        assertEquals(bootstrap.size, bootstrap.toSet().size, "no relay asked twice")
        assertTrue(bootstrap.size > 1, "one relay was the whole problem")
    }

    @Test
    fun `one request per relay all asking for the same things about the same key`() {
        val relays = listOf(relay("wss://one.example"), relay("wss://two.example"))

        val requests = SignInSync.requests(unsignedNostrEventId = 7, publicKey = publicKey, relays = relays, level = 1)

        assertEquals(relays.map { it.url }, requests.map { it.relayURL })
        requests.forEach { request ->
            assertEquals(SignInSync.PURPOSE, request.purpose)
            assertEquals(1, request.level)
            assertEquals(7L, request.unsignedNostrEventId)
            assertEquals("pending", request.status)
            val filter = request.synchronizationFilters.single()
            assertEquals(listOf(publicKey), filter.authors?.toList())
            assertEquals(SignInSync.KINDS.toList(), filter.kinds?.toList())
        }
    }

    @Test
    fun `a relay list yields its write relays less those already asked`() {
        val tags = arrayOf(
            arrayOf("r", "wss://write.example", "write"),
            arrayOf("r", "wss://both.example"),
            arrayOf("r", "wss://read.example", "read"),
            arrayOf("r", "wss://asked.example"),
            arrayOf("r", "wss://both.example"),          // duplicate
            arrayOf("r", "not a url"),                    // dropped by the normaliser
            arrayOf("p", "wss://tagged-wrong.example"),   // not an r tag
            arrayOf("r"),                                 // no url
        )

        val relays = SignInSync.writeRelaysOf(tags, alreadyAsked = listOf(relay("wss://asked.example")))

        assertEquals(
            listOf(relay("wss://write.example"), relay("wss://both.example")),
            relays,
        )
    }

    @Test
    fun `a relay list with only read relays yields nothing to follow`() {
        val tags = arrayOf(arrayOf("r", "wss://read.example", "read"))

        assertEquals(emptyList(), SignInSync.writeRelaysOf(tags, alreadyAsked = emptyList()))
    }
}
