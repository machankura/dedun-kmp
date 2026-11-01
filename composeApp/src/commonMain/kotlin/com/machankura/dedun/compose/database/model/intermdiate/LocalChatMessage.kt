package com.machankura.dedun.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalChatMessage(
    @Embedded val chatMessage: com.machankura.dedun.compose.database.model.ChatMessage,

    @Relation(
        parentColumns = ["senderPublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.machankura.dedun.compose.database.model.Profile?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["chatMessageId"]
    )
    val chatMessageBroadcastNostrEventReceiptRelation: com.machankura.dedun.compose.database.model.ChatMessageBroadcastNostrEventReceiptRelation? = null,


    @Relation(
        parentColumns = ["id"],
        entityColumns = ["chatMessageId"]
    )
    val chatMessageBroadcastNostrEventRequestRelation: com.machankura.dedun.compose.database.model.ChatMessageBroadcastNostrEventRequestRelation? = null,


    @Relation(
        parentColumns = ["id"],
        entityColumns = ["chatMessageId"]
    )
    val chatMessageNostrEventRelation: com.machankura.dedun.compose.database.model.ChatMessageNostrEventRelation? = null,
)
