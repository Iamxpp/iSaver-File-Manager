package com.iamxpp.isaver.locations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun `wechat template defines five documented candidates`() {
        val template = LocationCatalog.weChat
        assertEquals(listOf("com.tencent.mm"), template.packageNames)
        assertEquals(
            listOf(
                "/storage/emulated/0/Android/data/com.tencent.mm",
                "/storage/emulated/0/Android/media/com.tencent.mm",
                "/storage/emulated/0/tencent/MicroMsg",
                "/data/user/0/com.tencent.mm",
                "/data/data/com.tencent.mm",
            ),
            template.candidates.map { it.path.value },
        )
        assertEquals(listOf(10, 20, 30, 40, 50), template.candidates.map { it.priority })
    }

    @Test fun `catalog ids are unique and every path is validated`() {
        val commonIds = LocationCatalog.commonLocations.map { it.id }
        val candidateIds = LocationCatalog.weChat.candidates.map { it.id }
        assertEquals(commonIds.size, commonIds.toSet().size)
        assertEquals(candidateIds.size, candidateIds.toSet().size)
        val globalIds = commonIds + candidateIds + LocationCatalog.weChat.id
        assertEquals(globalIds.size, globalIds.toSet().size)
        assertTrue(LocationCatalog.commonLocations.all { it.path.value.startsWith('/') })
        assertTrue(LocationCatalog.weChat.candidates.all { it.path.value.startsWith('/') })
    }

    @Test fun `wechat group uses app template source`() {
        val group = LocationCatalog.appLocations.single()
        assertEquals("微信", group.displayName)
        assertEquals(StorageLocation.Source.APP_TEMPLATE, group.source)
        assertEquals(LocationCatalog.weChat.candidates.map { it.path }, group.children.map { it.path })
    }
}
