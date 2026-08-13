package com.iamxpp.isaver.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CustomLocationEntity::class, RecentItemEntity::class, OperationTaskEntity::class,
        TrashItemEntity::class, BookmarkEntity::class, VirtualViewNodeEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class ISaverDatabase : RoomDatabase() {
    abstract fun customLocationDao(): CustomLocationDao
    abstract fun recentItemDao(): RecentItemDao
    abstract fun operationTaskDao(): OperationTaskDao
    abstract fun trashItemDao(): TrashItemDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun virtualViewNodeDao(): VirtualViewNodeDao

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

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bookmarks` (
                        `absolutePath` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`absolutePath`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `entryType` TEXT NOT NULL DEFAULT 'DIRECTORY'")
                database.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `device` INTEGER")
                database.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `inode` INTEGER")
                database.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `available` INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `colorKey` TEXT")
                database.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `groupName` TEXT")
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `virtual_view_nodes` (
                        `id` TEXT NOT NULL,
                        `parentId` TEXT,
                        `nodeType` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `targetPath` TEXT,
                        `entryType` TEXT,
                        `device` INTEGER,
                        `inode` INTEGER,
                        `available` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_virtual_view_nodes_parentId_sortOrder`
                    ON `virtual_view_nodes` (`parentId`, `sortOrder`)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_virtual_view_nodes_parentId_targetPath_entryType`
                    ON `virtual_view_nodes` (`parentId`, `targetPath`, `entryType`)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT OR IGNORE INTO virtual_view_nodes
                        (id, parentId, nodeType, displayName, targetPath, entryType, device, inode,
                         available, sortOrder, createdAt, updatedAt)
                    VALUES
                        ('$MIGRATED_UNGROUPED_ID', NULL, 'VIRTUAL_FOLDER', '未分组', NULL, NULL, NULL, NULL,
                         1, 0, 0, 0)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT OR IGNORE INTO virtual_view_nodes
                        (id, parentId, nodeType, displayName, targetPath, entryType, device, inode,
                         available, sortOrder, createdAt, updatedAt)
                    SELECT
                        '$MIGRATED_GROUP_PREFIX' || groupName,
                        NULL,
                        'VIRTUAL_FOLDER',
                        groupName,
                        NULL, NULL, NULL, NULL,
                        1,
                        ROW_NUMBER() OVER (ORDER BY groupName),
                        MIN(createdAt),
                        MAX(createdAt)
                    FROM bookmarks
                    WHERE groupName IS NOT NULL AND TRIM(groupName) <> ''
                    GROUP BY groupName
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT OR IGNORE INTO virtual_view_nodes
                        (id, parentId, nodeType, displayName, targetPath, entryType, device, inode,
                         available, sortOrder, createdAt, updatedAt)
                    SELECT
                        'migration.custom:' || id,
                        '$MIGRATED_UNGROUPED_ID',
                        'REAL_REFERENCE',
                        displayName,
                        absolutePath,
                        'DIRECTORY',
                        NULL, NULL,
                        1,
                        sortOrder,
                        createdAt,
                        updatedAt
                    FROM custom_locations
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO virtual_view_nodes
                        (id, parentId, nodeType, displayName, targetPath, entryType, device, inode,
                         available, sortOrder, createdAt, updatedAt)
                    SELECT
                        'migration.bookmark:' || absolutePath,
                        CASE
                            WHEN groupName IS NULL OR TRIM(groupName) = '' THEN '$MIGRATED_UNGROUPED_ID'
                            ELSE '$MIGRATED_GROUP_PREFIX' || groupName
                        END,
                        'REAL_REFERENCE',
                        displayName,
                        absolutePath,
                        CASE WHEN entryType = 'FILE' THEN 'FILE' ELSE 'DIRECTORY' END,
                        device,
                        inode,
                        available,
                        ROW_NUMBER() OVER (PARTITION BY groupName ORDER BY createdAt, absolutePath),
                        createdAt,
                        createdAt
                    FROM bookmarks
                    WHERE 1
                    ON CONFLICT(parentId, targetPath, entryType) DO UPDATE SET
                        displayName = CASE
                            WHEN excluded.updatedAt >= virtual_view_nodes.updatedAt
                            THEN excluded.displayName
                            ELSE virtual_view_nodes.displayName
                        END,
                        device = excluded.device,
                        inode = excluded.inode,
                        available = excluded.available,
                        updatedAt = MAX(virtual_view_nodes.updatedAt, excluded.updatedAt)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    DELETE FROM virtual_view_nodes
                    WHERE id = '$MIGRATED_UNGROUPED_ID'
                      AND NOT EXISTS (
                          SELECT 1 FROM virtual_view_nodes AS child
                          WHERE child.parentId = '$MIGRATED_UNGROUPED_ID'
                      )
                    """.trimIndent(),
                )
            }
        }

        private const val MIGRATED_UNGROUPED_ID = "migration.virtual.ungrouped"
        private const val MIGRATED_GROUP_PREFIX = "migration.virtual.group:"
    }
}
