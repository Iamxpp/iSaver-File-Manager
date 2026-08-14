package com.isaver.filemanager.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePathPolicyTest {
    @Test
    fun `normalizes safe relative unicode and multipart names`() {
        assertEquals("目录/报告.tar.gz", ArchivePathPolicy.normalizeRelative("目录\\报告.tar.gz").getOrThrow())
        assertEquals("a/b.txt", ArchivePathPolicy.normalizeRelative("a//b.txt").getOrThrow())
        assertEquals("file", ArchivePathPolicy.normalizeRelative("./file").getOrThrow())
    }

    @Test
    fun `rejects absolute drive traversal empty and nul names`() {
        listOf(
            "",
            "/etc/passwd",
            "\\absolute\\file",
            "C:\\Windows\\system32",
            "a/../secret.txt",
            "../secret.txt",
            "a/./../secret.txt",
            "bad\u0000name",
        ).forEach { raw ->
            assertTrue("expected rejection for [$raw]", ArchivePathPolicy.normalizeRelative(raw).isFailure)
        }
    }

    @Test
    fun `duplicate normalized entries are rejected`() {
        val names = ArchiveEntryNameSet()
        assertEquals("a/b.txt", names.add("a/b.txt").getOrThrow())
        assertTrue(names.add("a\\b.txt").isFailure)
    }

    @Test
    fun `symbolic links are never accepted as archive sources`() {
        assertTrue(ArchivePathPolicy.rejectSymbolicLink(true).isFailure)
        assertTrue(ArchivePathPolicy.rejectSymbolicLink(false).isSuccess)
    }

    @Test
    fun `limits reject entry count bytes and compression ratio`() {
        val limits = ArchiveLimits(maxEntries = 2, maxEntryBytes = 10, maxExpandedBytes = 15, maxCompressionRatio = 3.0)
        assertTrue(limits.checkEntry(1, 10, 5).isSuccess)
        assertTrue(limits.checkEntry(3, 1, 1).isFailure)
        assertTrue(limits.checkEntry(2, 11, 1).isFailure)
        assertTrue(limits.checkEntry(2, 10, 16).isFailure)
        assertTrue(limits.checkEntry(2, 10, 10, compressedBytes = 2).isFailure)
        assertFalse(limits.checkEntry(2, 10, 10, compressedBytes = 4).isFailure)
    }
}
