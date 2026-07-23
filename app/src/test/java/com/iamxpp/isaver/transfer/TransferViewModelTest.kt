package com.iamxpp.isaver.transfer

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.iamxpp.isaver.data.root.AppCachePath
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.share.IncomingShare
import com.iamxpp.isaver.share.ShareIntentFailureReason
import com.iamxpp.isaver.share.ShareIntentParseResult
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TransferViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `accepting a share caches immediately while target selection stays usable`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cacheRelease = CompletableDeferred<Unit>()
        var cacheCalls = 0
        var targetValidations = 0
        val viewModel = viewModel(
            cacheIncoming = { _, progress ->
                cacheCalls += 1
                progress(7L)
                cacheRelease.await()
                IncomingFileCacheResult.Success(cached("first"))
            },
            validateTarget = {
                targetValidations += 1
                OperationResult.Success(it)
            },
            dispatcher = dispatcher,
        )

        viewModel.acceptShare(share("report.pdf"))
        testScheduler.runCurrent()

        val caching = viewModel.state.value as TransferUiState.Caching
        assertEquals(OutputNameDraft("report", "pdf"), caching.outputName)
        assertEquals(7L, caching.bytesCopied)
        assertEquals(1, cacheCalls)

        viewModel.selectTarget(root("/target"))
        testScheduler.runCurrent()
        val selectedWhileCaching = viewModel.state.value as TransferUiState.Caching
        assertEquals(root("/target"), selectedWhileCaching.targetDirectory)
        assertEquals(1, targetValidations)

        cacheRelease.complete(Unit)
        advanceUntilIdle()

        val choosing = viewModel.state.value as TransferUiState.Choosing
        assertEquals(root("/target"), choosing.targetDirectory)
        assertTrue(choosing.canSave)
    }

    @Test
    fun `clearing target disables save without discarding cached share`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = viewModel(
            validateTarget = { OperationResult.Success(it) },
            dispatcher = dispatcher,
        )
        viewModel.acceptShare(share("report.pdf"))
        advanceUntilIdle()
        viewModel.selectTarget(root("/target"))
        advanceUntilIdle()
        assertTrue((viewModel.state.value as TransferUiState.Choosing).canSave)

        viewModel.clearTarget()

        val choosing = viewModel.state.value as TransferUiState.Choosing
        assertNull(choosing.targetDirectory)
        assertFalse(choosing.canSave)
        assertEquals(OutputNameDraft("report", "pdf"), choosing.outputName)
    }

    @Test
    fun `edited output fields disable invalid names and reach publish without being re-split`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var publishedName: OutputNameDraft? = null
        val viewModel = viewModel(
            validateTarget = { OperationResult.Success(it) },
            transferCached = { _, outputName, _, _ ->
                publishedName = outputName
                flowOf(TransferState.Failure(ErrorCode.NO_SPACE, "safe"))
            },
            dispatcher = dispatcher,
        )
        viewModel.acceptShare(share("archive.tar.gz"))
        advanceUntilIdle()
        assertEquals(
            OutputNameDraft("archive.tar", "gz"),
            (viewModel.state.value as TransferUiState.Choosing).outputName,
        )
        viewModel.selectTarget(root("/target"))
        advanceUntilIdle()

        viewModel.setStem("archive")
        viewModel.setExtension(".tar.gz")
        assertFalse((viewModel.state.value as TransferUiState.Choosing).canSave)

        viewModel.setExtension("tar.gz")
        assertTrue((viewModel.state.value as TransferUiState.Choosing).canSave)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(OutputNameDraft("archive", "tar.gz"), publishedName)
        assertEquals(
            OutputNameDraft("archive", "tar.gz"),
            (viewModel.state.value as TransferUiState.Failure).outputName,
        )
    }

    @Test
    fun `publish failure keeps specific repository message`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = readyViewModel(
            transferCached = { _, _, _, _ ->
                flowOf(TransferState.Failure(ErrorCode.COMMAND_FAILED, "文件名过长，无法保存"))
            },
            dispatcher = dispatcher,
        )

        viewModel.save()
        advanceUntilIdle()

        val failure = viewModel.state.value as TransferUiState.Failure
        assertEquals(ErrorCode.COMMAND_FAILED, failure.code)
        assertEquals("文件名过长，无法保存", failure.message)
    }

    @Test
    fun `editing name after retryable failure returns to saveable choosing state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publishedNames = mutableListOf<OutputNameDraft>()
        val viewModel = readyViewModel(
            transferCached = { _, outputName, _, _ ->
                publishedNames += outputName
                if (publishedNames.size == 1) {
                    flowOf(TransferState.Failure(ErrorCode.COMMAND_FAILED, "无法准备目标目录"))
                } else {
                    val displayName = if (outputName.extension.isEmpty()) {
                        outputName.stem
                    } else {
                        "${outputName.stem}.${outputName.extension}"
                    }
                    flowOf(TransferState.Success(file("/target/$displayName"), name(displayName)))
                }
            },
            dispatcher = dispatcher,
        )

        viewModel.save()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is TransferUiState.Failure)

        viewModel.setStem("renamed")
        advanceUntilIdle()

        val choosing = viewModel.state.value as TransferUiState.Choosing
        assertEquals(OutputNameDraft("renamed", "txt"), choosing.outputName)
        assertTrue(choosing.canSave)

        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.state.value is TransferUiState.Success)
        assertEquals(listOf(OutputNameDraft("a", "txt"), OutputNameDraft("renamed", "txt")), publishedNames)
    }

    @Test
    fun `save revalidates cache and target before the publish boundary`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var targetValidations = 0
        var cacheValid = true
        var publishCalls = 0
        val viewModel = viewModel(
            validateTarget = {
                targetValidations += 1
                OperationResult.Success(it)
            },
            validateCache = { cacheValid },
            transferCached = { _, _, _, _ ->
                publishCalls += 1
                flowOf(TransferState.Failure(ErrorCode.NO_SPACE, "safe"))
            },
            dispatcher = dispatcher,
        )
        viewModel.acceptShare(share())
        advanceUntilIdle()
        viewModel.selectTarget(root("/target"))
        advanceUntilIdle()
        assertEquals(1, targetValidations)

        cacheValid = false
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.state.value is TransferUiState.RequiresReshare)
        assertEquals(0, publishCalls)
        assertEquals(1, targetValidations)
    }

    @Test
    fun `cancel during publish waits for its success and records exactly once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publishRelease = CompletableDeferred<Unit>()
        val recent = mutableListOf<DirectoryEntry>()
        val viewModel = readyViewModel(
            dispatcher = dispatcher,
            transferCached = { _, _, _, _ ->
                flow {
                    emit(TransferState.Publishing(name("a.txt"), 0))
                    publishRelease.await()
                    emit(TransferState.Success(file("/target/a.txt"), name("a.txt")))
                }
            },
            recordSaved = { recent += it },
        )

        viewModel.save()
        testScheduler.runCurrent()
        viewModel.cancel()
        assertTrue(viewModel.state.value is TransferUiState.Cancelling)

        publishRelease.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is TransferUiState.Success)
        assertEquals(listOf(file("/target/a.txt")), recent)
    }

    @Test
    fun `new share during publish is cached as queue and cannot hide active success`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publishRelease = CompletableDeferred<Unit>()
        val recent = mutableListOf<DirectoryEntry>()
        val viewModel = readyViewModel(
            dispatcher = dispatcher,
            transferCached = { _, _, _, _ ->
                flow {
                    emit(TransferState.Publishing(name("a.txt"), 0))
                    publishRelease.await()
                    emit(TransferState.Success(file("/target/a.txt"), name("a.txt")))
                }
            },
            recordSaved = { recent += it },
        )
        viewModel.save()
        testScheduler.runCurrent()

        viewModel.acceptShare(share("queued.pdf"))
        testScheduler.runCurrent()
        val reconciliation = viewModel.state.value as TransferUiState.Reconciliation
        assertEquals("a.txt", reconciliation.share.displayName)
        assertTrue(reconciliation.queuedPending)

        publishRelease.complete(Unit)
        advanceUntilIdle()

        val queued = viewModel.state.value as TransferUiState.Choosing
        assertEquals("queued.pdf", queued.share.displayName)
        assertEquals(listOf(file("/target/a.txt")), recent)
    }

    @Test
    fun `new share before first publish boundary replaces the old request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val boundaryRelease = CompletableDeferred<Unit>()
        var publishCalls = 0
        val viewModel = readyViewModel(
            dispatcher = dispatcher,
            transferCached = { _, _, _, _ ->
                flow {
                    emit(TransferState.Resolving)
                    boundaryRelease.await()
                    publishCalls += 1
                    emit(TransferState.Publishing(name("a.txt"), 0))
                }
            },
        )
        viewModel.save()
        testScheduler.runCurrent()

        viewModel.acceptShare(share("replacement.pdf"))
        advanceUntilIdle()

        assertEquals(
            "replacement.pdf",
            (viewModel.state.value as TransferUiState.Choosing).share.displayName,
        )
        boundaryRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, publishCalls)
    }

    @Test
    fun `queued share prevents another publish after already exists`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val collisionRelease = CompletableDeferred<Unit>()
        val attempts = mutableListOf<Int>()
        val viewModel = readyViewModel(
            dispatcher = dispatcher,
            transferCached = { _, _, _, mayContinue ->
                flow {
                    attempts += 0
                    emit(TransferState.Publishing(name("a.txt"), 0))
                    collisionRelease.await()
                    if (mayContinue()) {
                        attempts += 1
                        emit(TransferState.Publishing(name("a (1).txt"), 1))
                    } else {
                        emit(TransferState.Failure(ErrorCode.CANCELLED, "cancelled"))
                    }
                }
            },
        )
        viewModel.save()
        testScheduler.runCurrent()
        viewModel.acceptShare(share("queued.pdf"))
        testScheduler.runCurrent()

        collisionRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(0), attempts)
        assertEquals("queued.pdf", (viewModel.state.value as TransferUiState.Choosing).share.displayName)
    }

    @Test
    fun `retryable failure keeps old cache until user continues with queued share`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publishRelease = CompletableDeferred<Unit>()
        val cleaned = mutableListOf<String>()
        val viewModel = readyViewModel(
            dispatcher = dispatcher,
            transferCached = { _, _, _, _ ->
                flow {
                    emit(TransferState.Publishing(name("a.txt"), 0))
                    publishRelease.await()
                    emit(TransferState.Failure(ErrorCode.NO_SPACE, "safe"))
                }
            },
            cleanupIncoming = { cleaned += it.file.nameWithoutExtension; true },
        )
        viewModel.save()
        testScheduler.runCurrent()
        viewModel.acceptShare(share("queued.pdf"))
        testScheduler.runCurrent()
        publishRelease.complete(Unit)
        advanceUntilIdle()

        val failure = viewModel.state.value as TransferUiState.Failure
        assertEquals("a.txt", failure.share?.displayName)
        assertTrue(failure.retryable)
        assertTrue(failure.queuedPending)

        viewModel.continueWithQueued()
        advanceUntilIdle()

        assertEquals("queued.pdf", (viewModel.state.value as TransferUiState.Choosing).share.displayName)
        assertEquals(1, cleaned.size)
    }

    @Test
    fun `uncertain outcome retains cache until explicit acknowledgement`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var cleanupCalls = 0
        val viewModel = readyViewModel(
            dispatcher = dispatcher,
            transferCached = { _, _, _, _ ->
                flowOf(TransferState.Failure(ErrorCode.OUTCOME_UNCERTAIN, "safe"))
            },
            cleanupIncoming = { cleanupCalls += 1; true },
        )
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.state.value is TransferUiState.Uncertain)
        assertEquals(0, cleanupCalls)
        viewModel.acknowledgeUncertain()
        advanceUntilIdle()

        assertEquals(TransferUiState.Idle, viewModel.state.value)
        assertEquals(1, cleanupCalls)
    }

    @Test
    fun `process recreation restores redacted summary without Uri or cache capability`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handle = SavedStateHandle()
        viewModel(savedStateHandle = handle, dispatcher = dispatcher).acceptShare(
            share("重建.pdf", uri = "content://private.provider/secret-token"),
        )
        testScheduler.runCurrent()

        assertTrue(handle.values().none { it is Uri || it.toString().contains("content://") })
        val restored = viewModel(savedStateHandle = handle, dispatcher = dispatcher)

        val state = restored.state.value as TransferUiState.RequiresReshare
        assertEquals("重建.pdf", state.share?.displayName)
        assertTrue(state.message.contains("重新分享"))
    }

    @Test
    fun `intent entry accepts SEND and VIEW asynchronously and maps provider timeout`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parsed = mutableListOf<String?>()
        val viewModel = viewModel(
            parseShare = { intent ->
                parsed += intent.action
                if (intent.action == Intent.ACTION_VIEW) {
                    ShareIntentParseResult.Success(share("view.pdf"))
                } else {
                    ShareIntentParseResult.Failure(ShareIntentFailureReason.PROVIDER_TIMEOUT, "timeout")
                }
            },
            dispatcher = dispatcher,
        )
        viewModel.handleIntent(Intent(Intent.ACTION_MAIN))
        viewModel.handleIntent(Intent(Intent.ACTION_VIEW))
        advanceUntilIdle()
        assertEquals(listOf(Intent.ACTION_VIEW), parsed)
        assertEquals("view.pdf", (viewModel.state.value as TransferUiState.Choosing).share.displayName)

        viewModel.handleIntent(Intent(Intent.ACTION_SEND))
        advanceUntilIdle()
        val failure = viewModel.state.value as TransferUiState.Failure
        assertTrue(failure.retryable)
        assertFalse(failure.requiresReshare)
    }

    private suspend fun readyViewModel(
        dispatcher: TestDispatcher,
        transferCached: (CachedIncomingFile, OutputNameDraft, RootPath, () -> Boolean) -> Flow<TransferState>,
        cleanupIncoming: suspend (CachedIncomingFile) -> Boolean = { true },
        recordSaved: suspend (DirectoryEntry) -> Unit = {},
    ): TransferViewModel {
        val viewModel = viewModel(
            validateTarget = { OperationResult.Success(it) },
            transferCached = transferCached,
            cleanupIncoming = cleanupIncoming,
            recordSaved = recordSaved,
            dispatcher = dispatcher,
        )
        viewModel.acceptShare(share())
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectTarget(root("/target"))
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    private fun viewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        parseShare: suspend (Intent) -> ShareIntentParseResult = {
            ShareIntentParseResult.Failure(ShareIntentFailureReason.UNSUPPORTED_INTENT, "unused")
        },
        validateTarget: suspend (RootPath) -> OperationResult<RootPath> = { OperationResult.Success(it) },
        cacheIncoming: suspend (IncomingShare, (Long) -> Unit) -> IncomingFileCacheResult = { share, _ ->
            IncomingFileCacheResult.Success(cached(share.displayName))
        },
        validateCache: suspend (CachedIncomingFile) -> Boolean = { true },
        cleanupIncoming: suspend (CachedIncomingFile) -> Boolean = { true },
        transferCached: (
            CachedIncomingFile,
            OutputNameDraft,
            RootPath,
            () -> Boolean,
        ) -> Flow<TransferState> = { _, _, _, _ -> flowOf(TransferState.Failure(ErrorCode.NO_SPACE, "safe")) },
        recordSaved: suspend (DirectoryEntry) -> Unit = {},
        dispatcher: TestDispatcher,
    ) = TransferViewModel(
        savedStateHandle = savedStateHandle,
        parseShare = parseShare,
        validateTarget = validateTarget,
        cacheIncoming = cacheIncoming,
        validateCache = validateCache,
        cleanupIncoming = cleanupIncoming,
        transferCached = transferCached,
        recordSaved = recordSaved,
        workDispatcher = dispatcher,
    )

    private fun share(
        displayName: String = "a.txt",
        uri: String = "content://provider/token",
    ) = IncomingShare(Uri.parse(uri), displayName, 1L, "application/octet-stream")

    private fun cached(label: String): CachedIncomingFile {
        val root = kotlin.io.path.createTempDirectory("isaver-vm").toFile()
        val file = File(root, "incoming/123e4567-e89b-12d3-a456-426614174000.tmp")
        file.parentFile!!.mkdirs()
        file.writeText(label)
        return CachedIncomingFile(
            file = file,
            sizeBytes = file.length(),
            appCachePath = AppCachePath.fromIncomingCacheFile(root, file) { 1L to 2L }.getOrThrow(),
        )
    }

    private fun root(value: String) = RootPath.parse(value).getOrThrow()
    private fun name(value: String) = EntryName.parse(value).getOrThrow()

    private fun file(path: String) = DirectoryEntry(
        path = root(path),
        name = path.substringAfterLast('/'),
        type = EntryType.FILE,
        sizeBytes = 1L,
        modifiedAtEpochSeconds = 1L,
        readable = true,
        writable = true,
        symbolicLink = false,
    )

    private fun SavedStateHandle.values(): List<Any?> = keys().map { get<Any?>(it) }
}
