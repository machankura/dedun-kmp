package com.machankura.dedun.compose.database.repository

class DatabaseSearchRepository(
    private val database: com.machankura.dedun.compose.database.DedunDatabase
): com.machankura.dedun.compose.repository.SearchRepository {

    override suspend fun submitSearch(synchronizeNostrEventRequests: List<com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest>) {
        database.synchronizeNostrEventRequestDao().insert(
            synchronizeNostrEventRequests = synchronizeNostrEventRequests
        )
    }
}