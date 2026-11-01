package com.machankura.dedun.compose.database.model

import androidx.room3.Entity

@Entity(
    primaryKeys = ["publicKey", "type", "url"]
)
data class Relay(
    val publicKey: String,
    val type: String,
    val url: String,
    val read: Boolean,
    val write: Boolean
)
