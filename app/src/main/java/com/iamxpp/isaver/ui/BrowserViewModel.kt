package com.iamxpp.isaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.root.DirectorySnapshot
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.archive.ArchiveRepository
import com.iamxpp.isaver.archive.ArchiveState
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootPathRiskPolicy
import com.iamxpp.isaver.export.ExternalFileGrant
import com.iamxpp.isaver.ui.files.FileEntrySorter
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.SortSpec
import com.iamxpp.isaver.transfer.OutputNameDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowserViewModel(
    private val rootFileSystem: RootFileSystem,
    private val ioDispatcher: CoroutineDispatcher,
    private val preferencesStore: BrowserPreferencesStore,
    private val archiveRepository: ArchiveRepository? = null,
    private val recordDirectoryAccess: suspend (RootPath, String) -> Unit = { _, _ -> },
    private val recordFileAccess: suspend (RootPath, String) -> Unit = { _, _ -> },
    private val snapshotCache: DirectorySnapshotCache = DirectorySnapshotCache(),
    private val sorter: (List<DirectoryEntry>, SortSpec) -> List<DirectoryEntry> = FileEntrySorter::sort,
    private val exportFile: suspend (DirectoryEntry) -> OperationResult<ExternalFileGrant> = {
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法打开文件")
    },
    private val shareFile: suspend (DirectoryEntry) -> OperationResult<ExternalFileGrant> = {
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法分享文件")
    },
    private val moveFile: suspend (DirectoryEntry, RootPath, RootPath) -> OperationResult<DirectoryEntry> = { _, _, _ ->
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法移动文件")
    },
    private val copyFile: suspend (DirectoryEntry, RootPath, RootPath) -> OperationResult<DirectoryEntry> = { _, _, _ ->
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法复制文件")
    },
    private val renameFile: suspend (DirectoryEntry, RootPath, String) -> OperationResult<DirectoryEntry> = { _, _, _ ->
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法重命名文件")
    },
    private val revokeExport: (ExternalFileGrant) -> Unit = {},
) : ViewModel() {
    private val initialPath = RootPath.parse(INITIAL_PATH).getOrThrow()
    private var selectedRootPath = initialPath
    private val stack = ArrayDeque<RootPath>()
    private val mutableState = MutableStateFlow(BrowserUiState(currentPath = initialPath))
    private var loadJob: Job? = null
    private var presentationJob: Job? = null
    private var createDirectoryJob: Job? = null
    private var openFileJob: Job? = null
    private var openFileGeneration = 0L
    private var shareFileJob: Job? = null
    private var shareFileGeneration = 0L
    private var moveFileJob: Job? = null
    private var selectionToRestore: BrowserMoveSelection? = null
    private var copyFileJob: Job? = null
    private var renameFileJob: Job? = null
    private var copySelectionToRestore: BrowserCopySelection? = null
    private var generation = 0L
    private var visibleCount = PAGE_SIZE
    private var presentedEntries: List<DirectoryEntry> = emptyList()

    val state: StateFlow<BrowserUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesStore.preferences.collect { preferences ->
                val sortChanged = preferences.sortSpec != mutableState.value.sortSpec
                if (sortChanged) resetPresentationWindow()
                mutableState.value = mutableState.value.copy(
                    displayMode = preferences.displayMode,
                    sortSpec = preferences.sortSpec,
                )
                if (sortChanged) refreshPresentation()
            }
        }
    }

    fun enterDirectory(entry: DirectoryEntry): Boolean {
        if (entry.type != EntryType.DIRECTORY) return false
        stack.addLast(mutableState.value.currentPath)
        load(entry.path)
        return true
    }

    fun openEntry(entry: DirectoryEntry) {
        if (mutableState.value.selectionMode) {
            selectEntry(entry)
            return
        }
        cancelExternalShare()
        val openRequest = cancelExternalOpen()
        when (entry.type) {
            EntryType.DIRECTORY -> enterDirectory(entry)
            EntryType.FILE -> when {
                !entry.readable || entry.symbolicLink -> mutableState.value = mutableState.value.copy(
                    fileOpenError = BrowserOperationError(ErrorCode.SOURCE_UNREADABLE, "无法读取来源文件"),
                    archiveToOpen = null,
                )
                isSupportedArchive(entry.name) -> mutableState.value = mutableState.value.copy(
                    archiveToOpen = entry,
                    fileInfo = null,
                    fileOpenError = null,
                )
                else -> openExternalFile(entry, openRequest)
            }
            EntryType.OTHER -> {
                mutableState.value = mutableState.value.copy(fileInfo = entry, archiveToOpen = null)
                recordSuccessfulFileAccess(entry)
            }
        }
    }

    fun selectEntry(entry: DirectoryEntry) {
        if (!entry.readable || entry.symbolicLink || entry.type == EntryType.OTHER) return
        val selected = mutableState.value.selectedEntries.toMutableSet()
        if (!selected.add(entry)) selected.remove(entry)
        mutableState.value = mutableState.value.copy(selectedEntries = selected)
    }

    fun toggleSelection(entry: DirectoryEntry) = selectEntry(entry)

    fun clearSelection() {
        cancelExternalShare()
        mutableState.value = mutableState.value.copy(selectedEntries = emptySet())
    }

    fun dismissFileInfo() {
        mutableState.value = mutableState.value.copy(fileInfo = null)
    }

    fun consumeArchiveOpen() {
        mutableState.value = mutableState.value.copy(archiveToOpen = null)
    }

    fun completeExternalOpen(grant: ExternalFileGrant, launched: Boolean) {
        if (mutableState.value.externalFileToOpen?.token != grant.token) return
        if (!launched) revokeExport(grant)
        mutableState.value = mutableState.value.copy(
            externalFileToOpen = null,
            fileOpenError = if (launched) null else BrowserOperationError(
                ErrorCode.COMMAND_FAILED,
                "没有可打开此文件的应用",
            ),
        )
    }

    fun dismissFileOpenError() {
        mutableState.value = mutableState.value.copy(fileOpenError = null)
    }

    fun shareEntry(entry: DirectoryEntry) {
        val request = cancelExternalShare()
        cancelExternalOpen()
        if (entry.type != EntryType.FILE || !entry.readable || entry.symbolicLink) {
            mutableState.value = mutableState.value.copy(
                fileShareError = BrowserOperationError(ErrorCode.SOURCE_UNREADABLE, "无法分享此文件"),
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            sharingFile = true,
            externalFileToShare = null,
            fileShareError = null,
        )
        shareFileJob = viewModelScope.launch {
            try {
                when (val result = shareFile(entry)) {
                    is OperationResult.Failure -> if (request == shareFileGeneration) {
                        mutableState.value = mutableState.value.copy(
                            sharingFile = false,
                            fileShareError = BrowserOperationError(result.code, result.userMessage),
                        )
                    }
                    is OperationResult.Success -> {
                        if (request != shareFileGeneration) {
                            revokeExport(result.value)
                            return@launch
                        }
                        mutableState.value = mutableState.value.copy(
                            sharingFile = false,
                            externalFileToShare = result.value,
                        )
                        recordSuccessfulFileAccess(entry)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (request == shareFileGeneration) {
                    mutableState.value = mutableState.value.copy(sharingFile = false)
                }
                throw cancelled
            } catch (_: Exception) {
                if (request == shareFileGeneration) {
                    mutableState.value = mutableState.value.copy(
                        sharingFile = false,
                        fileShareError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "无法分享文件"),
                    )
                }
            }
        }
    }

    fun completeExternalShare(grant: ExternalFileGrant, launched: Boolean) {
        if (mutableState.value.externalFileToShare?.token != grant.token) return
        if (!launched) revokeExport(grant)
        mutableState.value = mutableState.value.copy(
            externalFileToShare = null,
            selectedEntries = if (launched) emptySet() else mutableState.value.selectedEntries,
            fileShareError = if (launched) null else BrowserOperationError(
                ErrorCode.COMMAND_FAILED,
                "没有可接收此文件的应用",
            ),
        )
    }

    fun dismissFileShareError() {
        mutableState.value = mutableState.value.copy(fileShareError = null)
    }

    fun beginMove(entry: DirectoryEntry): Boolean {
        if (mutableState.value.movingFile || mutableState.value.copyingFile || mutableState.value.renamingFile) return false
        cancelExternalOpen()
        cancelExternalShare()
        if (
            entry.type != EntryType.FILE ||
            !entry.readable ||
            entry.symbolicLink ||
            RootPathRiskPolicy.isProtected(mutableState.value.currentPath)
        ) {
            mutableState.value = mutableState.value.copy(
                fileMoveError = BrowserOperationError(
                    ErrorCode.SOURCE_UNREADABLE,
                    "当前仅支持移动单个普通文件",
                ),
            )
            return false
        }
        mutableState.value = mutableState.value.copy(
            moveSelection = BrowserMoveSelection(entry, mutableState.value.currentPath),
            movedOutput = null,
            fileMoveError = null,
        )
        return true
    }

    fun moveTo(targetDirectory: RootPath) {
        val selection = mutableState.value.moveSelection ?: return
        if (mutableState.value.movingFile) return
        if (targetDirectory == selection.sourceDirectory) {
            mutableState.value = mutableState.value.copy(
                fileMoveError = BrowserOperationError(ErrorCode.ALREADY_EXISTS, "文件已在当前目录"),
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            movingFile = true,
            movedOutput = null,
            fileMoveError = null,
        )
        moveFileJob = viewModelScope.launch {
            try {
                when (
                    val result = withContext(ioDispatcher) {
                        moveFile(selection.entry, selection.sourceDirectory, targetDirectory)
                    }
                ) {
                    is OperationResult.Failure -> mutableState.value = mutableState.value.copy(
                        movingFile = false,
                        fileMoveError = BrowserOperationError(result.code, result.userMessage),
                    )
                    is OperationResult.Success -> {
                        mutableState.value = mutableState.value.copy(
                            selectedEntries = emptySet(),
                            moveSelection = null,
                            movingFile = false,
                            movedOutput = result.value,
                            fileMoveError = null,
                        )
                        recordSuccessfulFileAccess(result.value)
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(movingFile = false)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    movingFile = false,
                    fileMoveError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "无法移动文件"),
                )
            }
        }
    }

    fun cancelMove(restoreSelection: Boolean = false): Boolean {
        val selection = mutableState.value.moveSelection ?: return true
        if (mutableState.value.movingFile) return false
        if (restoreSelection) selectionToRestore = selection
        mutableState.value = mutableState.value.copy(
            moveSelection = null,
            movedOutput = null,
            fileMoveError = null,
        )
        return true
    }

    fun consumeMovedOutput() {
        mutableState.value = mutableState.value.copy(movedOutput = null)
    }

    fun dismissFileMoveError() {
        mutableState.value = mutableState.value.copy(fileMoveError = null)
    }

    fun beginCopy(entry: DirectoryEntry): Boolean {
        if (mutableState.value.copyingFile || mutableState.value.movingFile || mutableState.value.renamingFile) return false
        cancelExternalOpen()
        cancelExternalShare()
        if (
            entry.type != EntryType.FILE ||
            !entry.readable ||
            entry.symbolicLink
        ) {
            mutableState.value = mutableState.value.copy(
                fileCopyError = BrowserOperationError(
                    ErrorCode.SOURCE_UNREADABLE,
                    "当前仅支持复制单个普通文件",
                ),
            )
            return false
        }
        mutableState.value = mutableState.value.copy(
            copySelection = BrowserCopySelection(entry, mutableState.value.currentPath),
            copiedOutput = null,
            fileCopyError = null,
        )
        return true
    }

    fun copyTo(targetDirectory: RootPath) {
        val selection = mutableState.value.copySelection ?: return
        if (mutableState.value.copyingFile) return
        if (targetDirectory == selection.sourceDirectory) {
            mutableState.value = mutableState.value.copy(
                fileCopyError = BrowserOperationError(ErrorCode.ALREADY_EXISTS, "文件已在当前目录"),
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            copyingFile = true,
            copiedOutput = null,
            fileCopyError = null,
        )
        copyFileJob = viewModelScope.launch {
            try {
                when (
                    val result = withContext(ioDispatcher) {
                        copyFile(selection.entry, selection.sourceDirectory, targetDirectory)
                    }
                ) {
                    is OperationResult.Failure -> mutableState.value = mutableState.value.copy(
                        copyingFile = false,
                        fileCopyError = BrowserOperationError(result.code, result.userMessage),
                    )
                    is OperationResult.Success -> {
                        mutableState.value = mutableState.value.copy(
                            selectedEntries = emptySet(),
                            copySelection = null,
                            copyingFile = false,
                            copiedOutput = result.value,
                            fileCopyError = null,
                        )
                        recordSuccessfulFileAccess(result.value)
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(copyingFile = false)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    copyingFile = false,
                    fileCopyError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "无法复制文件"),
                )
            }
        }
    }

    fun cancelCopy(restoreSelection: Boolean = false): Boolean {
        val selection = mutableState.value.copySelection ?: return true
        if (mutableState.value.copyingFile) return false
        if (restoreSelection) copySelectionToRestore = selection
        mutableState.value = mutableState.value.copy(
            copySelection = null,
            copiedOutput = null,
            fileCopyError = null,
        )
        return true
    }

    fun consumeCopiedOutput() {
        mutableState.value = mutableState.value.copy(copiedOutput = null)
    }

    fun dismissFileCopyError() {
        mutableState.value = mutableState.value.copy(fileCopyError = null)
    }

    fun renameEntry(entry: DirectoryEntry, newName: String) {
        if (renameFileJob?.isActive == true) return
        val parent = mutableState.value.currentPath
        mutableState.value = mutableState.value.copy(
            renamingFile = true,
            renamedOutput = null,
            fileRenameError = null,
        )
        renameFileJob = viewModelScope.launch {
            try {
                when (val result = withContext(ioDispatcher) { renameFile(entry, parent, newName) }) {
                    is OperationResult.Failure -> mutableState.value = mutableState.value.copy(
                        renamingFile = false,
                        fileRenameError = BrowserOperationError(result.code, result.userMessage),
                    )
                    is OperationResult.Success -> {
                        mutableState.value = mutableState.value.copy(
                            selectedEntries = emptySet(),
                            renamingFile = false,
                            renamedOutput = result.value,
                            fileRenameError = null,
                        )
                        load(parent, result.value.path)
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(renamingFile = false)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    renamingFile = false,
                    fileRenameError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "无法重命名文件"),
                )
            }
        }
    }

    fun consumeRenamedOutput() {
        mutableState.value = mutableState.value.copy(renamedOutput = null)
    }

    fun dismissFileRenameError() {
        mutableState.value = mutableState.value.copy(fileRenameError = null)
    }

    fun compress(outputName: String) {
        val repository = archiveRepository ?: return
        val current = mutableState.value
        if (current.selectedEntries.isEmpty() || current.compressing) return
        val draft = OutputNameDraft.fromDisplayName(outputName)
        mutableState.value = current.copy(compressing = true, compressionMessage = null)
        viewModelScope.launch {
            try {
                repository.createZip(
                    sources = current.selectedEntries.toList(),
                    targetDirectory = current.currentPath,
                    outputName = draft,
                ).collect { state ->
                    when (state) {
                        is ArchiveState.Success -> mutableState.value = mutableState.value.copy(
                            compressing = false,
                            compressionMessage = "压缩完成",
                            selectedEntries = emptySet(),
                        )
                        is ArchiveState.Failure -> mutableState.value = mutableState.value.copy(
                            compressing = false,
                            compressionMessage = state.message,
                        )
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(compressing = false)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    compressing = false,
                    compressionMessage = "压缩失败",
                )
            }
        }
    }

    fun clearCompressionMessage() {
        mutableState.value = mutableState.value.copy(compressionMessage = null)
    }

    fun openRoot(path: RootPath, title: String, recordAccess: Boolean = true) {
        stack.clear()
        selectedRootPath = path
        mutableState.value = mutableState.value.copy(rootTitle = title)
        load(path, recordAccess = recordAccess)
    }

    fun back(): BrowserBackResult {
        val previous = stack.removeLastOrNull() ?: return BrowserBackResult.RETURN_HOME
        load(previous)
        return BrowserBackResult.NAVIGATED
    }

    fun retry() = load(mutableState.value.currentPath)

    fun setDisplayMode(displayMode: DisplayMode) {
        savePresentationPreference { preferencesStore.setDisplayMode(displayMode) }
    }

    fun setSort(sortSpec: SortSpec) {
        savePresentationPreference { preferencesStore.setSort(sortSpec) }
    }

    fun setSearchQuery(searchQuery: String) {
        resetPresentationWindow()
        mutableState.value = mutableState.value.copy(searchQuery = searchQuery)
        refreshPresentation()
    }

    fun createDirectory(rawName: String) {
        if (createDirectoryJob?.isActive == true) return
        if (!mutableState.value.canCreateDirectory) {
            mutableState.value = mutableState.value.copy(
                createDirectoryError = BrowserOperationError(ErrorCode.NOT_WRITABLE, "目录不可写"),
            )
            return
        }
        val name = FolderName.parse(rawName).getOrElse {
            mutableState.value = mutableState.value.copy(
                createDirectoryError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "文件夹名称无效"),
            )
            return
        }
        val parent = mutableState.value.currentPath
        mutableState.value = mutableState.value.copy(
            creatingDirectory = true,
            createDirectoryError = null,
            locationTarget = null,
        )
        createDirectoryJob = viewModelScope.launch {
            try {
                when (val result = withContext(ioDispatcher) { rootFileSystem.createDirectory(parent, name) }) {
                    is OperationResult.Failure -> if (mutableState.value.currentPath == parent) {
                        mutableState.value = mutableState.value.copy(
                            creatingDirectory = false,
                            createDirectoryError = BrowserOperationError(result.code, result.userMessage),
                        )
                    }
                    is OperationResult.Success -> if (mutableState.value.currentPath == parent) {
                        load(parent, result.value.path)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (mutableState.value.currentPath == parent) {
                    mutableState.value = mutableState.value.copy(creatingDirectory = false)
                }
                throw cancelled
            } catch (_: Exception) {
                if (mutableState.value.currentPath == parent) {
                    mutableState.value = mutableState.value.copy(
                        creatingDirectory = false,
                        createDirectoryError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "新建文件夹失败"),
                    )
                }
            }
        }
    }

    fun loadMore() {
        val current = mutableState.value
        if (!current.hasMore) return
        visibleCount = (visibleCount + PAGE_SIZE).coerceAtMost(current.totalCount)
        mutableState.value = current.copy(
            entries = presentedEntries.take(visibleCount),
            hasMore = visibleCount < current.totalCount,
        )
    }

    private fun load(
        path: RootPath,
        locationTarget: RootPath? = null,
        recordAccess: Boolean = true,
    ) {
        val request = ++generation
        loadJob?.cancel()
        cancelExternalOpen()
        cancelExternalShare()
        resetPresentationWindow()
        val cached = snapshotCache.get(path)
        val cachedSnapshot = cached?.snapshot
        val requestedPresentationKey = DirectoryPresentationKey(
            mutableState.value.sortSpec,
            mutableState.value.searchQuery,
        )
        val cachedEntries = cached
            ?.takeIf { it.presentationKey == requestedPresentationKey }
            ?.presentedEntries
            .orEmpty()
        if (cached != null) presentedEntries = cachedEntries
        val previousState = mutableState.value
        val restoredSelection = selectionToRestore
            ?.takeIf { it.sourceDirectory == path }
            ?.also { selectionToRestore = null }
        val restoredCopySelection = copySelectionToRestore
            ?.takeIf { it.sourceDirectory == path }
            ?.also { copySelectionToRestore = null }
        mutableState.value = BrowserUiState(
            currentPath = path,
            rootTitle = previousState.rootTitle,
            title = displayTitle(path),
            allEntries = cachedSnapshot?.entries.orEmpty(),
            entries = cachedEntries.take(PAGE_SIZE),
            totalCount = cachedEntries.size,
            refreshing = true,
            canGoBack = stack.isNotEmpty(),
            hasMore = cachedEntries.size > PAGE_SIZE,
            canCreateDirectory = cachedSnapshot?.parentWritable == true && !RootPathRiskPolicy.isProtected(path),
            locationTarget = locationTarget,
            displayMode = previousState.displayMode,
            sortSpec = previousState.sortSpec,
            searchQuery = previousState.searchQuery,
            selectedEntries = (restoredSelection?.entry ?: restoredCopySelection?.entry)
                ?.let(::setOf)
                .orEmpty(),
            moveSelection = previousState.moveSelection,
            movingFile = previousState.movingFile,
            movedOutput = previousState.movedOutput,
            fileMoveError = previousState.fileMoveError,
            copySelection = previousState.copySelection,
            copyingFile = previousState.copyingFile,
            copiedOutput = previousState.copiedOutput,
            fileCopyError = previousState.fileCopyError,
            renamingFile = previousState.renamingFile,
            renamedOutput = previousState.renamedOutput,
            fileRenameError = previousState.fileRenameError,
            compressionMessage = null,
        )
        val cachedPresentationJob = if (cachedSnapshot != null && cachedEntries.isEmpty()) {
            refreshPresentation()
        } else {
            null
        }
        val loadingIndicatorJob = if (cachedSnapshot == null) {
            viewModelScope.launch {
                delay(LOADING_INDICATOR_DELAY_MILLIS)
                if (request == generation) {
                    mutableState.value = mutableState.value.copy(loading = true)
                }
            }
        } else {
            null
        }
        loadJob = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { rootFileSystem.readDirectory(path) }
                when (result) {
                    is OperationResult.Failure -> if (request == generation) {
                        cachedPresentationJob?.join()
                        if (request == generation) {
                            mutableState.value = if (cachedSnapshot == null) {
                                mutableState.value.copy(
                                    loading = false,
                                    refreshing = false,
                                    errorMessage = result.userMessage,
                                    canCreateDirectory = false,
                                )
                            } else {
                                mutableState.value.copy(loading = false, refreshing = false)
                            }
                        }
                    }
                    is OperationResult.Success -> publishSnapshot(path, result.value, request, recordAccess)
                }
            } catch (cancelled: CancellationException) {
                if (request == generation) {
                    mutableState.value = mutableState.value.copy(loading = false, refreshing = false)
                }
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    cachedPresentationJob?.join()
                    if (request == generation) {
                        mutableState.value = if (cachedSnapshot == null) {
                            mutableState.value.copy(
                                loading = false,
                                refreshing = false,
                                errorMessage = "无法读取目录",
                            )
                        } else {
                            mutableState.value.copy(loading = false, refreshing = false)
                        }
                    }
                }
            } finally {
                loadingIndicatorJob?.cancel()
            }
        }
    }

    private suspend fun publishSnapshot(
        path: RootPath,
        snapshot: DirectorySnapshot,
        request: Long,
        recordAccess: Boolean,
    ) {
        while (request == generation) {
            val presentationState = mutableState.value
            val sortSpec = presentationState.sortSpec
            val searchQuery = presentationState.searchQuery
            val derived = withContext(ioDispatcher) {
                derivePresentation(snapshot.entries, searchQuery, sortSpec)
            }
            if (request != generation) return
            val current = mutableState.value
            if (current.sortSpec != sortSpec || current.searchQuery != searchQuery) continue

            visibleCount = PAGE_SIZE
            presentedEntries = derived
            snapshotCache.put(
                path,
                snapshot,
                derived,
                DirectoryPresentationKey(sortSpec, searchQuery),
            )
            mutableState.value = current.copy(
                allEntries = snapshot.entries,
                entries = derived.take(visibleCount),
                totalCount = derived.size,
                loading = false,
                refreshing = false,
                canCreateDirectory = snapshot.parentWritable && !RootPathRiskPolicy.isProtected(path),
                hasMore = visibleCount < derived.size,
            )
            if (recordAccess) {
                recordSuccessfulDirectoryAccess(path, mutableState.value.title)
            }
            return
        }
    }

    private fun recordSuccessfulDirectoryAccess(path: RootPath, title: String) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val canonical = rootFileSystem.canonicalize(path)
                if (canonical is OperationResult.Success) {
                    recordDirectoryAccess(canonical.value, title)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
        }
    }

    private fun recordSuccessfulFileAccess(entry: DirectoryEntry) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val canonical = rootFileSystem.canonicalize(entry.path)
                if (canonical is OperationResult.Success) {
                    recordFileAccess(canonical.value, entry.name)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
        }
    }

    private fun openExternalFile(entry: DirectoryEntry, request: Long) {
        mutableState.value = mutableState.value.copy(
            openingFile = true,
            externalFileToOpen = null,
            fileOpenError = null,
            fileInfo = null,
            archiveToOpen = null,
        )
        openFileJob = viewModelScope.launch {
            try {
                when (val result = exportFile(entry)) {
                    is OperationResult.Failure -> if (request == openFileGeneration) {
                        mutableState.value = mutableState.value.copy(
                            openingFile = false,
                            fileOpenError = BrowserOperationError(result.code, result.userMessage),
                        )
                    }
                    is OperationResult.Success -> {
                        if (request != openFileGeneration) {
                            revokeExport(result.value)
                            return@launch
                        }
                        mutableState.value = mutableState.value.copy(
                            openingFile = false,
                            externalFileToOpen = result.value,
                        )
                        recordSuccessfulFileAccess(entry)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (request == openFileGeneration) {
                    mutableState.value = mutableState.value.copy(openingFile = false)
                }
                throw cancelled
            } catch (_: Exception) {
                if (request == openFileGeneration) {
                    mutableState.value = mutableState.value.copy(
                        openingFile = false,
                        fileOpenError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "无法打开文件"),
                    )
                }
            }
        }
    }

    private fun cancelExternalOpen(): Long {
        openFileGeneration += 1L
        openFileJob?.cancel()
        mutableState.value.externalFileToOpen?.let(revokeExport)
        mutableState.value = mutableState.value.copy(
            openingFile = false,
            externalFileToOpen = null,
        )
        return openFileGeneration
    }

    private fun cancelExternalShare(): Long {
        shareFileGeneration += 1L
        shareFileJob?.cancel()
        mutableState.value.externalFileToShare?.let(revokeExport)
        mutableState.value = mutableState.value.copy(
            sharingFile = false,
            externalFileToShare = null,
        )
        return shareFileGeneration
    }

    private fun displayTitle(path: RootPath): String = when {
        path == selectedRootPath && path.value == "/" -> "/"
        path == selectedRootPath -> mutableState.value.rootTitle
        else -> path.value.substringAfterLast('/').ifEmpty { "/" }
    }

    private fun resetPresentationWindow() {
        visibleCount = PAGE_SIZE
        presentedEntries = emptyList()
        mutableState.value = mutableState.value.copy(
            entries = emptyList(),
            totalCount = 0,
            hasMore = false,
        )
    }

    private fun savePresentationPreference(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
                mutableState.value = mutableState.value.copy(presentationError = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(presentationError = "无法保存显示设置")
            }
        }
    }

    private fun refreshPresentation(): Job {
        val current = mutableState.value
        presentationJob?.cancel()
        return viewModelScope.launch {
            val derived = withContext(ioDispatcher) {
                derivePresentation(current.allEntries, current.searchQuery, current.sortSpec)
            }
            if (
                mutableState.value.allEntries == current.allEntries &&
                mutableState.value.sortSpec == current.sortSpec &&
                mutableState.value.searchQuery == current.searchQuery
            ) {
                presentedEntries = derived
                mutableState.value = mutableState.value.copy(
                    entries = derived.take(visibleCount),
                    totalCount = derived.size,
                    hasMore = visibleCount < derived.size,
                )
            }
        }.also { presentationJob = it }
    }

    private fun derivePresentation(
        entries: List<DirectoryEntry>,
        searchQuery: String,
        sortSpec: SortSpec,
    ): List<DirectoryEntry> = sorter(
        entries.filter { entry ->
            searchQuery.isEmpty() || entry.name.contains(searchQuery, ignoreCase = true)
        },
        sortSpec,
    )

    internal companion object {
        const val INITIAL_PATH = "/storage/emulated/0"
        const val PAGE_SIZE = 200
        const val LOADING_INDICATOR_DELAY_MILLIS = 120L
    }
}

private fun isSupportedArchive(name: String): Boolean {
    val normalized = name.lowercase()
    return normalized.endsWith(".zip") || normalized.endsWith(".tar") ||
        normalized.endsWith(".tar.gz") || normalized.endsWith(".tgz") ||
        normalized.endsWith(".7z") || normalized.endsWith(".rar")
}

enum class BrowserBackResult {
    NAVIGATED,
    RETURN_HOME,
}
