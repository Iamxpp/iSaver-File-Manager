package com.iamxpp.isaver.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveTreeTest {
    @Test
    fun `root children synthesize missing folders and keep only direct entries`() {
        val listing = ArchiveListing(
            ArchiveFormat.ZIP,
            listOf(
                ArchiveEntry("docs/reports/annual.pdf", false, 12L),
                ArchiveEntry("readme.txt", false, 3L),
            ),
        )

        assertEquals(
            listOf(
                ArchiveNode("docs", "docs", true, null, null),
                ArchiveNode("readme.txt", "readme.txt", false, 3L, null),
            ),
            listing.children(""),
        )
        assertEquals(
            listOf(ArchiveNode("reports", "docs/reports", true, null, null)),
            listing.children("docs"),
        )
    }

    @Test
    fun `children merge explicit folders and use directory first natural order`() {
        val listing = ArchiveListing(
            ArchiveFormat.TAR,
            listOf(
                ArchiveEntry("folder10/", true, 0L),
                ArchiveEntry("file10.txt", false, 10L, 7L),
                ArchiveEntry("Folder2/item.txt", false, 1L),
                ArchiveEntry("file2.txt", false, 2L, 1L),
            ),
        )

        assertEquals(
            listOf("Folder2", "folder10", "file2.txt", "file10.txt"),
            listing.children("").map(ArchiveNode::name),
        )
        assertEquals(1, listing.children("").count { it.path.equals("folder10", true) })
    }

    @Test
    fun `empty archive has no children and compound extension is stripped`() {
        assertTrue(ArchiveListing(ArchiveFormat.SEVEN_Z, emptyList()).children("").isEmpty())
        assertEquals("backup", archiveDisplayName("backup.tar.gz"))
        assertEquals("backup", archiveDisplayName("backup.tgz"))
        assertEquals("backup", archiveDisplayName("backup.7z"))
        assertEquals(".archive", archiveDisplayName(".archive"))
    }
}
