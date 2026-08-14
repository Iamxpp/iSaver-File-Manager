package com.isaver.filemanager.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputNameDraftTest {
    @Test
    fun `splits source names at the last non edge dot`() {
        val cases = mapOf(
            "report.pdf" to OutputNameDraft("report", "pdf"),
            "archive.tar.gz" to OutputNameDraft("archive.tar", "gz"),
            ".env" to OutputNameDraft(".env", ""),
            "name." to OutputNameDraft("name.", ""),
            "README" to OutputNameDraft("README", ""),
            "中文😀.文档" to OutputNameDraft("中文😀", "文档"),
        )

        cases.forEach { (displayName, expected) ->
            assertEquals(expected, OutputNameDraft.fromDisplayName(displayName))
        }
    }

    @Test
    fun `combines independently edited stem and multipart extension`() {
        assertEquals(
            "archive.tar.gz",
            OutputNameDraft(stem = "archive", extension = "tar.gz")
                .toEntryName()
                .getOrThrow()
                .value,
        )
        assertEquals(
            "无扩展名😀",
            OutputNameDraft(stem = "无扩展名😀", extension = "")
                .toEntryName()
                .getOrThrow()
                .value,
        )
    }

    @Test
    fun `rejects invalid stem extension and malformed unicode`() {
        val invalid = listOf(
            OutputNameDraft("", "pdf"),
            OutputNameDraft(" ", ""),
            OutputNameDraft(".", ""),
            OutputNameDraft("..", ""),
            OutputNameDraft("a/b", "pdf"),
            OutputNameDraft("a\u0000b", "pdf"),
            OutputNameDraft("report", ".pdf"),
            OutputNameDraft("report", "p/df"),
            OutputNameDraft("report", "p\u0000df"),
            OutputNameDraft("bad-\uD83D", "txt"),
            OutputNameDraft("bad", "\uDE00"),
        )

        invalid.forEach { draft ->
            assertTrue("Expected failure for $draft", draft.toEntryName().isFailure)
        }
    }

    @Test
    fun `enforces the combined 255 UTF8 byte boundary`() {
        val exactly255 = OutputNameDraft(stem = "你".repeat(83) + "ab", extension = "txt")
        val over255 = OutputNameDraft(stem = "你".repeat(84), extension = "txt")

        assertEquals(255, exactly255.toEntryName().getOrThrow().value.toByteArray().size)
        assertTrue(over255.toEntryName().isFailure)
    }
}
