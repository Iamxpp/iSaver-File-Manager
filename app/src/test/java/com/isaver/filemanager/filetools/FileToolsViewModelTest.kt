package com.isaver.filemanager.filetools

import com.isaver.filemanager.data.root.RootFileChunk
import com.isaver.filemanager.data.root.RootFileVersion
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.fileops.ChecksumAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileToolsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `hex opens pages jumps offsets and closes`() = runTest(dispatcher.scheduler) {
        val bytes = ByteArray(48) { it.toByte() }
        val viewModel = viewModel(mapOf("a.bin" to bytes), pageSize = 16)

        viewModel.openHex(entry("a.bin", bytes.size.toLong()))
        advanceUntilIdle()
        assertEquals(0L, viewModel.state.value.hexPage?.offset)
        viewModel.nextHexPage()
        advanceUntilIdle()
        assertEquals(16L, viewModel.state.value.hexPage?.offset)
        assertTrue(viewModel.jumpToOffset("0x21"))
        advanceUntilIdle()
        assertEquals(32L, viewModel.state.value.hexPage?.offset)
        assertFalse(viewModel.jumpToOffset("bad"))
        viewModel.close()
        assertFalse(viewModel.state.value.visible)
    }

    @Test fun `comparison exposes content and selected checksum results`() = runTest(dispatcher.scheduler) {
        val data = mapOf("a.bin" to "abcd".toByteArray(), "b.bin" to "abXd".toByteArray())
        val viewModel = viewModel(data)

        viewModel.openComparison(listOf(entry("a.bin", 4), entry("b.bin", 4)))
        advanceUntilIdle()

        assertEquals(2L, (viewModel.state.value.contentComparison as ContentComparison.DifferentContent).firstDifferenceOffset)
        assertFalse(viewModel.state.value.checksumComparison?.identical ?: true)
        viewModel.setChecksumAlgorithm(ChecksumAlgorithm.MD5)
        advanceUntilIdle()
        assertEquals(ChecksumAlgorithm.MD5, viewModel.state.value.checksumComparison?.algorithm)
    }

    private fun viewModel(data: Map<String, ByteArray>, pageSize: Int = 16): FileToolsViewModel {
        val read: suspend (DirectoryEntry, Long, Long) -> OperationResult<RootFileChunk> = { entry, offset, count ->
            val bytes = data.getValue(entry.name)
            OperationResult.Success(
                RootFileChunk(
                    bytes.copyOfRange(offset.toInt(), (offset + count).toInt()),
                    RootFileVersion(bytes.size.toLong(), 1, entry.name.hashCode().toLong(), 3, 4, 5, 6),
                ),
            )
        }
        return FileToolsViewModel(
            HexViewerRepository(read, pageSize),
            FileComparisonRepository(
                readRange = read,
                checksum = { entry, algorithm -> OperationResult.Success("${algorithm.name}:${data.getValue(entry.name).contentHashCode()}") },
                chunkSizeBytes = 2,
            ),
            dispatcher,
        )
    }

    private fun entry(name: String, size: Long) = DirectoryEntry(
        RootPath.parse("/data/local/tmp/$name").getOrThrow(), name, EntryType.FILE,
        size, 1, true, false, false,
    )
}
