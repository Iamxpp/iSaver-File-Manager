package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.trash.TrashItem
import com.iamxpp.isaver.trash.TrashItemState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverableReplaceRepositoryTest {
    @Test fun `recycles existing target before publishing replacement`() = runTest {
        val calls = mutableListOf<String>()
        val repository = repository(
            recycle = { _, _ -> calls += "recycle"; OperationResult.Success(trashItem()) },
            restore = { error("must not restore") },
        )
        val result = repository.replace(source(), SOURCE, TARGET) { source, _, target, _ ->
            calls += "publish"
            OperationResult.Success(source.copy(path = path("${target.value}/${source.name}")))
        }
        assertTrue(result is OperationResult.Success)
        assertEquals(listOf("recycle", "publish"), calls)
    }

    @Test fun `restores recycled target when publication fails`() = runTest {
        var restored = false
        val repository = repository(
            recycle = { _, _ -> OperationResult.Success(trashItem()) },
            restore = { restored = true; OperationResult.Success(existing()) },
        )
        val failure = OperationResult.Failure(ErrorCode.NO_SPACE, "空间不足")
        val result = repository.replace(source(), SOURCE, TARGET) { _, _, _, _ -> failure }
        assertEquals(failure, result)
        assertTrue(restored)
    }

    @Test fun `reports uncertain when failed publication cannot restore target`() = runTest {
        val repository = repository(
            recycle = { _, _ -> OperationResult.Success(trashItem()) },
            restore = { OperationResult.Failure(ErrorCode.ALREADY_EXISTS, "冲突") },
        )
        val result = repository.replace(source(), SOURCE, TARGET) { _, _, _, _ ->
            OperationResult.Failure(ErrorCode.NO_SPACE, "空间不足")
        }
        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
    }

    private fun repository(
        recycle: suspend (DirectoryEntry, RootPath) -> OperationResult<TrashItem>,
        restore: suspend (TrashItem) -> OperationResult<DirectoryEntry>,
    ) = RecoverableReplaceRepository(
        stat = { OperationResult.Success(existing()) },
        recycle = recycle,
        restore = restore,
    )

    private fun source() = entry("report.txt", SOURCE)
    private fun existing() = entry("report.txt", TARGET)
    private fun entry(name: String, parent: RootPath) = DirectoryEntry(
        path("${parent.value}/$name"), name, EntryType.FILE, 10, 20, true, true, false,
    )
    private fun trashItem() = TrashItem(
        "backup", existing().path, TARGET, "report.txt", path("/storage/emulated/0/.iSaver/Trash/files/backup"),
        "backup", EntryType.FILE, 10, 1, 2, TrashItemState.ACTIVE, 3,
    )
    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private companion object {
        val SOURCE = RootPath.parse("/storage/emulated/0/source").getOrThrow()
        val TARGET = RootPath.parse("/storage/emulated/0/target").getOrThrow()
    }
}
