package com.machankura.dedun.compose.repository

import kotlinx.coroutines.flow.Flow

interface RelayRepository {
    suspend fun observePublicKeyRelays(publicKey: String): Flow<List<com.machankura.dedun.compose.database.model.Relay>>
}