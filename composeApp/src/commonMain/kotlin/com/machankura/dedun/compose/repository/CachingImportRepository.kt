package com.machankura.dedun.compose.repository

/**
 * As seen in Primal
 *
 * https://github.com/PrimalHQ/primal-android-app/blob/a7e32e2203555d27e60a749238175cd1ee0fa2af/domain/primal/src/commonMain/kotlin/net/primal/domain/global/CachingImportRepository.kt
 *
 */
interface CachingImportRepository {
    suspend fun cacheNostrEvents(vararg events: com.machankura.dedun.compose.database.model.NostrEvent)
    suspend fun cacheNostrEvents(events: List<com.machankura.dedun.compose.database.model.NostrEvent>)

    suspend fun importEvents(events: List<com.machankura.dedun.compose.database.model.NostrEvent>): Boolean
    suspend fun broadcastEvents(events: List<com.machankura.dedun.compose.database.model.NostrEvent>, relays: List<String>): Result<List<com.machankura.dedun.compose.network.relays.broadcast.BroadcastEventResponse>>

    companion object {
        val NO_OP_CACHING_IMPORT_REPOSITORY = object : CachingImportRepository {
            override suspend fun cacheNostrEvents(vararg events: com.machankura.dedun.compose.database.model.NostrEvent) {
            }

            override suspend fun cacheNostrEvents(events: List<com.machankura.dedun.compose.database.model.NostrEvent>) {

            }

            override suspend fun importEvents(events: List<com.machankura.dedun.compose.database.model.NostrEvent>): Boolean {
                return true
            }

            override suspend fun broadcastEvents(
                events: List<com.machankura.dedun.compose.database.model.NostrEvent>,
                relays: List<String>
            ): Result<List<com.machankura.dedun.compose.network.relays.broadcast.BroadcastEventResponse>> {
                return Result.success(
                    emptyList()
                )
            }

        }
    }
}
