package com.isaver.filemanager.ui.files

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.RootPath
import org.junit.Assert.assertEquals
import org.junit.Test

class FileEntrySorterTest {
    @Test
    fun `presentation models expose the documented choices`() {
        assertEquals(listOf(HomeTab.RECENT, HomeTab.VIEWS, HomeTab.BROWSE), HomeTab.entries)
        assertEquals(listOf(DisplayMode.LIST, DisplayMode.GRID), DisplayMode.entries)
        assertEquals(
            listOf(SortField.DISPLAY_NAME, SortField.TYPE, SortField.MODIFIED_AT, SortField.SIZE),
            SortField.entries,
        )
        assertEquals(listOf(SortDirection.ASCENDING, SortDirection.DESCENDING), SortDirection.entries)
    }

    @Test
    fun `display name sorting is natural and keeps directories first`() {
        val entries = listOf(
            entry("file10", EntryType.FILE),
            entry("dir10", EntryType.DIRECTORY),
            entry("file2", EntryType.FILE),
            entry("Dir2", EntryType.DIRECTORY),
            entry("file1", EntryType.FILE),
        )

        assertNames(
            listOf("Dir2", "dir10", "file1", "file2", "file10"),
            FileEntrySorter.sort(entries, SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING)),
        )
    }

    @Test
    fun `descending display name reverses within groups but keeps directories first`() {
        val entries = listOf(
            entry("file2", EntryType.FILE),
            entry("dir2", EntryType.DIRECTORY),
            entry("file10", EntryType.FILE),
            entry("dir10", EntryType.DIRECTORY),
        )

        assertNames(
            listOf("dir10", "dir2", "file10", "file2"),
            FileEntrySorter.sort(entries, SortSpec(SortField.DISPLAY_NAME, SortDirection.DESCENDING)),
        )
    }

    @Test
    fun `natural display name sorting preserves zero padded equality and unicode case folding`() {
        val entries = listOf(
            entry("item001", EntryType.FILE),
            entry("文件10", EntryType.FILE),
            entry("ITEM1", EntryType.FILE),
            entry("文件2", EntryType.FILE),
            entry("item0002", EntryType.FILE),
        )

        assertNames(
            listOf("item001", "ITEM1", "item0002", "文件2", "文件10"),
            FileEntrySorter.sort(entries, SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING)),
        )
    }

    @Test
    fun `type sorting uses file extension and is stable for equal kinds`() {
        val entries = listOf(
            entry("second.txt", EntryType.FILE),
            entry("folder", EntryType.DIRECTORY),
            entry("photo.jpg", EntryType.FILE),
            entry("first.txt", EntryType.FILE),
            entry("socket", EntryType.OTHER),
        )

        assertNames(
            listOf("folder", "photo.jpg", "second.txt", "first.txt", "socket"),
            FileEntrySorter.sort(entries, SortSpec(SortField.TYPE, SortDirection.ASCENDING)),
        )
        assertNames(
            listOf("folder", "socket", "second.txt", "first.txt", "photo.jpg"),
            FileEntrySorter.sort(entries, SortSpec(SortField.TYPE, SortDirection.DESCENDING)),
        )
    }

    @Test
    fun `modified date sorting keeps null values stable`() {
        val entries = listOf(
            entry("unknown-a", modifiedAt = null),
            entry("new", modifiedAt = 20),
            entry("folder", EntryType.DIRECTORY, modifiedAt = 30),
            entry("unknown-b", modifiedAt = null),
            entry("old", modifiedAt = 10),
        )

        assertNames(
            listOf("folder", "unknown-a", "unknown-b", "old", "new"),
            FileEntrySorter.sort(entries, SortSpec(SortField.MODIFIED_AT, SortDirection.ASCENDING)),
        )
        assertNames(
            listOf("folder", "new", "old", "unknown-a", "unknown-b"),
            FileEntrySorter.sort(entries, SortSpec(SortField.MODIFIED_AT, SortDirection.DESCENDING)),
        )
    }

    @Test
    fun `size sorting keeps directories first and unknown sizes stable`() {
        val entries = listOf(
            entry("large", size = 100),
            entry("unknown-a", size = null),
            entry("dir", EntryType.DIRECTORY, size = null),
            entry("small", size = 10),
            entry("unknown-b", size = null),
        )

        assertNames(
            listOf("dir", "unknown-a", "unknown-b", "small", "large"),
            FileEntrySorter.sort(entries, SortSpec(SortField.SIZE, SortDirection.ASCENDING)),
        )
        assertNames(
            listOf("dir", "large", "small", "unknown-a", "unknown-b"),
            FileEntrySorter.sort(entries, SortSpec(SortField.SIZE, SortDirection.DESCENDING)),
        )
    }

    private fun entry(
        name: String,
        type: EntryType = EntryType.FILE,
        size: Long? = null,
        modifiedAt: Long? = null,
    ) = DirectoryEntry(
        path = RootPath.parse("/test/$name").getOrThrow(),
        name = name,
        type = type,
        sizeBytes = size,
        modifiedAtEpochSeconds = modifiedAt,
        readable = true,
        writable = false,
        symbolicLink = false,
    )

    private fun assertNames(expected: List<String>, actual: List<DirectoryEntry>) {
        assertEquals(expected, actual.map(DirectoryEntry::name))
    }
}
