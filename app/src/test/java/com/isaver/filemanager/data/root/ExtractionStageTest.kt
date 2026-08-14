package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.RootPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionStageTest {
    @Test
    fun `stage name is generated-only and bound to parent and stage identities`() {
        val stage = ExtractionStage.create(
            originalParent = path("/original"),
            canonicalParent = path("/canonical"),
            parentIdentity = RootFileIdentity(1L, 2L),
            name = ".isaver-extract-123e4567-e89b-12d3-a456-426614174000",
            stageIdentity = RootFileIdentity(3L, 4L),
        ).getOrThrow()

        assertEquals("/original", stage.originalParent.value)
        assertEquals("/canonical", stage.canonicalParent.value)
        assertEquals(RootFileIdentity(1L, 2L), stage.parentIdentity)
        assertEquals(RootFileIdentity(3L, 4L), stage.stageIdentity)
        listOf(
            ".isaver-stage-123e4567-e89b-12d3-a456-426614174000",
            ".isaver-extract-not-a-uuid",
            "/.isaver-extract-123e4567-e89b-12d3-a456-426614174000",
        ).forEach { invalid ->
            assertTrue(
                ExtractionStage.create(
                    path("/original"), path("/canonical"), RootFileIdentity(1L, 2L),
                    invalid, RootFileIdentity(3L, 4L),
                ).isFailure,
            )
        }
    }

    @Test
    fun `relative extraction paths reject absolute parent traversal nul and empty components`() {
        assertEquals("目录 one/子目录", ExtractionRelativePath.directory("目录 one/子目录").getOrThrow().value)
        assertEquals("", ExtractionRelativePath.parent("").getOrThrow().value)
        listOf("/absolute", "../escape", "a/../b", "a/./b", "a//b", "a/", "a\u0000b").forEach {
            assertTrue(ExtractionRelativePath.parent(it).isFailure)
            assertTrue(ExtractionRelativePath.directory(it).isFailure)
        }
        assertTrue(ExtractionRelativePath.directory("").isFailure)
    }

    private fun path(value: String) = RootPath.parse(value).getOrThrow()
}
