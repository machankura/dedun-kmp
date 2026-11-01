package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Ignore
import androidx.room3.PrimaryKey
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = NostrEvent::class,
            parentColumns = ["id"],
            childColumns = ["nostrEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GiftWrapMessage(
    @PrimaryKey
    val id: HexKey,

    /**
     * Random PublicKey (should it be derived from randomPrivateKey?)
     */
    val publicKey: String,

    val kind: Kind = 1059,

    /**
     * Receiver Public Key Derived from tags
     *
     * [
     *     ["p", receiverPublicKey, "<relay-url>"] // receiver
     *   ]
     *
     */
    val receiverPublicKey: String,

    val receiverRelayHit: String?,

    /**
     *
     * encrypt(GiftWrapSeal, randomPrivateKey, receiverPublicKey)
     */
    val content: String,
    /**
     * Signed using the randomPrivateKey used to encrypt the GiftWrapSeal
     */
    val signature: String,

    /**
     * What gets broadcasts?
     */
    override val nostrEventId: String,

    /**
     * randomTimeUpTo2DaysInThePast
     */
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = Clock.System.now(),
    override val viewedAt: Instant? = null,
    override val deletedAt: Instant? = null, // Who this message should be seen by (used to derive conversationId)
): com.machankura.dedun.compose.database.model.traits.NostrEventEntity,
    com.machankura.dedun.compose.database.model.traits.TimestampedEntity,
    com.machankura.dedun.compose.database.model.traits.LocalStoreEntity,
    com.machankura.dedun.compose.database.model.traits.UserViewableEntity,
    com.machankura.dedun.compose.database.model.traits.SoftDeletableEntity {

    @Ignore
    private val logger = Logger.withTag("GiftWrapMessage")

    /**
     * Whether this gift wrap is addressed to [keyPair], i.e. whether we hold the
     * private key that can unwrap it.
     *
     * NIP-59 encrypts the wrap to its recipient using an ephemeral key that
     * [GiftWrapEvent.create] throws away, so a wrap addressed to anyone else can
     * never be decrypted by us, not even one we sent ourselves.
     */
    fun isAddressedTo(
        keyPair: KeyPair
    ): Boolean = receiverPublicKey.equals(keyPair.pubKey.toHexKey(), ignoreCase = true)

    suspend fun decryptGiftWrapSeal(
        keyPair: KeyPair
    ): GiftWrapSeal? {
        if (kind == GiftWrapEvent.KIND) {
            val giftWrapEvent = GiftWrapEvent(
                id = id,
                pubKey = publicKey,
                tags = arrayOf(
                    PTag(
                        pubKey = receiverPublicKey,
                        relayHint = receiverRelayHit?.let { NormalizedRelayUrl(it) }
                    ).toTagArray()
                ),
                createdAt = createdAt.epochSeconds,
                content = content,
                sig = signature
            )
            logger.d("giftWrapEvent: ${giftWrapEvent.toJson()}")

            giftWrapEvent.recipientPubKey()?.let { recipientPublicKey ->
                logger.d("Recipient PublicKey: $recipientPublicKey")

                if (!recipientPublicKey.equals(keyPair.pubKey.toHexKey(), ignoreCase = true)) {
                    // Not ours to open, and no key we hold ever will be: the wrap is
                    // encrypted to the recipient with a one-off key that
                    // GiftWrapEvent.create() discards, so not even the sender can
                    // unwrap their own gift wrap.
                    logger.d("GiftWrap $id is addressed to $recipientPublicKey, not to us")
                } else {
                    val nostrSigner = NostrSignerInternal(
                        keyPair = KeyPair(privKey = keyPair.privKey)
                    )

                    giftWrapEvent.unwrapOrNull(
                        nostrSigner
                    )?.let { giftWrapSeal ->

                        logger.d("giftWrapSeal: ${giftWrapSeal.toJson()}")
                        return GiftWrapSeal(
                            id = giftWrapSeal.id,
                            publicKey = giftWrapSeal.pubKey,
                            content = giftWrapSeal.content,
                            tags = giftWrapSeal.tags,
                            createdAt = Instant.fromEpochSeconds(giftWrapSeal.createdAt),
                            signature = giftWrapSeal.sig,
                            // The wrap this came out of. The column existed and was never
                            // written, so nothing could say which wraps had been opened;
                            // the inbox sweep asks exactly that. A seal from before this
                            // reads back null, is swept once, and comes back with the link.
                            giftWrapMessageId = id,
                        )
                    }
                }
            }
        }

        return null
    }
}