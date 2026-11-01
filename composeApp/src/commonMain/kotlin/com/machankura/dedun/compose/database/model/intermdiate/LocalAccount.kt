package com.machankura.dedun.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalAccount(
    @Embedded val unsignedNostrEvent: com.machankura.dedun.compose.database.model.UnsignedNostrEvent?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent?,

    @Relation(
        parentColumns = ["pubKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.machankura.dedun.compose.database.model.Profile?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val broadcastNostrEventRequest: com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val broadcastNostrEventReceipt: com.machankura.dedun.compose.database.model.BroadcastNostrEventReceipt?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val synchronizeNostrEventRequests: List<com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest>,

    )
