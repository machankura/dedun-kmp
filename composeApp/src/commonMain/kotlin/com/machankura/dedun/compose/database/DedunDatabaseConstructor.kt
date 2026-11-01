package com.machankura.dedun.compose.database

import androidx.room3.RoomDatabaseConstructor

@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object DedunDatabaseConstructor: RoomDatabaseConstructor<DedunDatabase> {

    override fun initialize(): DedunDatabase
}