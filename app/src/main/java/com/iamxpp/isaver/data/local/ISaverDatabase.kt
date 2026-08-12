package com.iamxpp.isaver.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CustomLocationEntity::class, RecentItemEntity::class, OperationTaskEntity::class, TrashItemEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class ISaverDatabase : RoomDatabase() {
    abstract fun customLocationDao(): CustomLocationDao
    abstract fun recentItemDao(): RecentItemDao
    abstract fun operationTaskDao(): OperationTaskDao
    abstract fun trashItemDao(): TrashItemDao

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

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trash_items` (
                        `id` TEXT NOT NULL,
                        `originalPath` TEXT NOT NULL,
                        `originalParent` TEXT NOT NULL,
                        `originalName` TEXT NOT NULL,
                        `trashedPath` TEXT NOT NULL,
                        `trashedName` TEXT NOT NULL,
                        `entryType` TEXT NOT NULL,
                        `sizeBytes` INTEGER,
                        `device` INTEGER,
                        `inode` INTEGER,
                        `state` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trash_items_deletedAt` ON `trash_items` (`deletedAt`)",
                )
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `operation_tasks` ADD COLUMN `totalBytes` INTEGER")
                database.execSQL(
                    "ALTER TABLE `operation_tasks` ADD COLUMN `completedBytes` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
