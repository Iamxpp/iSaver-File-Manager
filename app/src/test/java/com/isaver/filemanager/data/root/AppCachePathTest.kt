package com.isaver.filemanager.data.root

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCachePathTest {
    private val cacheDir = java.nio.file.Files.createTempDirectory("isaver-cache").toFile()
    private val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

    @Test
    fun `factory accepts only a direct UUID tmp child of the supplied cache incoming directory`() {
        val candidate = File(cacheDir, "incoming/$uuid.tmp")
        requireNotNull(candidate.parentFile).mkdirs();candidate.writeText("x")

        val path = AppCachePath.fromIncomingCacheFile(cacheDir, candidate){1L to 2L}.getOrThrow()

        assertEquals(candidate.canonicalPath, path.value)
    }

    @Test
    fun `factory rejects lookalike paths not bound to the supplied cache directory`() {
        val candidates = listOf(
            File("/data/user/0/another.app/cache/incoming/$uuid.tmp"),
            File(cacheDir, "incoming/nested/$uuid.tmp"),
            File(cacheDir, "incoming/source.tmp"),
            File(cacheDir, "incoming/$uuid.tmp/child"),
        )

        candidates.forEach { candidate ->
            assertTrue(
                "unexpectedly accepted ${candidate.path}",
                AppCachePath.fromIncomingCacheFile(cacheDir, candidate){1L to 2L}.isFailure,
            )
        }
    }

    @Test
    fun `factory rejects a stat failure instead of inventing an identity`() {
        val candidate = File(cacheDir, "incoming/$uuid.tmp").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("x")
        }

        assertTrue(
            AppCachePath.fromIncomingCacheFile(cacheDir, candidate) { error("stat failed") }.isFailure,
        )
    }
}
