package com.machankura.dedun.compose.database.builder

import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.machankura.dedun.compose.database.migrations.MIGRATION_3_4
import com.machankura.dedun.compose.database.migrations.MIGRATION_9_10
import com.machankura.dedun.compose.database.migrations.MIGRATION_13_14
import com.machankura.dedun.compose.database.migrations.MIGRATION_14_15
import com.machankura.dedun.compose.database.migrations.MIGRATION_26_27
import com.machankura.dedun.compose.database.migrations.MIGRATION_32_33
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

expect object PlatformDatabaseBuilder {
    fun getDatabaseBuilder(platformContext: com.machankura.dedun.compose.PlatformContext): RoomDatabase.Builder<com.machankura.dedun.compose.database.DedunDatabase>

    fun getInMemoryDatabaseBuilder(platform: com.machankura.dedun.compose.PlatformContext): RoomDatabase.Builder<com.machankura.dedun.compose.database.DedunDatabase>
}

fun getRoomDatabase(
    builder: RoomDatabase.Builder<com.machankura.dedun.compose.database.DedunDatabase>
): com.machankura.dedun.compose.database.DedunDatabase {
    return builder
        // Everything else Room generates itself. These six move data rather than
        // only changing shape, which an AutoMigration cannot express: 3->4 rewrites
        // chat rows, 9->10 copies a session's per-event columns onto the items
        // table before dropping them, 13->14 renames a column and rewrites the
        // chat rows that named it the old way, 14->15 adds a column and deletes
        // the chat rows written for messages from before this device joined, and
        // 26->27 drops the document tables and deletes the chat rows nothing
        // renders any more, and 32->33 deletes the raw-JSON rows the Lightning
        // wallet's own messages left.
        .addMigrations(MIGRATION_3_4, MIGRATION_9_10, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_26_27, MIGRATION_32_33)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}