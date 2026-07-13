package com.iamxpp.isaver.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ISaverDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ISaverDatabase::class.java,
    )

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

    private companion object {
        const val TEST_DATABASE = "isaver-migration-test"
    }
}
