package com.iamxpp.isaver.recent

import com.iamxpp.isaver.data.local.RecentItemDao
import com.iamxpp.isaver.data.local.RecentItemEntity
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentRepositoryTest {
    @Test
    fun `same canonical path is updated without duplication and newest activity is first`() = runTest {
        val dao = FakeRecentItemDao()
        var now = 10L
        val repository = RecentRepository(dao = dao, clock = { now })

        repository.recordAccess(
            canonicalPath = root("/storage/emulated/0/Documents"),
            displayName = "文档",
            note = "工作目录",
            type = RecentItemType.DIRECTORY,
        )
        now = 20L
        repository.recordAccess(
            canonicalPath = root("/storage/emulated/0/Download"),
            displayName = "下载",
            note = null,
            type = RecentItemType.DIRECTORY,
        )
        now = 30L
        repository.recordSaved(
            canonicalPath = root("/storage/emulated/0/Documents/report.pdf"),
            displayName = "report.pdf",
            note = null,
            type = RecentItemType.FILE,
        )
        now = 40L
        repository.recordAccess(
            canonicalPath = root("/storage/emulated/0/Documents"),
            displayName = "项目文档",
            note = "  新备注  ",
            type = RecentItemType.DIRECTORY,
        )

        val recent = repository.observeRecent().first()

        assertEquals(3, recent.size)
        assertEquals(
            listOf(
                "/storage/emulated/0/Documents",
                "/storage/emulated/0/Documents/report.pdf",
                "/storage/emulated/0/Download",
            ),
            recent.map { it.path.value },
        )
        assertEquals("项目文档", recent.first().displayName)
        assertEquals("新备注", recent.first().note)
        assertEquals(RecentActivity.ACCESSED, recent.first().activity)
        assertTrue(recent.first().available)
    }

    @Test
    fun `repository keeps only the configured maximum recent items`() = runTest {
        val dao = FakeRecentItemDao()
        var now = 0L
        val repository = RecentRepository(dao = dao, clock = { ++now })

        repeat(RecentRepository.MAX_RECENT_ITEMS + 1) { index ->
            repository.recordAccess(
                canonicalPath = root("/recent/$index"),
                displayName = "item-$index",
                note = null,
                type = RecentItemType.DIRECTORY,
            )
        }

        val recent = repository.observeRecent().first()
        assertEquals(RecentRepository.MAX_RECENT_ITEMS, recent.size)
        assertEquals("/recent/${RecentRepository.MAX_RECENT_ITEMS}", recent.first().path.value)
        assertTrue(recent.none { it.path.value == "/recent/0" })
    }

    @Test
    fun `marking an item unavailable retains its metadata and order`() = runTest {
        val dao = FakeRecentItemDao()
        val repository = RecentRepository(dao = dao, clock = { 7L })
        val path = root("/storage/emulated/0/失效目录")
        repository.recordAccess(path, "失效目录", "保留备注", RecentItemType.DIRECTORY)

        assertTrue(repository.markAvailability(path, available = false))

        val item = repository.observeRecent().first().single()
        assertEquals(path, item.path)
        assertEquals("失效目录", item.displayName)
        assertEquals("保留备注", item.note)
        assertEquals(7L, item.lastActivityAt)
        assertTrue(!item.available)
    }

    @Test
    fun `record compressed stores archive activity`() = runTest {
        val dao = FakeRecentItemDao()
        val repository = RecentRepository(dao = dao, clock = { 11L })

        repository.recordCompressed(root("/archives/output.zip"), "output.zip")

        val item = repository.observeRecent().first().single()
        assertEquals(RecentActivity.COMPRESSED, item.activity)
        assertEquals(RecentItemType.ARCHIVE, item.type)
    }

    @Test
    fun `record extracted stores destination directory activity`() = runTest {
        val dao = FakeRecentItemDao()
        val repository = RecentRepository(dao = dao, clock = { 12L })

        repository.recordExtracted(root("/extract/output"), "output")

        val item = repository.observeRecent().first().single()
        assertEquals(RecentActivity.EXTRACTED, item.activity)
        assertEquals(RecentItemType.DIRECTORY, item.type)
    }

    @Test
    fun `plain access does not downgrade a meaningful operation activity`() = runTest {
        val dao = FakeRecentItemDao()
        var now = 12L
        val repository = RecentRepository(dao = dao, clock = { now })
        val output = root("/extract/output")
        repository.recordExtracted(output, "output")

        now = 13L
        repository.recordAccess(output, "output", null, RecentItemType.DIRECTORY)

        val item = repository.observeRecent().first().single()
        assertEquals(RecentActivity.EXTRACTED, item.activity)
        assertEquals(13L, item.lastActivityAt)
    }

    @Test
    fun `corrupt rows are skipped without hiding valid recent items`() = runTest {
        val dao = FakeRecentItemDao()
        dao.seed(
            entity(path = "/valid", type = "DIRECTORY", activity = "ACCESSED", time = 30),
            entity(path = "relative/private", type = "DIRECTORY", activity = "ACCESSED", time = 20),
            entity(path = "/unknown-type", type = "REMOTE_SECRET", activity = "ACCESSED", time = 10),
            entity(path = "/unknown-activity", type = "FILE", activity = "FAILED", time = 5),
        )
        val repository = RecentRepository(dao = dao, clock = { 0L })

        val recent = repository.observeRecent().first()

        assertEquals(listOf("/valid"), recent.map { it.path.value })
    }

    private class FakeRecentItemDao : RecentItemDao() {
        private val rows = linkedMapOf<String, RecentItemEntity>()
        private val flow = MutableStateFlow<List<RecentItemEntity>>(emptyList())

        override fun observeRecent(): Flow<List<RecentItemEntity>> = flow

        fun seed(vararg entities: RecentItemEntity) {
            rows.clear()
            entities.forEach { rows[it.absolutePath] = it }
            flow.value = entities.toList()
        }

        override suspend fun upsert(entity: RecentItemEntity) {
            rows[entity.absolutePath] = entity
        }

        override suspend fun findByPath(path: String): RecentItemEntity? = rows[path]

        override suspend fun deleteBeyondLimit(limit: Int) {
            val kept = rows.values
                .sortedWith(compareByDescending<RecentItemEntity> { it.lastActivityAt }.thenBy { it.absolutePath })
                .take(limit)
            rows.clear()
            kept.forEach { rows[it.absolutePath] = it }
            flow.value = kept
        }

        override suspend fun markAvailability(path: String, available: Boolean): Int {
            val current = rows[path] ?: return 0
            rows[path] = current.copy(available = available)
            flow.value = rows.values
                .sortedWith(compareByDescending<RecentItemEntity> { it.lastActivityAt }.thenBy { it.absolutePath })
            return 1
        }
    }

    private fun entity(path: String, type: String, activity: String, time: Long) = RecentItemEntity(
        absolutePath = path,
        displayName = "item",
        note = null,
        itemType = type,
        activity = activity,
        lastActivityAt = time,
        available = true,
    )

    private fun root(value: String): RootPath = RootPath.parse(value).getOrThrow()
}
