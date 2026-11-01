package com.machankura.dedun.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface PostDao {
    @Query("SELECT * FROM Post")
    suspend fun  getAllPosts(): List<com.machankura.dedun.compose.database.model.intermdiate.LocalPost>

    @Query("SELECT * FROM Post WHERE id = :id")
    suspend fun getPostById(id: String): com.machankura.dedun.compose.database.model.Post?

    @Insert
    suspend fun insert(post: com.machankura.dedun.compose.database.model.Post)

    @Upsert
    suspend fun upsert(post: com.machankura.dedun.compose.database.model.Post)
}