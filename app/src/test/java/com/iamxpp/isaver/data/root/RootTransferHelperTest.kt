package com.iamxpp.isaver.data.root

import java.io.File
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
        val source = source()

        val prepare = helper.prepare("/original", "/canonical", stage.name, parent)
        val copy = helper.copyPublish("/original", "/canonical", stage, "a';\n.txt", parent, source, 5_000)
        val remove = helper.removeStage("/original", "/canonical", stage, parent)

        assertTrue(prepare.contains("'prepare-stage' '/original' '/canonical'"))
        assertTrue(copy.startsWith("set -o pipefail\n'/system/bin/content' 'read' '--uri'"))
        assertTrue(copy.contains("| '/system/bin/timeout' '-s' 'KILL' '5.000' '/lib dir/libisaver_fs_helper.so' 'copy-publish-stdin'"))
        assertTrue(copy.contains("'copy-publish-stdin' '/original' '/canonical'"))
        assertTrue(copy.contains("'a'\\'';\n.txt'"))
        assertFalse(copy.contains("/data/user/0"))
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
        val source=source()
        val command=helper.copyPublish(
            "/original","/canonical",stage,"final.txt",RootFileIdentity(1,2),source,1_250,
        )

        assertEquals(
            listOf(
                "/system/bin/content", "read", "--uri", source.contentUri,
                "/system/bin/timeout","-s","KILL","1.250","/lib dir/libisaver_fs_helper.so",
                "copy-publish-stdin","/original","/canonical",stage.name,"final.txt",
                "1","2","3","4","5",
            ),
            Regex("'([^']*)'").findAll(command).map{it.groupValues[1]}.toList(),
        )
    }

    @Test
    fun `native helper allowlists only stdin publication`() {
        val sourceFile = listOf(
            File("app/src/main/cpp/isaver_fs_helper.c"),
            File("src/main/cpp/isaver_fs_helper.c"),
        ).first(File::isFile)
        val main = sourceFile.readText()
            .substringAfter("int main(int argc")
            .substringBeforeLast("}")

        assertTrue(main.contains("copy-publish-stdin"))
        assertFalse(main.contains("strcmp(argv[1], \"copy-publish\")"))
    }

    private fun source() = RootTransferSource(
        contentUri = "content://com.iamxpp.isaver.incoming-stream/incoming/${"ab".repeat(32)}",
        expectedSizeBytes = 5L,
        token = "ab".repeat(32),
    )
}
