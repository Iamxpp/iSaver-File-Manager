package com.isaver.filemanager.locations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationCatalogTest {
    @Test fun `common locations have stable product order and paths`() {
        assertEquals(
            listOf(
                "内部存储" to "/storage/emulated/0",
                "下载" to "/storage/emulated/0/Download",
                "文档" to "/storage/emulated/0/Documents",
                "图片" to "/storage/emulated/0/Pictures",
                "视频" to "/storage/emulated/0/Movies",
            ),
            LocationCatalog.commonLocations.map { it.displayName to it.path.value },
        )
        assertTrue(LocationCatalog.commonLocations.all { it.source == StorageLocation.Source.BUILT_IN })
    }

    @Test fun `product catalog avoids app specific absolute paths by default`() {
        assertTrue(LocationCatalog.appTemplates.isEmpty())
        assertTrue(LocationCatalog.appLocations.isEmpty())

        val builtInPaths = LocationCatalog.commonLocations.map { it.path.value }
        assertFalse(builtInPaths.any { it.contains("com.tencent", ignoreCase = true) })
        assertFalse(builtInPaths.any { it.contains("MicroMsg", ignoreCase = true) })
    }

    @Test fun `catalog ids are unique and every path is validated`() {
        val commonIds = LocationCatalog.commonLocations.map { it.id.value }
        val candidateIds = LocationCatalog.appTemplates.flatMap { template -> template.candidates.map { it.id.value } }
        assertEquals(commonIds.size, commonIds.toSet().size)
        assertEquals(candidateIds.size, candidateIds.toSet().size)
        val globalIds = commonIds + candidateIds + LocationCatalog.appTemplates.map { it.id.value } + LocationCatalog.appLocations.map { it.id.value }
        assertEquals(globalIds.size, globalIds.toSet().size)
        assertTrue(LocationCatalog.commonLocations.all { it.path.value.startsWith('/') })
        assertTrue(LocationCatalog.appTemplates.all { template -> template.candidates.all { it.path.value.startsWith('/') } })
    }

    @Test fun `app template group uses app template source`() {
        val template = AppPathTemplate(
            LocationId.of("template.test"), "Test", listOf("pkg"),
            listOf(PathCandidate(LocationId.of("candidate.one"), "one", root("/one"), 1)),
        )
        val group = LocationCatalog.groupFor(template)
        assertEquals("Test", group.displayName)
        assertEquals(StorageLocation.Source.APP_TEMPLATE, group.source)
        assertEquals(template.candidates.map { it.path }, group.children.map { it.path })
    }

    @Test fun `group children are derived in candidate priority order`() {
        val template = AppPathTemplate(
            LocationId.of("template.test"), "Test", listOf("pkg"),
            listOf(
                PathCandidate(LocationId.of("candidate.third"), "third", root("/third"), 30),
                PathCandidate(LocationId.of("candidate.first"), "first", root("/first"), 10),
                PathCandidate(LocationId.of("candidate.second"), "second", root("/second"), 20),
            ),
        )
        assertEquals(listOf("first", "second", "third"), LocationCatalog.groupFor(template).children.map { it.displayName })
    }

    @Test fun `model rejects invalid ids priorities and duplicate template priorities`() {
        assertThrows(IllegalArgumentException::class.java) { LocationId.of(" ") }
        assertThrows(IllegalArgumentException::class.java) { LocationId.of("bad\u0000id") }
        assertThrows(IllegalArgumentException::class.java) { PathCandidate(LocationId.of("candidate.bad"), "bad", root("/bad"), -1) }
        assertThrows(IllegalArgumentException::class.java) {
            AppPathTemplate(LocationId.of("template.bad"), "bad", listOf("pkg"), listOf(
                PathCandidate(LocationId.of("candidate.a"), "a", root("/a"), 1),
                PathCandidate(LocationId.of("candidate.b"), "b", root("/b"), 1),
            ))
        }
    }

    @Test fun `model defensively copies mutable input lists`() {
        val packages = mutableListOf("pkg")
        val candidates = mutableListOf(PathCandidate(LocationId.of("candidate.copy"), "copy", root("/copy"), 1))
        val template = AppPathTemplate(LocationId.of("template.copy"), "copy", packages, candidates)
        packages += "mutated"; candidates.clear()
        assertEquals(listOf("pkg"), template.packageNames)
        assertEquals(1, template.candidates.size)
        val children = mutableListOf(StorageLocation.Direct(LocationId.of("direct.copy"), "copy", root("/copy"), StorageLocation.Source.APP_TEMPLATE))
        val group = StorageLocation.Group(LocationId.of("group.copy"), "copy", children, StorageLocation.Source.APP_TEMPLATE)
        children.clear()
        assertEquals(1, group.children.size)
    }

    private fun root(value: String) = com.isaver.filemanager.domain.RootPath.parse(value).getOrThrow()
}
