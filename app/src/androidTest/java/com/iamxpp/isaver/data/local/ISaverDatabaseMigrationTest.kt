package com.iamxpp.isaver.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ISaverDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ISaverDatabase::class.java,
    )

    @Before
    fun deletePreviousTestDatabase() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migration1To2PreservesCustomLocationsAndCreatesRecentItems() {
        migrationHelper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO custom_locations
                    (id, displayName, absolutePath, sortOrder, createdAt, updatedAt)
                VALUES
                    ('custom.one', '工作目录', '/storage/emulated/0/Documents', 0, 10, 20)
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            ISaverDatabase.MIGRATION_1_2,
        )

        migrated.query("SELECT displayName, absolutePath FROM custom_locations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("工作目录", cursor.getString(0))
            assertEquals("/storage/emulated/0/Documents", cursor.getString(1))
        }
        migrated.query("SELECT COUNT(*) FROM recent_items").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration2To3CreatesPersistentOperationTasks() {
        migrationHelper.createDatabase(TEST_DATABASE, 2).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            3,
            true,
            ISaverDatabase.MIGRATION_2_3,
        )

        migrated.query("SELECT COUNT(*) FROM operation_tasks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration3To4CreatesTrashJournal() {
        migrationHelper.createDatabase(TEST_DATABASE, 3).close()
        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE, 4, true, ISaverDatabase.MIGRATION_3_4,
        )
        migrated.query("SELECT COUNT(*) FROM trash_items").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration4To5AddsTaskByteProgress() {
        migrationHelper.createDatabase(TEST_DATABASE, 4).close()
        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE, 5, true, ISaverDatabase.MIGRATION_4_5,
        )
        migrated.query("SELECT totalBytes, completedBytes FROM operation_tasks").use { cursor ->
            assertEquals(2, cursor.columnCount)
        }
    }

    @Test
    fun migration5To6CreatesBookmarks() {
        migrationHelper.createDatabase(TEST_DATABASE, 5).close()
        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE, 6, true, ISaverDatabase.MIGRATION_5_6,
        )
        migrated.query("SELECT absolutePath, displayName, createdAt FROM bookmarks").use { cursor ->
            assertEquals(3, cursor.columnCount)
            assertEquals(0, cursor.count)
        }
    }

    @Test
    fun migration6To7AddsFileBookmarkMetadata() {
        migrationHelper.createDatabase(TEST_DATABASE, 6).close()
        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE, 7, true, ISaverDatabase.MIGRATION_6_7,
        )
        migrated.query("SELECT entryType, device, inode, available, colorKey, groupName FROM bookmarks").use { cursor ->
            assertEquals(6, cursor.columnCount)
        }
    }

    @Test
    fun migration7To8UnifiesCustomLocationsAndBookmarks() {
        migrationHelper.createDatabase(TEST_DATABASE, 7).apply {
            execSQL(
                """
                INSERT INTO custom_locations
                    (id, displayName, absolutePath, sortOrder, createdAt, updatedAt)
                VALUES
                    ('custom.docs', '旧自定义名', '/storage/emulated/0/Documents', 0, 10, 20),
                    ('custom.download', '下载', '/storage/emulated/0/Download', 1, 11, 12)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO bookmarks
                    (absolutePath, displayName, createdAt, entryType, device, inode, available, colorKey, groupName)
                VALUES
                    ('/storage/emulated/0/Documents', '工作文档', 30, 'DIRECTORY', 8, 81, 1, 'GREEN', NULL),
                    ('/data/local/tmp/report.txt', '报告', 40, 'FILE', 8, 82, 0, 'RED', '工作')
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            8,
            true,
            ISaverDatabase.MIGRATION_7_8,
        )

        migrated.query(
            """
            SELECT nodeType, displayName, parentId, targetPath, entryType, device, inode, available
            FROM virtual_view_nodes
            ORDER BY nodeType DESC, displayName
            """.trimIndent(),
        ).use { cursor ->
            val rows = buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { index -> cursor.getString(index) })
                }
            }
            assertEquals(5, rows.size)
            assertTrue(rows.any { it[0] == "VIRTUAL_FOLDER" && it[1] == "未分组" && it[2] == null })
            assertTrue(rows.any { it[0] == "VIRTUAL_FOLDER" && it[1] == "工作" && it[2] == null })
            assertTrue(rows.any {
                it[0] == "REAL_REFERENCE" && it[1] == "工作文档" &&
                    it[3] == "/storage/emulated/0/Documents" && it[4] == "DIRECTORY" &&
                    it[5] == "8" && it[6] == "81" && it[7] == "1"
            })
            assertTrue(rows.any {
                it[0] == "REAL_REFERENCE" && it[1] == "报告" &&
                    it[3] == "/data/local/tmp/report.txt" && it[4] == "FILE" && it[7] == "0"
            })
            assertTrue(rows.any {
                it[0] == "REAL_REFERENCE" && it[1] == "下载" &&
                    it[3] == "/storage/emulated/0/Download" && it[4] == "DIRECTORY"
            })
        }
        migrated.query("SELECT COUNT(*) FROM custom_locations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM bookmarks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test
    fun migration7To8KeepsVirtualViewEmptyWithoutLegacyData() {
        val databaseName = "$TEST_DATABASE-empty-${System.nanoTime()}"
        migrationHelper.createDatabase(databaseName, 7).apply {
            query("SELECT COUNT(*) FROM custom_locations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM bookmarks").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            ISaverDatabase.MIGRATION_7_8,
        )

        migrated.query("SELECT id, nodeType, parentId FROM virtual_view_nodes").use { cursor ->
            val rows = buildList {
                while (cursor.moveToNext()) add(listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            }
            assertEquals(rows.toString(), 0, rows.size)
        }
    }

    private companion object {
        const val TEST_DATABASE = "isaver-migration-test"
    }
}
