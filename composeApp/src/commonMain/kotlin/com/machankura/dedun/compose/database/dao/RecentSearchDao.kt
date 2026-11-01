package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM RecentSearch WHERE `query` = :query")
    fun observeRecentSearchByQuery(query: String): Flow<com.machankura.dedun.compose.database.model.RecentSearch?>

    @Query("SELECT * FROM RecentSearch ORDER BY createdAt DESC LIMIT :limit ")
    suspend fun getAllRecentSearches(limit: Int = 21): List<com.machankura.dedun.compose.database.model.RecentSearch>

    @Delete
    suspend fun deleteRecentSearch(recentSearch: com.machankura.dedun.compose.database.model.RecentSearch)

    @Upsert
    suspend fun upsert(recentSearch: com.machankura.dedun.compose.database.model.RecentSearch)
}