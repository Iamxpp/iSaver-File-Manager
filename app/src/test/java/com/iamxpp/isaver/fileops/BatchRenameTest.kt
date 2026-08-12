package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRenameTest {
    private val planner = BatchRenamePlanner()

    @Test
    fun `plans find replace prefix suffix numbering case and regex previews`() {
        val selected = listOf(entry("IMG_old.jpg"), entry("IMG_old.png"))
        fun names(rule: BatchRenameRule) = (planner.plan(selected, selected, rule) as OperationResult.Success)
            .value.items.map { it.targetName.value }

        assertEquals(listOf("IMG_new.jpg", "IMG_new.png"), names(BatchRenameRule(BatchRenameMode.FIND_REPLACE, find = "old", replacement = "new")))
        assertEquals(listOf("pre_IMG_old.jpg_done", "pre_IMG_old.png_done"), names(BatchRenameRule(BatchRenameMode.PREFIX_SUFFIX, prefix = "pre_", suffix = "_done")))
        assertEquals(listOf("file_08.txt", "file_09.txt"), names(BatchRenameRule(BatchRenameMode.NUMBERING, prefix = "file_", suffix = ".txt", startNumber = 8, numberWidth = 2)))
        assertEquals(listOf("img_old.jpg", "img_old.png"), names(BatchRenameRule(BatchRenameMode.CASE)))
        assertEquals(listOf("Photo-old.jpg", "Photo-old.png"), names(BatchRenameRule(BatchRenameMode.REGEX, find = "^IMG_(.*)", replacement = "Photo-$1")))
    }

    @Test
    fun `rejects invalid regex duplicate targets external conflicts and unchanged plans`() {
        val selected = listOf(entry("a.txt"), entry("b.txt"))
        val cases = listOf(
            planner.plan(selected, selected, BatchRenameRule(BatchRenameMode.REGEX, find = "[")) to ErrorCode.COMMAND_FAILED,
            planner.plan(selected, selected, BatchRenameRule(BatchRenameMode.NUMBERING, prefix = "same", startNumber = 1, numberWidth = 0, suffix = "")) to null,
            planner.plan(selected, selected + entry("c.txt"), BatchRenameRule(BatchRenameMode.FIND_REPLACE, find = "a", replacement = "c")) to ErrorCode.ALREADY_EXISTS,
            planner.plan(selected, selected, BatchRenameRule(BatchRenameMode.FIND_REPLACE, find = "missing", replacement = "x")) to ErrorCode.ALREADY_EXISTS,
        )
        assertEquals(ErrorCode.COMMAND_FAILED, (cases[0].first as OperationResult.Failure).code)
        assertTrue(cases[1].first is OperationResult.Success)
        assertEquals(ErrorCode.ALREADY_EXISTS, (cases[2].first as OperationResult.Failure).code)
        assertEquals(ErrorCode.ALREADY_EXISTS, (cases[3].first as OperationResult.Failure).code)
    }

    @Test
    fun `executor uses temporary names and handles swaps`() = runTest {
        val parent = path("/data/local/tmp")
        val current = linkedMapOf("a" to entry("a"), "b" to entry("b"))
        val calls = mutableListOf<Pair<String, String>>()
        val executor = BatchRenameExecutor(
            rename = { source, _, target ->
                calls += source.name to target
                current.remove(source.name)
                val output = source.copy(path = path("${parent.value}/$target"), name = target)
                current[target] = output
                OperationResult.Success(output)
            },
            temporaryName = sequenceOf(".tmp-a", ".tmp-b").iterator()::next,
        )
        val plan = BatchRenamePlan(listOf(
            BatchRenameItem(current.getValue("a"), com.iamxpp.isaver.domain.EntryName.parse("b").getOrThrow()),
            BatchRenameItem(current.getValue("b"), com.iamxpp.isaver.domain.EntryName.parse("a").getOrThrow()),
        ))

        val result = executor.execute(plan, parent)

        assertTrue(result is OperationResult.Success)
        assertEquals(listOf("a" to ".tmp-a", "b" to ".tmp-b", ".tmp-a" to "b", ".tmp-b" to "a"), calls)
    }

    @Test
    fun `executor rolls back deterministic staging failure`() = runTest {
        val parent = path("/data/local/tmp")
        val calls = mutableListOf<Pair<String, String>>()
        val executor = BatchRenameExecutor(
            rename = { source, _, target ->
                calls += source.name to target
                if (source.name == "b") OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "conflict")
                else OperationResult.Success(source.copy(path = path("${parent.value}/$target"), name = target))
            },
            temporaryName = sequenceOf(".tmp-a", ".tmp-b").iterator()::next,
        )
        val plan = BatchRenamePlan(listOf(
            item("a", "x"), item("b", "y"),
        ))

        val result = executor.execute(plan, parent)

        assertEquals(ErrorCode.ALREADY_EXISTS, (result as OperationResult.Failure).code)
        assertEquals(listOf("a" to ".tmp-a", "b" to ".tmp-b", ".tmp-a" to "a"), calls)
    }

    @Test
    fun `executor preserves preview order when a planned item is unchanged`() = runTest {
        val parent = path("/data/local/tmp")
        val unchanged = entry("keep")
        val executor = BatchRenameExecutor(
            rename = { source, _, target ->
                OperationResult.Success(source.copy(path = path("${parent.value}/$target"), name = target))
            },
            temporaryName = { ".tmp-change" },
        )
        val plan = BatchRenamePlan(listOf(item("change", "changed"), BatchRenameItem(
            unchanged,
            com.iamxpp.isaver.domain.EntryName.parse("keep").getOrThrow(),
        )))

        val result = executor.execute(plan, parent) as OperationResult.Success

        assertEquals(listOf("changed", "keep"), result.value.renamed.map { it.name })
    }

    @Test
    fun `executor restores every source after deterministic commit failure`() = runTest {
        val parent = path("/data/local/tmp")
        val calls = mutableListOf<Pair<String, String>>()
        val executor = BatchRenameExecutor(
            rename = { source, _, target ->
                calls += source.name to target
                if (source.name == ".tmp-b" && target == "y") {
                    OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "conflict")
                } else {
                    OperationResult.Success(source.copy(path = path("${parent.value}/$target"), name = target))
                }
            },
            temporaryName = sequenceOf(".tmp-a", ".tmp-b", ".tmp-rebound").iterator()::next,
        )

        val result = executor.execute(
            BatchRenamePlan(listOf(item("a", "x"), item("b", "y"))),
            parent,
        )

        assertEquals(ErrorCode.ALREADY_EXISTS, (result as OperationResult.Failure).code)
        assertEquals(
            listOf(
                "a" to ".tmp-a",
                "b" to ".tmp-b",
                ".tmp-a" to "x",
                ".tmp-b" to "y",
                "x" to ".tmp-rebound",
                ".tmp-rebound" to "a",
                ".tmp-b" to "b",
            ),
            calls,
        )
    }

    private fun item(source: String, target: String) = BatchRenameItem(
        entry(source), com.iamxpp.isaver.domain.EntryName.parse(target).getOrThrow(),
    )

    private fun entry(name: String) = DirectoryEntry(
        path = path("/data/local/tmp/$name"), name = name, type = EntryType.FILE,
        sizeBytes = 1, modifiedAtEpochSeconds = 1, readable = true, writable = true, symbolicLink = false,
    )

    private fun path(value: String) = RootPath.parse(value).getOrThrow()
}
