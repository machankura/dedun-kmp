package com.machankura.dedun.compose.managers

class AuxDatabaseManager(
    dedunGlobal: com.machankura.dedun.compose.DedunGlobal
) {
    val auxDatabase by lazy {
        com.machankura.dedun.compose.database.builder.getRoomDatabase(
            com.machankura.dedun.compose.database.builder.PlatformDatabaseBuilder.getDatabaseBuilder(
                dedunGlobal.platformContext
            )
        )
    }

    /**
     * Dynamic data like user feeds and the likes will be in a in-memory db.
     *
     * We may also decide to just use the normal DB and manage what's saved better...
     */
    val inMemoryAuxDatabase by lazy {
        com.machankura.dedun.compose.database.builder.getRoomDatabase(
            com.machankura.dedun.compose.database.builder.PlatformDatabaseBuilder.getInMemoryDatabaseBuilder(
                dedunGlobal.platformContext
            )
        )
    }
}