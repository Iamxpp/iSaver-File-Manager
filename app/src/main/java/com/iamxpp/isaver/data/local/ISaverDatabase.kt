package com.iamxpp.isaver.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CustomLocationEntity::class, RecentItemEntity::class, OperationTaskEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class ISaverDatabase : RoomDatabase() {
    abstract fun customLocationDao(): CustomLocationDao
    abstract fun recentItemDao(): RecentItemDao
    abstract fun operationTaskDao(): OperationTaskDao

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

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `operation_tasks` (
                        `id` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `totalItems` INTEGER NOT NULL,
                        `completedItems` INTEGER NOT NULL,
                        `failedItems` INTEGER NOT NULL,
                        `recoveryPolicy` TEXT NOT NULL,
                        `message` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_operation_tasks_updatedAt`
                    ON `operation_tasks` (`updatedAt`)
                    """.trimIndent(),
                )
            }
        }
    }
}
