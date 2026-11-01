package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Ignore
import androidx.room3.PrimaryKey
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = GiftWrapMessage::class,
            parentColumns = ["id"],
            childColumns = ["giftWrapMessageId"],
            onDelete = ForeignKey.CASCADE,
        ),

    ],
)
data class GiftWrapSeal(
    @PrimaryKey
    val id: HexKey,
    /**
     * senderPublicKey
     */
    val publicKey: String,

    val kind: Kind = 13,
    /**
     * No tags
     */
    val tags: Array<Array<String>> = emptyArray(),

    /**
     *
     * nip44Encrypt(unsignedKind14, senderPrivateKey, receiverPublicKey)
     */
    val content: String,

    /**
     * signed by senderPrivateKey
     */
    val signature: String,

    /**
     * Associated GiftWrapMessage
     */
    val giftWrapMessageId: String? = null,

    /**
     * randomTimeUpTo2DaysInThePast()
     */
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = Clock.System.now(),
    override val viewedAt: Instant? = null,
    override val deletedAt: Instant? = null, // Who this message should be seen by (used to derive conversationId)
): com.machankura.dedun.compose.database.model.traits.TimestampedEntity,
    com.machankura.dedun.compose.database.model.traits.LocalStoreEntity,
    com.machankura.dedun.compose.database.model.traits.UserViewableEntity,
    com.machankura.dedun.compose.database.model.traits.SoftDeletableEntity {

    @Ignore
    private val logger = Logger.withTag("GiftWrapMessage")

    suspend fun decryptGiftWrapPayload(
        privateKey: ByteArray
    ): GiftWrapPayload? {
        if (kind == SealedRumorEvent.KIND) {
            val sealedRumorEvent = SealedRumorEvent(
                id = id,
                pubKey = publicKey,
                tags = tags,
                createdAt = createdAt.epochSeconds,
                content = content,
                sig = signature
            )
            logger.d("sealedRumorEvent: ${sealedRumorEvent.toJson()}")

            val nostrSigner = NostrSignerInternal(
                keyPair = KeyPair(privKey = privateKey)
            )

            sealedRumorEvent.unsealOrNull(
                nostrSigner
            )?.let { giftWrapPayload ->
                logger.d("giftWrapPayload: ${giftWrapPayload.toJson()}")

                val giftWrapPayload = GiftWrapPayload(
                    id = id,
                    giftWrapSealId = id,
                    publicKey = giftWrapPayload.pubKey,
                    kind = giftWrapPayload.kind,
                    tags = giftWrapPayload.tags,
                    content = giftWrapPayload.content,
                    createdAt = Instant.fromEpochSeconds(giftWrapPayload.createdAt),
                    chatRoomId = ""
                )
                return giftWrapPayload.aggregatedParticipantsPublicKey()?.let {
                    giftWrapPayload.copy(
                        chatRoomId = it
                    )
                }
            }

        }

        return null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as GiftWrapSeal

        if (kind != other.kind) return false
        if (id != other.id) return false
        if (publicKey != other.publicKey) return false
        if (!tags.contentDeepEquals(other.tags)) return false
        if (content != other.content) return false
        if (signature != other.signature) return false
        if (giftWrapMessageId != other.giftWrapMessageId) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (savedAt != other.savedAt) return false
        if (viewedAt != other.viewedAt) return false
        if (deletedAt != other.deletedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = kind
        result = 31 * result + id.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + tags.contentDeepHashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + signature.hashCode()
        result = 31 * result + (giftWrapMessageId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + savedAt.hashCode()
        result = 31 * result + (viewedAt?.hashCode() ?: 0)
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        return result
    }
}