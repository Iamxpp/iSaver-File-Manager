package com.isaver.filemanager.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentItemDaoRoomTest {
    private lateinit var database: ISaverDatabase
    private lateinit var dao: RecentItemDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ISaverDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.recentItemDao()
    }

    @After
    fun close() = database.close()

    @Test
    fun `flow is stably newest first and upsert updates a canonical path`() = runTest {
        dao.upsertAndTrim(entity("/b", "旧名称", 10), limit = 100)
        dao.upsertAndTrim(entity("/a", "A", 10), limit = 100)
        dao.upsertAndTrim(entity("/c", "C", 20), limit = 100)
        dao.upsertAndTrim(entity("/b", "新名称", 30), limit = 100)

        val rows = dao.observeRecent().first()

        assertEquals(listOf("/b", "/c", "/a"), rows.map { it.absolutePath })
        assertEquals("新名称", rows.first().displayName)
        assertEquals(3, rows.size)
    }

    @Test
    fun `upsert and trim enforces the requested limit`() = runTest {
        repeat(5) { index ->
            dao.upsertAndTrim(entity("/item/$index", "item-$index", index.toLong()), limit = 3)
        }

        assertEquals(listOf("/item/4", "/item/3", "/item/2"), dao.observeRecent().first().map { it.absolutePath })
    }

    @Test
    fun `mark unavailable retains the recent row`() = runTest {
        dao.upsertAndTrim(entity("/missing", "失效目录", 10), limit = 100)

        assertEquals(1, dao.markAvailability("/missing", available = false))
        assertEquals(0, dao.markAvailability("/missing", available = false))

        val row = dao.observeRecent().first().single()
        assertEquals("/missing", row.absolutePath)
        assertEquals("失效目录", row.displayName)
        assertEquals(false, row.available)
    }

    @Test
    fun `upsert and trim rolls back when cleanup fails`() = runTest {
        dao.upsertAndTrim(entity("/old-a", "A", 1), limit = 100)
        dao.upsertAndTrim(entity("/old-b", "B", 2), limit = 100)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_recent_trim
            BEFORE DELETE ON recent_items
            BEGIN SELECT RAISE(ABORT, 'fail'); END
            """.trimIndent(),
        )

        try {
            dao.upsertAndTrim(entity("/new", "new", 3), limit = 2)
            fail("Expected trim failure")
        } catch (_: Exception) {
        }

        assertEquals(listOf("/old-b", "/old-a"), dao.observeRecent().first().map { it.absolutePath })
    }

    private fun entity(path: String, displayName: String, time: Long) = RecentItemEntity(
        absolutePath = path,
        displayName = displayName,
        note = null,
        itemType = "DIRECTORY",
        activity = "ACCESSED",
        lastActivityAt = time,
        available = true,
    )
}
