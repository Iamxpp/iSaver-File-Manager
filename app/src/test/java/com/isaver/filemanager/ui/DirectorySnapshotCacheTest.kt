package com.isaver.filemanager.ui

import com.isaver.filemanager.data.root.DirectorySnapshot
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.ui.files.SortDirection
import com.isaver.filemanager.ui.files.SortField
import com.isaver.filemanager.ui.files.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DirectorySnapshotCacheTest {
    @Test
    fun `keeps sixteen most recently used snapshots`() {
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L })
        val snapshots = (0..16).associateWith(::snapshot)

        (0 until 16).forEach { index ->
            val snapshot = snapshots.getValue(index)
            cache.put(path(index), snapshot, snapshot.entries, presentationKey())
        }
        assertSame(snapshots.getValue(0), cache.get(path(0))?.snapshot)

        cache.put(path(16), snapshots.getValue(16), emptyList(), presentationKey())

        assertSame(snapshots.getValue(0), cache.get(path(0))?.snapshot)
        assertNull(cache.get(path(1)))
        assertSame(snapshots.getValue(16), cache.get(path(16))?.snapshot)
        assertEquals(16, (0..16).count { cache.get(path(it)) != null })
    }

    @Test
    fun `expires snapshots at the two second ttl`() {
        var nowMillis = 10L
        val cache = DirectorySnapshotCache(monotonicNowMillis = { nowMillis })
        val snapshot = snapshot(1)
        cache.put(path(1), snapshot, snapshot.entries, presentationKey())

        nowMillis = 2_009L
        assertSame(snapshot, cache.get(path(1))?.snapshot)

        nowMillis = 2_010L
        assertNull(cache.get(path(1)))
    }

    @Test
    fun `keeps the off main prepared presentation with its snapshot`() {
        val cache = DirectorySnapshotCache(monotonicNowMillis = { 0L })
        val snapshot = snapshot(1)
        val presentedEntry = DirectoryEntry(
            path = RootPath.parse("/cache/1/child").getOrThrow(),
            name = "child",
            type = EntryType.FILE,
            sizeBytes = 1L,
            modifiedAtEpochSeconds = 2L,
            readable = true,
            writable = false,
            symbolicLink = false,
        )

        val presentationKey = presentationKey()
        cache.put(path(1), snapshot, listOf(presentedEntry), presentationKey)

        val cached = cache.get(path(1))!!
        assertSame(snapshot, cached.snapshot)
        assertEquals(listOf(presentedEntry), cached.presentedEntries)
        assertEquals(presentationKey, cached.presentationKey)
    }

    private fun path(index: Int) = RootPath.parse("/cache/$index").getOrThrow()

    private fun snapshot(index: Int) = DirectorySnapshot(
        parentDevice = index.toLong(),
        parentInode = index.toLong(),
        parentReadable = true,
        parentWritable = false,
        entries = emptyList(),
    )

    private fun presentationKey() = DirectoryPresentationKey(
        sortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
        searchQuery = "",
    )
}
