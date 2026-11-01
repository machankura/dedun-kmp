package com.machankura.dedun.compose.database.builder

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.machankura.dedun.compose.PlatformContext
import com.machankura.dedun.compose.database.DedunDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual object PlatformDatabaseBuilder {
    actual fun getDatabaseBuilder(platformContext: PlatformContext): RoomDatabase.Builder<DedunDatabase> {
        val dbFilePath = documentDirectory() + "/aux.db"
        return Room.databaseBuilder<DedunDatabase>(
            name = dbFilePath,
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun documentDirectory(): String {
        val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
        return requireNotNull(documentDirectory?.path)
    }

    actual fun getInMemoryDatabaseBuilder(platform: PlatformContext): RoomDatabase.Builder<DedunDatabase> {
        return Room.inMemoryDatabaseBuilder()
    }

}