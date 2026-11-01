package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Transaction
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.ChatMessageBroadcastNostrEventRequestRelation
import com.machankura.dedun.compose.database.model.ChatMessageNostrEventRelation
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GiftWrapMessage
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.GiftWrapSeal
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.Relays
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlin.time.Instant


@Dao
abstract class NostrNip17Dao(
    private val database: com.machankura.dedun.compose.database.DedunDatabase
) {
    val logger = Logger.withTag(TAG)

    /**
     * Stands up a NIP-17 group locally: no MLS group, no key packages, no invites.
     * Membership under NIP-17 *is* the p-tag set on each message, so all that has to
     * exist up front is the room and its participants — the first message carries the
     * membership to everyone else.
     *
     * The id is [ChatRoom.deriveChatRoomId] over the members, the same aggregate the
     * inbound path derives from an arriving gift wrap. That makes it idempotent: two
     * people building the same group land on the same room rather than two.
     */
    @Transaction
    open suspend fun createNip17ChatRoom(
        userPublicKey: HexKey,
        participantPublicKeys: List<HexKey>,
        subject: String? = null,
        description: String? = null,
        relayHint: String? = Relays.DefaultDMRelayList.first().url
    ): LocalChatRoom? {
        val memberPublicKeys = (participantPublicKeys + userPublicKey).toSet()
        val chatRoomId = ChatRoom.deriveChatRoomId(memberPublicKeys)

        logger.d("createNip17ChatRoom: $chatRoomId for ${memberPublicKeys.size} member(s)")

        database.chatRoomDao().findChatRoomById(chatRoomId)?.let { existingChatRoom ->
            logger.i("Chat room $chatRoomId already exists; reusing it")
            return existingChatRoom
        }

        val chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = userPublicKey,
            subject = subject,
            description = description,
            // No MLS state is what marks this a NIP-17 room: `sendChatMessage` reads
            // exactly this to decide between a group event and gift wraps.
            mlsGroupState = null
        )
        database.chatRoomDao().upsert(chatRoom)

        database.participantDao().upsert(
            memberPublicKeys.map { participantPublicKey ->
                Participant(
                    participantPublicKey = participantPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint
                )
            }
        )

        return database.chatRoomDao().findChatRoomById(chatRoomId)
    }

    @Transaction
    open suspend fun getOrCreateChatRoom(
        chatRoomId: HexKey,
        activeUserPublicKey: HexKey,
        relayHint: String?,
        defaultSubject: String? = null,
        description: String? = null,
        mlsGroupState: String? = null
    ): LocalChatRoom? {
        logger.d("getOrCreateChatRoom: $chatRoomId")


        val hexKeys = setOf(
            activeUserPublicKey,
//            publicKey
        )

        val localChatRoom = database.chatRoomDao().findChatRoomById(chatRoomId)

        if (localChatRoom != null) {
            // TODO: might want to update the mlsGroupState?
            return localChatRoom
        } else {
            val profiles = database.profileDao().getProfileByPublicKeys(hexKeys.toList())

            if (profiles.isNotEmpty()) {
                // Create new chatRoom
                val chatRoom = ChatRoom(
                    id = chatRoomId,
                    userPublicKey = activeUserPublicKey,
                    subject = defaultSubject,
                    mlsGroupState = mlsGroupState,
                    description = description
                )
                database.chatRoomDao().upsert(chatRoom)

                database.participantDao().upsert(
                    profiles.map {
                        Participant(
                            participantPublicKey = it.publicKey,
                            chatRoomId = chatRoomId,
                            relayHint = relayHint
                        )
                    }
                )

                return LocalChatRoom(
                    chatRoom = chatRoom
                )
            }
        }

        return null
    }

    @Transaction
    open suspend fun persistAndBroadcastGiftWrap(
        giftWrapPayload: GiftWrapPayload,
        sealedRumorEvent: SealedRumorEvent,
        giftWrapEvent: GiftWrapEvent,
        receiverPublicKey: PTag
    ) {
        val nostrEvent = NostrEvent(
            id = giftWrapEvent.id,
            content = giftWrapEvent.content,
            createdAt = Instant.fromEpochSeconds(giftWrapEvent.createdAt),
            sig = giftWrapEvent.sig,
            pubKey = giftWrapEvent.pubKey,
            tags = giftWrapEvent.tags,
            kind = giftWrapEvent.kind
        )
        logger.d("NostrEvent: $nostrEvent")

        database.nostrEventDao().insert(nostrEvent)

        val chatMessage = database.chatMessageDao().getChatMessagesByGiftWrapPayloadId(giftWrapPayload.id)

        logger.d("chatMessage: $chatMessage")
        chatMessage?.let {
            database.chatMessageNostrEventRelationDao().upsert(
                ChatMessageNostrEventRelation(
                    chatMessageId = it.id,
                    nostrEventId = nostrEvent.id
                )
            )
        }

        // Get ChatMessageRelayListEvent.KIND for receiverPublicKey.pubkey
        val chatMessageRelayListEvents = database.nostrEventDao().getAuthoredNostrEvents(
            kinds = arrayOf(ChatMessageRelayListEvent.KIND),
            authors = arrayOf(
                receiverPublicKey.pubKey
            ),
            since = GENESIS_AT,
            limit = 5
        )
        logger.d("Found messageRelayLists: $chatMessageRelayListEvents")

//        chatMessageRelayListEvents.firstOrNull()?.let { chatMessageRelayListNostrEvent ->
//            val chatMessageRelayListEvent = ChatMessageRelayListEvent(
//                id = chatMessageRelayListNostrEvent.id,
//                pubKey = chatMessageRelayListNostrEvent.pubKey,
//                tags = chatMessageRelayListNostrEvent.tags,
//                content = chatMessageRelayListNostrEvent.content,
//                sig = chatMessageRelayListNostrEvent.sig,
//                createdAt = chatMessageRelayListNostrEvent.createdAt.epochSeconds,
//            )
//
//            logger.d("Broadcast to ${chatMessageRelayListEvent.relays()}")
//
//            // Only broadcasting to the first 3 relays...
//            chatMessageRelayListEvent.relays().take(3).forEach { relayUrl ->
//                logger.d("Submit BroadcastNostrEventRequest to $relayUrl")
//                val broadcastNostrEventRequestIds = database.broadcastNostrEventRequestDao().insert(
//                    listOf(
//                        BroadcastNostrEventRequest(
//                            nostrEventId = nostrEvent.id,
//                            relayURL = relayUrl.url
//                        )
//                    )
//                )
//
//                chatMessage?.let {
//                    broadcastNostrEventRequestIds.forEach {
//                        database.chatMessageBroadcastNostrEventRequestRelationDao().upsert(
//                            ChatMessageBroadcastNostrEventRequestRelation(
//                                chatMessageId = chatMessage.id,
//                                broadcastNostrEventRequestId = it
//                            )
//                        )
//                    }
//                }
//
//            }
//        }

        Relays.DefaultDMRelayList.forEach { relayUrl ->
            logger.d("Submit BroadcastNostrEventRequest to $relayUrl")
            val broadcastNostrEventRequestIds = database.broadcastNostrEventRequestDao().insert(
                listOf(
                    BroadcastNostrEventRequest(
                        nostrEventId = nostrEvent.id,
                        relayURL = relayUrl.url
                    )
                )
            )

            chatMessage?.let {
                broadcastNostrEventRequestIds.forEach {
                    database.chatMessageBroadcastNostrEventRequestRelationDao().upsert(
                        ChatMessageBroadcastNostrEventRequestRelation(
                            chatMessageId = chatMessage.id,
                            broadcastNostrEventRequestId = it
                        )
                    )
                }
            }
        }


        logger.d("Insert gift wrap in DB")
        database.giftWrapMessageDao().upsert(
            GiftWrapMessage(
                id = giftWrapEvent.id,
                nostrEventId = nostrEvent.id,
                content = giftWrapEvent.content,
                createdAt = Instant.fromEpochSeconds(giftWrapEvent.createdAt),
                signature = giftWrapEvent.sig,
                publicKey = giftWrapEvent.pubKey,
                kind = giftWrapEvent.kind,
                receiverPublicKey = receiverPublicKey.pubKey,
                receiverRelayHit = receiverPublicKey.relayHint?.url
            )
        )

        database.giftWrapSealDao().upsert(
            GiftWrapSeal(
                id = sealedRumorEvent.id,
                publicKey = sealedRumorEvent.pubKey,
                content = sealedRumorEvent.content,
                kind = sealedRumorEvent.kind,
                tags = sealedRumorEvent.tags,
                signature = sealedRumorEvent.sig,
                giftWrapMessageId = giftWrapEvent.id
            )
        )
        logger.d("persistAndBroadcastGiftWrap done")

        if (giftWrapPayload.giftWrapSealId == null) {
            // Only update the giftWrapPayload.giftWrapSealId on the first seal...
            database.giftWrapPayloadDao().upsert(
                giftWrapPayload.copy(
                    giftWrapSealId = sealedRumorEvent.id
                )
            )
            logger.d("GiftWrap now sealed: ${giftWrapPayload.id}")
        }

    }

    companion object {
        const val TAG = "NostrNip17Dao"
    }
}