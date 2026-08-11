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

    @Test
    fun `read file command uses fixed base64 stdout helper`() {
        val command = helper.readFile("/data/user/0/com.iamxpp.isaver/files/report with space.bin")
        assertTrue(command.contains("'read-file-stdout' '/data/user/0/com.iamxpp.isaver/files/report with space.bin'"))
        assertFalse(command.contains("'cat'"))
        assertFalse(command.contains("sh -c"))
    }

    @Test
    fun `same filesystem move command carries exact parent and source identities`() {
        val command = helper.moveNoReplace(
            sourceOriginal = "/source original",
            sourceCanonical = "/source canonical",
            sourceName = com.iamxpp.isaver.domain.EntryName.parse("a';\n.txt").getOrThrow(),
            sourceParentIdentity = RootFileIdentity(1L, 2L),
            sourceIdentity = RootFileIdentity(3L, 4L),
            targetOriginal = "/target original",
            targetCanonical = "/target canonical",
            targetParentIdentity = RootFileIdentity(5L, 6L),
        )

        assertTrue(command.contains("'move-noreplace' '/source original' '/source canonical'"))
        assertTrue(command.contains("'a'\\'';\n.txt' '1' '2' '3' '4'"))
        assertTrue(command.endsWith("'/target original' '/target canonical' '5' '6'"))
        assertFalse(command.contains(" mv "))
        assertFalse(command.contains("sh -c"))
    }

    @Test
    fun `extraction commands keep parent and stage identity in fixed argv`() {
        val stage = ExtractionStage.create(
            com.iamxpp.isaver.domain.RootPath.parse("/original").getOrThrow(),
            com.iamxpp.isaver.domain.RootPath.parse("/canonical").getOrThrow(),
            RootFileIdentity(1L, 2L),
            ".isaver-extract-123e4567-e89b-12d3-a456-426614174000",
            RootFileIdentity(3L, 4L),
        ).getOrThrow()

        val prepare = helper.prepareExtraction(stage.originalParent.value, stage.canonicalParent.value, stage.name, stage.parentIdentity)
        val mkdir = helper.createExtractionDirectory(stage, "目录 one/子目录")
        val copy = helper.copyIntoExtraction(stage, "目录 one", source(), com.iamxpp.isaver.domain.EntryName.parse("报告.txt").getOrThrow(), 5_000)
        val commit = helper.commitExtraction(stage, com.iamxpp.isaver.domain.FolderName.parse("backup").getOrThrow())
        val remove = helper.removeExtraction(stage)

        assertTrue(prepare.contains("'prepare-extract-stage' '/original' '/canonical'"))
        assertTrue(mkdir.contains("'mkdir-extract' '/original' '/canonical' '${stage.name}' '目录 one/子目录' '1' '2' '3' '4'"))
        assertTrue(copy.contains("'copy-extract-stdin' '/original' '/canonical' '${stage.name}' '目录 one' '报告.txt' '1' '2' '3' '4' '5'"))
        assertTrue(copy.startsWith("set -o pipefail\n'/system/bin/content' 'read' '--uri'"))
        assertTrue(commit.contains("'commit-extract-stage' '/original' '/canonical' '${stage.name}' 'backup' '1' '2' '3' '4'"))
        assertTrue(remove.contains("'remove-extract-stage' '/original' '/canonical' '${stage.name}' '1' '2' '3' '4'"))
        listOf(prepare, mkdir, copy, commit, remove).forEach {
            assertFalse(it.contains("/data/user/0"))
            assertFalse(it.contains(" rm "))
        }
    }

    private fun source() = RootTransferSource(
        contentUri = "content://com.iamxpp.isaver.incoming-stream/incoming/${"ab".repeat(32)}",
        expectedSizeBytes = 5L,
        token = "ab".repeat(32),
    )
}
