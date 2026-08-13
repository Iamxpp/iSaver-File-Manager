package com.iamxpp.isaver.filetools

import com.iamxpp.isaver.data.root.RootFileChunk
import com.iamxpp.isaver.data.root.RootFileVersion
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexViewerRepositoryTest {
    @Test fun `formats offsets hex bytes and printable ascii`() {
        val rows = HexFormatter.rows(0x20, byteArrayOf(0x41, 0x20, 0x00, 0x7e, 0x7f))

        assertEquals(1, rows.size)
        assertEquals("00000020", rows.single().offsetLabel)
        assertEquals("41 20 00 7E 7F", rows.single().hex)
        assertEquals("A .~.", rows.single().ascii)
    }

    @Test fun `formats a partial final row with a 64 bit offset`() {
        val rows = HexFormatter.rows(0x1_0000_0000, ByteArray(17) { it.toByte() })

        assertEquals("0000000100000000", rows.first().offsetLabel)
        assertEquals(16, rows.first().byteCount)
        assertEquals("0000000100000010", rows.last().offsetLabel)
        assertEquals("10", rows.last().hex)
        assertEquals(1, rows.last().byteCount)
    }

    @Test fun `loads empty file as one stable empty page`() = runTest {
        val repository = repository(ByteArray(0))

        val result = repository.loadPage(entry(0), 0)

        val page = (result as OperationResult.Success).value
        assertTrue(page.rows.isEmpty())
        assertEquals(0L, page.totalSizeBytes)
        assertEquals(0L, page.offset)
    }

    @Test fun `clamps page at end of file`() = runTest {
        val repository = repository(ByteArray(20) { it.toByte() }, pageSize = 16)

        val page = (repository.loadPage(entry(20), 16) as OperationResult.Success).value

        assertEquals(16L, page.offset)
        assertEquals(4, page.rows.single().byteCount)
        assertEquals(false, page.hasNext)
        assertEquals(true, page.hasPrevious)
    }

    @Test fun `rejects a changed version while paging`() = runTest {
        val bytes = ByteArray(32)
        var version = version(bytes.size.toLong())
        val repository = HexViewerRepository(
            readRange = { _, offset, count ->
                OperationResult.Success(RootFileChunk(bytes.copyOfRange(offset.toInt(), (offset + count).toInt()), version))
            },
            pageSizeBytes = 16,
        )
        val first = (repository.loadPage(entry(32), 0) as OperationResult.Success).value
        version = version(32, changedSeconds = 99)

        val result = repository.loadPage(entry(32), 16, first.version)

        assertEquals(ErrorCode.OUTCOME_UNCERTAIN, (result as OperationResult.Failure).code)
    }

    private fun repository(bytes: ByteArray, pageSize: Int = 4096) = HexViewerRepository(
        readRange = { _, offset, count ->
            OperationResult.Success(
                RootFileChunk(bytes.copyOfRange(offset.toInt(), (offset + count).toInt()), version(bytes.size.toLong())),
            )
        },
        pageSizeBytes = pageSize,
    )

    private fun entry(size: Long) = DirectoryEntry(
        RootPath.parse("/data/local/tmp/hex.bin").getOrThrow(), "hex.bin", EntryType.FILE,
        size, 1, true, false, false,
    )

    private fun version(size: Long, changedSeconds: Long = 5) =
        RootFileVersion(size, 1, 2, 3, 4, changedSeconds, 6)
}
