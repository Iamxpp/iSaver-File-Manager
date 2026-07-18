package com.iamxpp.isaver.ui.archive

import com.iamxpp.isaver.archive.ArchiveEntry
import com.iamxpp.isaver.archive.ArchiveFormat
import com.iamxpp.isaver.archive.ArchiveListing
import com.iamxpp.isaver.archive.ArchiveProgress
import com.iamxpp.isaver.archive.ArchiveState
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.HomeTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `inspect success exposes a navigable searchable tree and records access`() = runTest(scheduler) {
        val source = root("/archives/backup.tar.gz")
        val recorded = mutableListOf<Pair<RootPath, String>>()
        val viewModel = ArchiveViewModel(
            inspectArchive = {
                OperationResult.Success(
                    ArchiveListing(
                        ArchiveFormat.TAR_GZ,
                        listOf(
                            ArchiveEntry("docs/item10.txt", false, 10L, 5L),
                            ArchiveEntry("docs/item2.txt", false, 2L, 1L),
                        ),
                    ),
                )
            },
            extractArchive = { _, _ -> flowOf() },
            recordAccess = { path, title -> recorded += path to title },
            ioDispatcher = dispatcher,
        )

        viewModel.open(source, "backup.tar.gz", HomeTab.RECENT)
        assertTrue(viewModel.state.value.loading)
        advanceUntilIdle()

        assertEquals(ArchiveFormat.TAR_GZ, viewModel.state.value.listing?.format)
        assertEquals(listOf("docs"), viewModel.state.value.nodes.map { it.name })
        assertEquals(listOf(source to "backup.tar.gz"), recorded)
        viewModel.enter(viewModel.state.value.nodes.single())
        assertEquals("docs", viewModel.state.value.prefix)
        assertEquals(listOf("item2.txt", "item10.txt"), viewModel.state.value.nodes.map { it.name })
        viewModel.setSearchQuery("10")
        assertEquals(listOf("item10.txt"), viewModel.state.value.visibleNodes.map { it.name })
        assertEquals(ArchiveBackResult.NAVIGATED, viewModel.back())
        assertEquals("", viewModel.state.value.prefix)
        assertEquals(ArchiveBackResult.CLOSE_ARCHIVE, viewModel.back())
    }

    @Test
    fun `inspect failure is visible and does not look like an empty archive`() = runTest(scheduler) {
        val viewModel = viewModel(
            inspect = { OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法读取压缩包") },
        )

        viewModel.open(root("/broken.rar"), "broken.rar", HomeTab.BROWSE)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertEquals("无法读取压缩包", viewModel.state.value.errorMessage)
        assertNull(viewModel.state.value.listing)
    }

    @Test
    fun `newer archive request suppresses a stale inspect result`() = runTest(scheduler) {
        val viewModel = viewModel(
            inspect = { path ->
                if (path.value.endsWith("slow.zip")) delay(100)
                OperationResult.Success(
                    ArchiveListing(
                        if (path.value.endsWith("slow.zip")) ArchiveFormat.ZIP else ArchiveFormat.SEVEN_Z,
                        emptyList(),
                    ),
                )
            },
        )

        viewModel.open(root("/slow.zip"), "slow.zip", HomeTab.VIEWS)
        viewModel.open(root("/fast.7z"), "fast.7z", HomeTab.VIEWS)
        advanceUntilIdle()

        assertEquals(root("/fast.7z"), viewModel.state.value.source)
        assertEquals(ArchiveFormat.SEVEN_Z, viewModel.state.value.listing?.format)
    }

    @Test
    fun `choose extraction target and extraction progress remain explicit`() = runTest(scheduler) {
        val progress = ArchiveState.Running(ArchiveProgress.Entry("item.txt", 2L, 10L))
        val success = ArchiveState.Success(ArchiveFormat.ZIP, 1L, 10L)
        val viewModel = ArchiveViewModel(
            inspectArchive = { OperationResult.Success(ArchiveListing(ArchiveFormat.ZIP, emptyList())) },
            extractArchive = { _, _ -> flowOf(progress, success) },
            recordAccess = { _, _ -> },
            ioDispatcher = dispatcher,
        )
        viewModel.open(root("/a.zip"), "a.zip", HomeTab.VIEWS)
        advanceUntilIdle()

        viewModel.chooseExtractionTarget()
        assertTrue(viewModel.state.value.extractionTargetRequested)
        viewModel.consumeExtractionTargetRequest()
        assertFalse(viewModel.state.value.extractionTargetRequested)
        viewModel.extractTo(root("/target"))
        advanceUntilIdle()

        assertEquals(success, viewModel.state.value.operation)
    }

    private fun viewModel(
        inspect: suspend (RootPath) -> OperationResult<ArchiveListing>,
    ) = ArchiveViewModel(
        inspectArchive = inspect,
        extractArchive = { _, _ -> flowOf() },
        recordAccess = { _, _ -> },
        ioDispatcher = dispatcher,
    )

    private fun root(value: String) = RootPath.parse(value).getOrThrow()
}
