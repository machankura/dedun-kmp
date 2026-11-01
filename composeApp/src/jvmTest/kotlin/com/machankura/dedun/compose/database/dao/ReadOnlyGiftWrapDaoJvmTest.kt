package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.NostrEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * A gift wrap addressed to a read-only identity is kept and not opened -- and, since
 * docs/multiple-profiles.md, opened later by the inbox sweep, along with any wrap that
 * arrived while another profile on the device was the open one.
 *
 * `storeNostrEvent` is `@Transaction` and indexes inside it, and a wrap addressed to the
 * active key that cannot be unsealed throws -- so before the guard in `indexNostrEvent`
 * the throw took the event with it, and looked in the logs like a decryption failure.
 * Worse, `decryptGiftWrapSeal` builds `KeyPair(privKey = keyPair.privKey)`, and in
 * quartz a `KeyPair` given neither key generates a fresh one: the unseal would have run
 * with a key nobody has. A read-only identity holds no key by construction, and the
 * wrap is what the branch for someone else's mail already does with it: stored, so the
 * key that opens it can be signed in later.
 */
class ReadOnlyGiftWrapDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val relay = "wss://relay.example"

    /** The user, as the sync pump sees them when the device holds only their public key. */
    private val us = KeyPair()
    private val readOnlyUs = KeyPair(pubKey = us.pubKey)
    private val peer = KeyPair()

    private fun wrapTo(recipient: KeyPair): NostrEvent {
        val signer = NostrSignerSync(peer)
        val seal = signer.signNormal<SealedRumorEvent>(
            createdAt = 1_700_000_000,
            kind = SealedRumorEvent.KIND,
            tags = emptyArray(),
            content = signer.nip44Encrypt(
                plaintext = """{"kind":14,"content":"dumela"}""",
                toPublicKey = recipient.pubKey.toHexKey(),
            ),
        )
        val giftWrap = GiftWrapEvent.create(
            event = seal,
            recipientPubKey = recipient.pubKey.toHexKey(),
            createdAt = 1_700_000_100,
        )
        return NostrEvent(
            id = giftWrap.id,
            pubKey = giftWrap.pubKey,
            kind = giftWrap.kind,
            tags = giftWrap.tags,
            content = giftWrap.content,
            sig = giftWrap.sig,
            createdAt = Instant.fromEpochSeconds(giftWrap.createdAt),
        )
    }

    @Test
    fun `a wrap addressed to a read-only identity is stored, and its seal is not`() = runBlocking {
        val wrap = wrapTo(us)

        db.nostrDao().storeNostrEvent(
            nostrEvent = wrap,
            relayURL = relay,
            synchronizationRelayURLs = listOf(relay),
            level = 0,
            activeKeyPair = readOnlyUs,
        )

        assertNotNull(db.nostrEventDao().getNostrEventById(wrap.id), "the event itself must survive the failed unseal")
        assertEquals(listOf(wrap.id), db.giftWrapMessageDao().getAllGiftWrapMessages().map { it.id })
        assertEquals(emptyList(), db.giftWrapSealDao().getAllGiftWrapSeals(), "nothing was opened")
    }

    /** The same wrap, once the key is here: the seal comes out. The guard is only about the key's absence. */
    @Test
    fun `the same wrap opens once the identity holds its key`() = runBlocking {
        val wrap = wrapTo(us)

        db.nostrDao().storeNostrEvent(
            nostrEvent = wrap,
            relayURL = relay,
            synchronizationRelayURLs = listOf(relay),
            level = 0,
            activeKeyPair = us,
        )

        assertEquals(1, db.giftWrapSealDao().getAllGiftWrapSeals().size)
    }

    // --- The inbox, reopened: Phase 6 of docs/multiple-profiles.md ---

    /** Another profile on the device, open when the wrap for us arrived. */
    private val other = KeyPair()

    /**
     * The case the sweep exists for. A wrap for us, fetched while another profile was
     * open: stored, found not to be addressed to the active key, and left -- and
     * `storeNostrEvent` never indexes an event it already holds, so the live subscription
     * re-delivering it later would change nothing. The sweep runs it through the index
     * again under our key.
     */
    @Test
    fun `a wrap fetched under another profile's key is opened by the sweep, once`() = runBlocking {
        val wrap = wrapTo(us)
        db.nostrDao().storeNostrEvent(
            nostrEvent = wrap,
            relayURL = relay,
            synchronizationRelayURLs = listOf(relay),
            level = 0,
            activeKeyPair = other,
        )
        assertEquals(listOf(wrap.id), db.giftWrapMessageDao().getAllGiftWrapMessages().map { it.id }, "stored")
        assertEquals(emptyList(), db.giftWrapSealDao().getAllGiftWrapSeals(), "and not opened")

        // Re-delivery changes nothing: the id is known, and the DAO returns.
        db.nostrDao().storeNostrEvent(wrap, relay, listOf(relay), 0, us)
        assertEquals(emptyList(), db.giftWrapSealDao().getAllGiftWrapSeals(), "re-delivery does not reopen a known event")

        assertEquals(1, db.nostrDao().reopenInbox(us))
        assertEquals(1, db.giftWrapSealDao().getAllGiftWrapSeals().size, "the seal is out")
        assertEquals(1, db.giftWrapPayloadDao().getAllGiftWrapUnsigned().size, "and the payload with it")

        assertEquals(0, db.nostrDao().reopenInbox(us), "nothing left to open")
        assertEquals(1, db.giftWrapSealDao().getAllGiftWrapSeals().size)
    }

    /** The other profile's sweep finds nothing of ours to open, and a read-only pair opens nothing. */
    @Test
    fun `the sweep opens only what is addressed to the key it runs with`() = runBlocking {
        val wrap = wrapTo(us)
        db.nostrDao().storeNostrEvent(wrap, relay, listOf(relay), 0, other)

        assertEquals(0, db.nostrDao().reopenInbox(other))
        assertEquals(0, db.nostrDao().reopenInbox(readOnlyUs))
        assertEquals(emptyList(), db.giftWrapSealDao().getAllGiftWrapSeals())
    }

    /** The upgrade the npub plan left open: stored read-only, opened once the nsec is here. */
    @Test
    fun `a wrap stored while the identity was read-only is opened once it holds its key`() = runBlocking {
        val wrap = wrapTo(us)
        db.nostrDao().storeNostrEvent(wrap, relay, listOf(relay), 0, readOnlyUs)
        assertEquals(emptyList(), db.giftWrapSealDao().getAllGiftWrapSeals())

        assertEquals(1, db.nostrDao().reopenInbox(us))
        assertEquals(1, db.giftWrapSealDao().getAllGiftWrapSeals().size)
    }
}
