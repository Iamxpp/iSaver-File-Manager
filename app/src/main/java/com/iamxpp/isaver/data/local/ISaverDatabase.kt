package com.iamxpp.isaver.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CustomLocationEntity::class, RecentItemEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ISaverDatabase : RoomDatabase() {
    abstract fun customLocationDao(): CustomLocationDao
    abstract fun recentItemDao(): RecentItemDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recent_items` (
                        `absolutePath` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `note` TEXT,
                        `itemType` TEXT NOT NULL,
                        `activity` TEXT NOT NULL,
                        `lastActivityAt` INTEGER NOT NULL,
                        `available` INTEGER NOT NULL,
                        PRIMARY KEY(`absolutePath`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_recent_items_lastActivityAt`
                    ON `recent_items` (`lastActivityAt`)
                    """.trimIndent(),
                )
            }
        }
    }
}
