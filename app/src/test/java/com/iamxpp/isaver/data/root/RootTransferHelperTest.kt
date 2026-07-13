package com.iamxpp.isaver.data.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTransferHelperTest {
    private val helper = RootTransferHelper("/lib dir/libisaver_fs_helper.so")

    @Test
    fun `commands expose only fixed staging operations and quote hostile basenames`() {
        val parent = RootFileIdentity(1, 2)
        val stage = TransferStage(".isaver-stage-123e4567-e89b-12d3-a456-426614174000", RootFileIdentity(3, 4))
        val source = appCachePath()

        val prepare = helper.prepare("/original", "/canonical", stage.name, parent)
        val copy = helper.copyPublish("/original", "/canonical", stage, "a';\n.txt", parent, source, 5,5_000)
        val remove = helper.removeStage("/original", "/canonical", stage, parent)

        assertTrue(prepare.contains("'prepare-stage' '/original' '/canonical'"))
        assertTrue(copy.startsWith("'/system/bin/timeout' '-s' 'KILL' '5.000' '/lib dir/libisaver_fs_helper.so' 'copy-publish'"))
        assertTrue(copy.contains("'copy-publish' '/original' '/canonical'"))
        assertTrue(copy.contains("'a'\\'';\n.txt'"))
        assertTrue(remove.contains("'remove-stage' '/original' '/canonical'"))
        listOf(prepare, copy, remove).forEach {
            assertFalse(it.contains("copy-to-temp"))
            assertFalse(it.contains("publish-noreplace"))
            assertFalse(it.contains("remove-temp"))
        }
    }

    @Test
    fun `copy publish argv order and identities are exact`() {
        val stage=TransferStage(".isaver-stage-123e4567-e89b-12d3-a456-426614174000",RootFileIdentity(3,4))
        val source=appCachePath()
        val command=helper.copyPublish(
            "/original","/canonical",stage,"final.txt",RootFileIdentity(1,2),source,5,1_250,
        )

        assertEquals(
            listOf(
                "/system/bin/timeout","-s","KILL","1.250","/lib dir/libisaver_fs_helper.so",
                "copy-publish","/original","/canonical",stage.name,"final.txt",source.value,
                "1","2","3","4","5","6","5",
            ),
            Regex("'([^']*)'").findAll(command).map{it.groupValues[1]}.toList(),
        )
    }

    private fun appCachePath(): AppCachePath {
        val cache = java.nio.file.Files.createTempDirectory("isaver-helper-source").toFile()
        val file = java.io.File(cache, "incoming/123e4567-e89b-12d3-a456-426614174000.tmp")
        requireNotNull(file.parentFile).mkdirs()
        file.writeText("hello")
        return AppCachePath.fromIncomingCacheFile(cache, file) { 5L to 6L }.getOrThrow()
    }
}
