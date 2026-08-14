package com.isaver.filemanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isaver.filemanager.bookmarks.Bookmark
import com.isaver.filemanager.bookmarks.BookmarkRepository
import com.isaver.filemanager.data.local.BrowserPreferencesStore
import com.isaver.filemanager.data.local.BrowserSession
import com.isaver.filemanager.data.local.BrowserSessionStore
import com.isaver.filemanager.data.root.DirectorySnapshot
import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.archive.ArchiveRepository
import com.isaver.filemanager.archive.ArchiveFormat
import com.isaver.filemanager.archive.ArchiveState
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryName
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.FolderName
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.domain.RootEntryIdentity
import com.isaver.filemanager.domain.RootPathRiskPolicy
import com.isaver.filemanager.export.ExternalFileGrant
import com.isaver.filemanager.fileops.ConflictAction
import com.isaver.filemanager.fileops.BatchRenameExecutor
import com.isaver.filemanager.fileops.BatchRenamePlanner
import com.isaver.filemanager.fileops.BatchRenameRule
import com.isaver.filemanager.fileops.ChecksumAlgorithm
import com.isaver.filemanager.fileops.FileChecksumRepository
import com.isaver.filemanager.fileops.FilePermissionRepository
import com.isaver.filemanager.fileops.FilePermissions
import com.isaver.filemanager.search.LocalSearchCriteria
import com.isaver.filemanager.search.LocalSearchProgress
import com.isaver.filemanager.search.LocalSearchRepository
import com.isaver.filemanager.tasks.OperationTaskState
import com.isaver.filemanager.tasks.OperationTaskStore
import com.isaver.filemanager.tasks.OperationTaskType
import com.isaver.filemanager.trash.TrashItem
import com.isaver.filemanager.trash.TrashRepository
import com.isaver.filemanager.trash.RestoreConflictAction
import com.isaver.filemanager.ui.files.FileEntrySorter
import com.isaver.filemanager.ui.files.DisplayMode
import com.isaver.filemanager.ui.files.SortSpec
import com.isaver.filemanager.transfer.OutputNameDraft
import com.isaver.filemanager.preview.RootPreviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

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
    private val shareDirectory: (suspend (List<DirectoryEntry>) -> OperationResult<ExternalFileGrant>)? = null,
    private val moveFile: suspend (DirectoryEntry, RootPath, RootPath, ConflictAction) -> OperationResult<DirectoryEntry> = { _, _, _, _ ->
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法移动文件")
    },
    private val copyFile: suspend (DirectoryEntry, RootPath, RootPath, ConflictAction) -> OperationResult<DirectoryEntry> = { _, _, _, _ ->
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法复制文件")
    },
    private val renameFile: suspend (DirectoryEntry, RootPath, String) -> OperationResult<DirectoryEntry> = { _, _, _ ->
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法重命名文件")
    },
    private val revokeExport: (ExternalFileGrant) -> Unit = {},
    private val operationTaskStore: OperationTaskStore? = null,
    private val trashRepository: TrashRepository? = null,
    private val checksumFile: suspend (DirectoryEntry) -> OperationResult<String> = {
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法计算校验和")
    },
    private val checksumFileByAlgorithm: suspend (DirectoryEntry, ChecksumAlgorithm) -> OperationResult<String> = { entry, algorithm ->
        if (algorithm == ChecksumAlgorithm.SHA256) checksumFile(entry)
        else OperationResult.Failure(ErrorCode.COMMAND_FAILED, "不支持此校验算法")
    },
    private val bookmarkRepository: BookmarkRepository? = null,
    private val browserSessionStore: BrowserSessionStore? = null,
    private val localSearchRepository: LocalSearchRepository = LocalSearchRepository(rootFileSystem),
    private val previewRepository: RootPreviewRepository = RootPreviewRepository(rootFileSystem),
    private val relocateVirtualReferences: suspend (RootEntryIdentity, DirectoryEntry) -> Unit = { _, _ -> },
) : ViewModel() {
    private val initialPath = RootPath.parse(INITIAL_PATH).getOrThrow()
    private var selectedRootPath = initialPath
    private val stack = ArrayDeque<RootPath>()
    private val forwardStack = ArrayDeque<RootPath>()
    private val mutableState = MutableStateFlow(BrowserUiState(currentPath = initialPath))
    private var loadJob: Job? = null
    private var presentationJob: Job? = null
    private var createDirectoryJob: Job? = null
    private var createFileJob: Job? = null
    private var openFileJob: Job? = null
    private var openFileGeneration = 0L
    private var shareFileJob: Job? = null
    private var shareFileGeneration = 0L
    private var moveFileJob: Job? = null
    private var selectionToRestore: BrowserMoveSelection? = null
    private var copyFileJob: Job? = null
    private val taskPaused = MutableStateFlow(false)
    private var activeTaskId: String? = null
    private var activeTaskCompletedBytes = 0L
    private var renameFileJob: Job? = null
    private var checksumJob: Job? = null
    private var metadataJob: Job? = null
    private var permissionJob: Job? = null
    private var refreshAfterFileInfo = false
    private val permissionRepository = FilePermissionRepository(rootFileSystem)
    private var deepSearchJob: Job? = null
    private var previewJob: Job? = null
    private var sessionSaveJob: Job? = null
    private val batchRenamePlanner = BatchRenamePlanner()
    private val batchRenameExecutor = BatchRenameExecutor(renameFile)
    private var copySelectionToRestore: BrowserCopySelection? = null
    private var pendingConflict: PendingConflict? = null
    private var generation = 0L
    private var visibleCount = PAGE_SIZE
    private var presentedEntries: List<DirectoryEntry> = emptyList()
    private var sessionRestoreAttempted = false

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
        bookmarkRepository?.let { repository ->
            viewModelScope.launch {
                repository.bookmarks.collect { bookmarks ->
                    mutableState.value = mutableState.value.copy(
                        bookmarks = bookmarks,
                        currentPathBookmarked = bookmarks.any { it.path == mutableState.value.currentPath },
                    )
                }
            }
        }
    }

    fun enterDirectory(entry: DirectoryEntry): Boolean {
        if (entry.type != EntryType.DIRECTORY) return false
        stack.addLast(mutableState.value.currentPath)
        forwardStack.clear()
        load(entry.path)
        return true
    }

    fun openEntry(entry: DirectoryEntry) {
        if (mutableState.value.selectionMode) {
            selectEntry(entry)
            return
        }
        operationTaskStore?.let { store ->
            viewModelScope.launch {
                store.tasks.collect { tasks -> mutableState.value = mutableState.value.copy(operationTasks = tasks) }
            }
        }
        trashRepository?.let { repository ->
            viewModelScope.launch {
                repository.items.collect { items -> mutableState.value = mutableState.value.copy(trashItems = items) }
            }
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
                previewRepository.supports(entry.name) -> previewEntry(entry)
                else -> openExternalFile(entry, openRequest)
            }
            EntryType.OTHER -> {
                mutableState.value = mutableState.value.copy(fileInfo = entry, archiveToOpen = null)
                recordSuccessfulFileAccess(entry)
            }
        }
    }

    fun openWith(entry: DirectoryEntry) {
        if (entry.type != EntryType.FILE || !entry.readable || entry.symbolicLink) {
            mutableState.value = mutableState.value.copy(
                fileOpenError = BrowserOperationError(ErrorCode.SOURCE_UNREADABLE, "无法读取来源文件"),
            )
            return
        }
        cancelExternalShare()
        val openRequest = cancelExternalOpen()
        openExternalFile(entry, openRequest, chooser = true)
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

    fun selectAllVisible() {
        val selectable = presentedEntries.filter(::isSelectable)
        mutableState.value = mutableState.value.copy(
            selectedEntries = mutableState.value.selectedEntries + selectable,
        )
    }

    fun invertVisibleSelection() {
        val selectable = presentedEntries.filter(::isSelectable)
        val selected = mutableState.value.selectedEntries.toMutableSet()
        selectable.forEach { entry ->
            if (!selected.add(entry)) selected.remove(entry)
        }
        mutableState.value = mutableState.value.copy(selectedEntries = selected)
    }

    fun selectSameType() {
        val selectedTypes = mutableState.value.selectedEntries.map { it.type }.distinct()
        if (selectedTypes.size != 1) return
        val sameType = presentedEntries.filter { it.type == selectedTypes.single() && isSelectable(it) }
        mutableState.value = mutableState.value.copy(
            selectedEntries = mutableState.value.selectedEntries + sameType,
        )
    }

    private fun isSelectable(entry: DirectoryEntry): Boolean =
        entry.readable && !entry.symbolicLink && entry.type != EntryType.OTHER

    fun dismissFileInfo() {
        if (mutableState.value.permissionRunning) return
        checksumJob?.cancel()
        metadataJob?.cancel()
        permissionJob?.cancel()
        mutableState.value = mutableState.value.copy(
            fileInfo = null, fileMetadata = null, fileMetadataLoading = false, fileMetadataError = null,
            permissionRunning = false, permissionError = null, permissionConfirmation = null,
            checksumRunning = false, checksumValue = null, checksumError = null,
            checksumAlgorithm = ChecksumAlgorithm.SHA256,
        )
        if (refreshAfterFileInfo) {
            refreshAfterFileInfo = false
            load(mutableState.value.currentPath, recordAccess = false)
        }
    }

    fun showFileInfo(entry: DirectoryEntry) {
        checksumJob?.cancel()
        metadataJob?.cancel()
        mutableState.value = mutableState.value.copy(
            fileInfo = entry, fileMetadata = null, fileMetadataLoading = true, fileMetadataError = null,
            permissionRunning = false, permissionError = null, permissionConfirmation = null,
            checksumRunning = false, checksumValue = null, checksumError = null,
            checksumAlgorithm = ChecksumAlgorithm.SHA256,
        )
        metadataJob = viewModelScope.launch {
            val metadata = withContext(ioDispatcher) { rootFileSystem.metadata(entry.path) }
            if (mutableState.value.fileInfo?.path != entry.path) return@launch
            mutableState.value = when (metadata) {
                is OperationResult.Success -> when (
                    val identity = withContext(ioDispatcher) { rootFileSystem.identity(entry.path) }
                ) {
                    is OperationResult.Success -> if (
                        metadata.value.device == identity.value.device &&
                        metadata.value.inode == identity.value.inode
                    ) {
                        mutableState.value.copy(
                            fileMetadata = metadata.value,
                            fileMetadataLoading = false,
                            fileMetadataError = null,
                        )
                    } else {
                        changedMetadataState()
                    }
                    is OperationResult.Failure -> mutableState.value.copy(
                        fileMetadataLoading = false,
                        fileMetadataError = identity.userMessage,
                    )
                }
                is OperationResult.Failure -> mutableState.value.copy(
                    fileMetadataLoading = false,
                    fileMetadataError = metadata.userMessage,
                )
            }
        }
    }

    fun changePermissions(permissions: FilePermissions, confirmed: Boolean = false) {
        val state = mutableState.value
        val entry = state.fileInfo ?: return
        val metadata = state.fileMetadata ?: return
        if (state.permissionRunning) return
        if (com.isaver.filemanager.fileops.PermissionRiskPolicy.requiresConfirmation(entry.path, permissions) && !confirmed) {
            mutableState.value = state.copy(permissionConfirmation = permissions, permissionError = null)
            return
        }
        mutableState.value = state.copy(
            permissionRunning = true,
            permissionError = null,
            permissionConfirmation = null,
        )
        permissionJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                permissionRepository.change(entry, state.currentPath, metadata, permissions, confirmed)
            }
            if (mutableState.value.fileInfo?.path != entry.path) return@launch
            mutableState.value = when (result) {
                is OperationResult.Success -> {
                    refreshAfterFileInfo = true
                    mutableState.value.copy(
                        fileMetadata = result.value,
                        permissionRunning = false,
                        permissionError = null,
                        permissionConfirmation = null,
                    )
                }
                is OperationResult.Failure -> mutableState.value.copy(
                    permissionRunning = false,
                    permissionError = BrowserOperationError(result.code, result.userMessage),
                    permissionConfirmation = null,
                )
            }
        }
    }

    fun confirmPermissionChange() {
        mutableState.value.permissionConfirmation?.let { changePermissions(it, confirmed = true) }
    }

    fun dismissPermissionConfirmation() {
        mutableState.value = mutableState.value.copy(permissionConfirmation = null)
    }

    private fun changedMetadataState() = mutableState.value.copy(
        fileMetadata = null,
        fileMetadataLoading = false,
        fileMetadataError = "文件已变化，请刷新核对",
    )

    fun calculateSha256() {
        calculateChecksum(ChecksumAlgorithm.SHA256)
    }

    fun calculateSelectedChecksum() {
        calculateChecksum()
    }

    fun setChecksumAlgorithm(algorithm: ChecksumAlgorithm) {
        if (mutableState.value.checksumRunning) return
        mutableState.value = mutableState.value.copy(
            checksumAlgorithm = algorithm,
            checksumValue = null,
            checksumError = null,
        )
    }

    fun calculateChecksum(algorithm: ChecksumAlgorithm = mutableState.value.checksumAlgorithm) {
        val entry = mutableState.value.fileInfo ?: return
        if (entry.type != EntryType.FILE || mutableState.value.checksumRunning) return
        mutableState.value = mutableState.value.copy(
            checksumAlgorithm = algorithm,
            checksumRunning = true, checksumValue = null, checksumError = null,
        )
        checksumJob = viewModelScope.launch {
            val taskId = operationTaskStore?.start(OperationTaskType.CHECKSUM, 1, entry.sizeBytes)
            updateTask(taskId, OperationTaskState.RUNNING, 0)
            try {
                when (val result = withContext(ioDispatcher) { checksumFileByAlgorithm(entry, algorithm) }) {
                    is OperationResult.Success -> {
                        if (mutableState.value.fileInfo?.path == entry.path) {
                            mutableState.value = mutableState.value.copy(
                                checksumRunning = false, checksumValue = result.value, checksumError = null,
                            )
                        }
                        updateTask(
                            taskId, OperationTaskState.SUCCESS, 1,
                            completedBytes = entry.sizeBytes ?: 0,
                        )
                    }
                    is OperationResult.Failure -> {
                        if (mutableState.value.fileInfo?.path == entry.path) {
                            mutableState.value = mutableState.value.copy(
                                checksumRunning = false,
                                checksumError = BrowserOperationError(result.code, result.userMessage),
                            )
                        }
                        finishFailedTask(taskId, 0, 1, result)
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    updateTask(taskId, OperationTaskState.CANCELLED, 0, message = "校验已取消")
                }
                throw cancelled
            }
        }
    }

    fun consumeArchiveOpen() {
        mutableState.value = mutableState.value.copy(archiveToOpen = null)
    }

    fun completeExternalOpen(grant: ExternalFileGrant, launched: Boolean) {
        if (mutableState.value.externalFileToOpen?.token != grant.token) return
        if (!launched) revokeExport(grant)
        mutableState.value = mutableState.value.copy(
            externalFileToOpen = null,
            externalOpenChooser = false,
            fileOpenError = if (launched) null else BrowserOperationError(
                ErrorCode.COMMAND_FAILED,
                "没有可打开此文件的应用",
            ),
        )
    }

    fun dismissFileOpenError() {
        mutableState.value = mutableState.value.copy(fileOpenError = null)
    }

    fun dismissPreview() {
        previewJob?.cancel()
        mutableState.value = mutableState.value.copy(
            preview = null,
            previewEntry = null,
            previewLoading = false,
            previewError = null,
        )
    }

    private fun previewEntry(entry: DirectoryEntry) {
        previewJob?.cancel()
        mutableState.value = mutableState.value.copy(
            preview = null,
            previewEntry = entry,
            previewLoading = true,
            previewError = null,
            fileInfo = null,
            archiveToOpen = null,
        )
        previewJob = viewModelScope.launch {
            when (val result = withContext(ioDispatcher) { previewRepository.preview(entry) }) {
                is OperationResult.Success -> mutableState.value = mutableState.value.copy(
                    preview = result.value,
                    previewLoading = false,
                )
                is OperationResult.Failure -> mutableState.value = mutableState.value.copy(
                    previewLoading = false,
                    previewError = BrowserOperationError(result.code, result.userMessage),
                )
            }
        }
    }

    fun shareEntry(entry: DirectoryEntry) {
        shareEntries(listOf(entry))
    }

    fun shareSelection() {
        shareEntries(mutableState.value.selectedEntries.toList())
    }

    private fun shareEntries(entries: List<DirectoryEntry>) {
        val request = cancelExternalShare()
        cancelExternalOpen()
        if (entries.isEmpty() || entries.any { it.type == EntryType.OTHER || !it.readable || it.symbolicLink }) {
            mutableState.value = mutableState.value.copy(
                fileShareError = BrowserOperationError(
                    ErrorCode.SOURCE_UNREADABLE,
                    if (entries.size > 1) "无法分享选中的项目" else "无法分享此项目",
                ),
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            sharingFile = true,
            externalFilesToShare = emptyList(),
            fileShareError = null,
        )
        shareFileJob = viewModelScope.launch {
            val grants = mutableListOf<ExternalFileGrant>()
            try {
                val directoryShareResult = if (entries.any { it.type == EntryType.DIRECTORY }) {
                    shareDirectory?.invoke(entries)
                        ?: OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法准备目录分享")
                } else null
                if (directoryShareResult != null) {
                    when (directoryShareResult) {
                        is OperationResult.Failure -> {
                            if (request == shareFileGeneration) {
                                mutableState.value = mutableState.value.copy(
                                    sharingFile = false,
                                    fileShareError = BrowserOperationError(directoryShareResult.code, directoryShareResult.userMessage),
                                )
                            }
                            return@launch
                        }
                        is OperationResult.Success -> grants += directoryShareResult.value
                    }
                } else {
                    for (entry in entries) {
                        when (val result = shareFile(entry)) {
                            is OperationResult.Failure -> {
                                grants.forEach(revokeExport)
                                if (request == shareFileGeneration) {
                                    mutableState.value = mutableState.value.copy(
                                        sharingFile = false,
                                        fileShareError = BrowserOperationError(result.code, result.userMessage),
                                    )
                                }
                                return@launch
                            }
                            is OperationResult.Success -> grants += result.value
                        }
                    }
                }
                if (request != shareFileGeneration) {
                    grants.forEach(revokeExport)
                    return@launch
                }
                mutableState.value = mutableState.value.copy(
                    sharingFile = false,
                    externalFilesToShare = grants,
                )
                entries.forEach(::recordSuccessfulFileAccess)
            } catch (cancelled: CancellationException) {
                if (mutableState.value.externalFilesToShare.map { it.token } != grants.map { it.token }) {
                    grants.forEach(revokeExport)
                }
                if (request == shareFileGeneration) {
                    mutableState.value = mutableState.value.copy(sharingFile = false)
                }
                throw cancelled
            } catch (_: Exception) {
                grants.forEach(revokeExport)
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
        completeExternalShare(listOf(grant), launched)
    }

    fun completeExternalShare(grants: List<ExternalFileGrant>, launched: Boolean) {
        if (mutableState.value.externalFilesToShare.map { it.token } != grants.map { it.token }) return
        if (!launched) grants.forEach(revokeExport)
        mutableState.value = mutableState.value.copy(
            externalFilesToShare = emptyList(),
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
        return beginMove(listOf(entry))
    }

    fun beginMoveSelection(): Boolean = beginMove(selectedEntriesInDirectoryOrder())

    private fun beginMove(entries: List<DirectoryEntry>): Boolean {
        if (fileOperationBusy()) return false
        cancelExternalOpen()
        cancelExternalShare()
        if (
            entries.isEmpty() ||
            entries.any { it.type == EntryType.OTHER || !it.readable || it.symbolicLink } ||
            RootPathRiskPolicy.isProtected(mutableState.value.currentPath)
        ) {
            mutableState.value = mutableState.value.copy(
                fileMoveError = BrowserOperationError(
                    ErrorCode.SOURCE_UNREADABLE,
                    "无法移动选中的项目",
                ),
            )
            return false
        }
        mutableState.value = mutableState.value.copy(
            moveSelection = BrowserMoveSelection(entries, mutableState.value.currentPath),
            moveCompletedCount = 0,
            moveTotalCount = entries.size,
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
            moveCompletedCount = 0,
            moveTotalCount = selection.entries.size,
            movedOutput = null,
            fileMoveError = null,
        )
        launchMove(selection, targetDirectory, 0, emptyList(), null, ConflictAction.CANCEL, null)
    }

    private fun launchMove(
        selection: BrowserMoveSelection,
        targetDirectory: RootPath,
        startIndex: Int,
        completedBefore: List<DirectoryEntry>,
        persistentAction: ConflictAction?,
        currentAction: ConflictAction,
        existingTaskId: String?,
    ) {
        mutableState.value = mutableState.value.copy(movingFile = true, conflictPrompt = null)
        moveFileJob = viewModelScope.launch {
            var taskId: String? = existingTaskId
            try {
                taskId = taskId ?: operationTaskStore?.start(
                    OperationTaskType.MOVE,
                    selection.entries.size,
                    selection.totalKnownBytes(),
                )
                ownTask(taskId)
                updateTask(taskId, OperationTaskState.RUNNING, completedBefore.size, completedBytes = completedBefore.knownBytes())
                val completed = completedBefore.toMutableList()
                for (index in startIndex until selection.entries.size) {
                    awaitTaskResume(taskId, completed.size, completed.knownBytes())
                    val entry = selection.entries[index]
                    val sourceIdentity = withContext(ioDispatcher) {
                        (rootFileSystem.identity(entry.path) as? OperationResult.Success)?.value
                    }
                    val action = if (index == startIndex) currentAction else ConflictAction.CANCEL
                    when (
                        val result = withContext(ioDispatcher) {
                            moveFile(entry, selection.sourceDirectory, targetDirectory, action)
                        }
                    ) {
                        is OperationResult.Failure -> {
                            if (result.code == ErrorCode.ALREADY_EXISTS && persistentAction == ConflictAction.SKIP) {
                                continue
                            }
                            if (result.code == ErrorCode.ALREADY_EXISTS && persistentAction == ConflictAction.KEEP_BOTH) {
                                when (
                                    val kept = withContext(ioDispatcher) {
                                        moveFile(entry, selection.sourceDirectory, targetDirectory, ConflictAction.KEEP_BOTH)
                                    }
                                ) {
                                    is OperationResult.Success -> {
                                        completed += kept.value
                                        relocateBookmark(entry, kept.value)
                                        sourceIdentity?.let { relocateVirtualReferences(it, kept.value) }
                                        mutableState.value = mutableState.value.copy(moveCompletedCount = completed.size)
                                        recordSuccessfulFileAccess(kept.value)
                                        updateRunningTask(taskId, completed.size, completed.knownBytes())
                                        continue
                                    }
                                    is OperationResult.Failure -> {
                                        mutableState.value = mutableState.value.copy(
                                            movingFile = false,
                                            fileMoveError = BrowserOperationError(kept.code, kept.userMessage),
                                        )
                                        finishFailedTask(taskId, completed.size, selection.entries.size, kept)
                                        return@launch
                                    }
                                }
                            }
                            if (result.code == ErrorCode.ALREADY_EXISTS && action == ConflictAction.CANCEL) {
                                pendingConflict = PendingConflict.Move(
                                    selection, targetDirectory, index, completed, persistentAction, taskId,
                                )
                                mutableState.value = mutableState.value.copy(
                                    movingFile = false,
                                    movedOutput = completed.lastOrNull(),
                                    conflictPrompt = BrowserConflictPrompt(
                                        operation = BrowserConflictOperation.MOVE,
                                        entryName = entry.name,
                                        completedCount = completed.size,
                                        totalCount = selection.entries.size,
                                        entryType = entry.type,
                                    ),
                                )
                                updateTask(taskId, OperationTaskState.NEEDS_ACTION, completed.size, message = "等待处理同名冲突")
                                return@launch
                            }
                            mutableState.value = if (completed.isEmpty()) {
                                mutableState.value.copy(
                                    movingFile = false,
                                    fileMoveError = BrowserOperationError(result.code, result.userMessage),
                                )
                            } else {
                                mutableState.value.copy(
                                    selectedEntries = emptySet(),
                                    moveSelection = null,
                                    movingFile = false,
                                    movedOutput = completed.last(),
                                    fileMoveError = BrowserOperationError(
                                        result.code,
                                        "已移动 ${completed.size}/${selection.entries.size} 项；${result.userMessage}",
                                    ),
                                )
                            }
                            finishFailedTask(taskId, completed.size, selection.entries.size, result)
                            return@launch
                        }
                        is OperationResult.Success -> {
                            completed += result.value
                            relocateBookmark(entry, result.value)
                            sourceIdentity?.let { relocateVirtualReferences(it, result.value) }
                            mutableState.value = mutableState.value.copy(moveCompletedCount = completed.size)
                            recordSuccessfulFileAccess(result.value)
                            updateRunningTask(taskId, completed.size, completed.knownBytes())
                        }
                    }
                }
                mutableState.value = mutableState.value.copy(
                    selectedEntries = emptySet(),
                    moveSelection = null,
                    movingFile = false,
                    movedOutput = completed.lastOrNull(),
                    fileMoveError = null,
                )
                finishCompletedTask(taskId, completed.size, selection.entries.size)
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(movingFile = false)
                withContext(NonCancellable) {
                    updateTask(taskId, OperationTaskState.CANCELLED, mutableState.value.moveCompletedCount, message = "任务已取消")
                }
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    movingFile = false,
                    fileMoveError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "无法移动文件"),
                )
                finishFailedTask(
                    taskId,
                    mutableState.value.moveCompletedCount,
                    selection.entries.size,
                    OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法移动文件"),
                )
            } finally {
                releaseTask(taskId)
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
            conflictPrompt = null,
        )
        pendingConflict = null
        return true
    }

    fun consumeMovedOutput() {
        mutableState.value = mutableState.value.copy(movedOutput = null)
    }

    fun dismissFileMoveError() {
        mutableState.value = mutableState.value.copy(fileMoveError = null)
    }

    fun beginCopy(entry: DirectoryEntry): Boolean {
        return beginCopy(listOf(entry))
    }

    fun beginCopySelection(): Boolean = beginCopy(selectedEntriesInDirectoryOrder())

    private fun beginCopy(entries: List<DirectoryEntry>): Boolean {
        if (fileOperationBusy()) return false
        cancelExternalOpen()
        cancelExternalShare()
        if (
            entries.isEmpty() ||
            entries.any { it.type == EntryType.OTHER || !it.readable || it.symbolicLink }
        ) {
            mutableState.value = mutableState.value.copy(
                fileCopyError = BrowserOperationError(
                    ErrorCode.SOURCE_UNREADABLE,
                    "无法复制选中的项目",
                ),
            )
            return false
        }
        mutableState.value = mutableState.value.copy(
            copySelection = BrowserCopySelection(entries, mutableState.value.currentPath),
            copyCompletedCount = 0,
            copyTotalCount = entries.size,
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
            copyCompletedCount = 0,
            copyTotalCount = selection.entries.size,
            copiedOutput = null,
            fileCopyError = null,
        )
        launchCopy(selection, targetDirectory, 0, emptyList(), null, ConflictAction.CANCEL, null)
    }

    private fun launchCopy(
        selection: BrowserCopySelection,
        targetDirectory: RootPath,
        startIndex: Int,
        completedBefore: List<DirectoryEntry>,
        persistentAction: ConflictAction?,
        currentAction: ConflictAction,
        existingTaskId: String?,
    ) {
        mutableState.value = mutableState.value.copy(copyingFile = true, conflictPrompt = null)
        copyFileJob = viewModelScope.launch {
            var taskId: String? = existingTaskId
            try {
                taskId = taskId ?: operationTaskStore?.start(
                    OperationTaskType.COPY,
                    selection.entries.size,
                    selection.totalKnownBytes(),
                )
                ownTask(taskId)
                updateTask(taskId, OperationTaskState.RUNNING, completedBefore.size, completedBytes = completedBefore.knownBytes())
                val completed = completedBefore.toMutableList()
                for (index in startIndex until selection.entries.size) {
                    awaitTaskResume(taskId, completed.size, completed.knownBytes())
                    val entry = selection.entries[index]
                    val action = if (index == startIndex) currentAction else ConflictAction.CANCEL
                    when (
                        val result = withContext(ioDispatcher) {
                            copyFile(entry, selection.sourceDirectory, targetDirectory, action)
                        }
                    ) {
                        is OperationResult.Failure -> {
                            if (result.code == ErrorCode.ALREADY_EXISTS && persistentAction == ConflictAction.SKIP) {
                                continue
                            }
                            if (result.code == ErrorCode.ALREADY_EXISTS && persistentAction == ConflictAction.KEEP_BOTH) {
                                when (
                                    val kept = withContext(ioDispatcher) {
                                        copyFile(entry, selection.sourceDirectory, targetDirectory, ConflictAction.KEEP_BOTH)
                                    }
                                ) {
                                    is OperationResult.Success -> {
                                        completed += kept.value
                                        mutableState.value = mutableState.value.copy(copyCompletedCount = completed.size)
                                        recordSuccessfulFileAccess(kept.value)
                                        updateRunningTask(taskId, completed.size, completed.knownBytes())
                                        continue
                                    }
                                    is OperationResult.Failure -> {
                                        mutableState.value = mutableState.value.copy(
                                            copyingFile = false,
                                            fileCopyError = BrowserOperationError(kept.code, kept.userMessage),
                                        )
                                        finishFailedTask(taskId, completed.size, selection.entries.size, kept)
                                        return@launch
                                    }
                                }
                            }
                            if (result.code == ErrorCode.ALREADY_EXISTS && action == ConflictAction.CANCEL) {
                                pendingConflict = PendingConflict.Copy(
                                    selection, targetDirectory, index, completed, persistentAction, taskId,
                                )
                                mutableState.value = mutableState.value.copy(
                                    copyingFile = false,
                                    copiedOutput = completed.lastOrNull(),
                                    conflictPrompt = BrowserConflictPrompt(
                                        operation = BrowserConflictOperation.COPY,
                                        entryName = entry.name,
                                        completedCount = completed.size,
                                        totalCount = selection.entries.size,
                                        entryType = entry.type,
                                    ),
                                )
                                updateTask(taskId, OperationTaskState.NEEDS_ACTION, completed.size, message = "等待处理同名冲突")
                                return@launch
                            }
                            mutableState.value = if (completed.isEmpty()) {
                                mutableState.value.copy(
                                    copyingFile = false,
                                    fileCopyError = BrowserOperationError(result.code, result.userMessage),
                                )
                            } else {
                                mutableState.value.copy(
                                    selectedEntries = emptySet(),
                                    copySelection = null,
                                    copyingFile = false,
                                    copiedOutput = completed.last(),
                                    fileCopyError = BrowserOperationError(
                                        result.code,
                                        "已复制 ${completed.size}/${selection.entries.size} 项；${result.userMessage}",
                                    ),
                                )
                            }
                            finishFailedTask(taskId, completed.size, selection.entries.size, result)
                            return@launch
                        }
                        is OperationResult.Success -> {
                            completed += result.value
                            mutableState.value = mutableState.value.copy(copyCompletedCount = completed.size)
                            recordSuccessfulFileAccess(result.value)
                            updateRunningTask(taskId, completed.size, completed.knownBytes())
                        }
                    }
                }
                mutableState.value = mutableState.value.copy(
                    selectedEntries = emptySet(),
                    copySelection = null,
                    copyingFile = false,
                    copiedOutput = completed.lastOrNull(),
                    fileCopyError = null,
                )
                finishCompletedTask(taskId, completed.size, selection.entries.size)
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(copyingFile = false)
                withContext(NonCancellable) {
                    updateTask(taskId, OperationTaskState.CANCELLED, mutableState.value.copyCompletedCount, message = "任务已取消")
                }
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    copyingFile = false,
                    fileCopyError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "无法复制文件"),
                )
                finishFailedTask(
                    taskId,
                    mutableState.value.copyCompletedCount,
                    selection.entries.size,
                    OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法复制文件"),
                )
            } finally {
                releaseTask(taskId)
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
            conflictPrompt = null,
        )
        pendingConflict = null
        return true
    }

    fun consumeCopiedOutput() {
        mutableState.value = mutableState.value.copy(copiedOutput = null)
    }

    fun dismissFileCopyError() {
        mutableState.value = mutableState.value.copy(fileCopyError = null)
    }

    fun resolveConflict(action: ConflictAction, applyToAll: Boolean = false) {
        val pending = pendingConflict ?: return
        if (action == ConflictAction.CANCEL) {
            pendingConflict = null
            val message = if (pending.completed.isEmpty()) "操作已取消" else "已完成 ${pending.completed.size}/${pending.totalCount} 项；其余操作已取消"
            mutableState.value = when (pending) {
                is PendingConflict.Move -> mutableState.value.copy(
                    selectedEntries = emptySet(), moveSelection = null, conflictPrompt = null,
                    fileMoveError = BrowserOperationError(ErrorCode.ALREADY_EXISTS, message),
                )
                is PendingConflict.Copy -> mutableState.value.copy(
                    selectedEntries = emptySet(), copySelection = null, conflictPrompt = null,
                    fileCopyError = BrowserOperationError(ErrorCode.ALREADY_EXISTS, message),
                )
            }
            viewModelScope.launch {
                updateTask(
                    pending.taskId,
                    if (pending.completed.isEmpty()) OperationTaskState.CANCELLED else OperationTaskState.PARTIAL_SUCCESS,
                    pending.completed.size,
                    message = message,
                )
            }
            return
        }
        pendingConflict = null
        val persistent = if (applyToAll) action else pending.persistentAction
        when (pending) {
            is PendingConflict.Move -> launchMove(
                pending.selection, pending.targetDirectory,
                if (action == ConflictAction.SKIP) pending.index + 1 else pending.index,
                pending.completed, persistent,
                if (action == ConflictAction.SKIP) ConflictAction.CANCEL else action,
                pending.taskId,
            )
            is PendingConflict.Copy -> launchCopy(
                pending.selection, pending.targetDirectory,
                if (action == ConflictAction.SKIP) pending.index + 1 else pending.index,
                pending.completed, persistent,
                if (action == ConflictAction.SKIP) ConflictAction.CANCEL else action,
                pending.taskId,
            )
        }
    }

    fun renameEntry(entry: DirectoryEntry, newName: String) {
        if (renameFileJob?.isActive == true || fileOperationBusy()) return
        val parent = mutableState.value.currentPath
        mutableState.value = mutableState.value.copy(
            renamingFile = true,
            renamedOutput = null,
            fileRenameError = null,
        )
        renameFileJob = viewModelScope.launch {
            try {
                val sourceIdentity = withContext(ioDispatcher) {
                    (rootFileSystem.identity(entry.path) as? OperationResult.Success)?.value
                }
                when (val result = withContext(ioDispatcher) { renameFile(entry, parent, newName) }) {
                    is OperationResult.Failure -> mutableState.value = mutableState.value.copy(
                        renamingFile = false,
                        fileRenameError = BrowserOperationError(result.code, result.userMessage),
                    )
                    is OperationResult.Success -> {
                        relocateBookmark(entry, result.value)
                        sourceIdentity?.let { relocateVirtualReferences(it, result.value) }
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

    fun previewBatchRename(rule: BatchRenameRule) {
        if (fileOperationBusy()) return
        val current = mutableState.value
        val selected = selectedEntriesInDirectoryOrder()
        when (val result = batchRenamePlanner.plan(selected, current.allEntries, rule)) {
            is OperationResult.Success -> mutableState.value = current.copy(
                batchRenamePlan = result.value,
                batchRenameError = null,
            )
            is OperationResult.Failure -> mutableState.value = current.copy(
                batchRenamePlan = null,
                batchRenameError = BrowserOperationError(result.code, result.userMessage),
            )
        }
    }

    fun executeBatchRename() {
        if (renameFileJob?.isActive == true || fileOperationBusy()) return
        val plan = mutableState.value.batchRenamePlan ?: return
        val currentPaths = selectedEntriesInDirectoryOrder().map { it.path }
        if (currentPaths != plan.items.map { it.source.path }) {
            mutableState.value = mutableState.value.copy(
                batchRenamePlan = null,
                batchRenameError = BrowserOperationError(ErrorCode.SOURCE_UNREADABLE, "选择已变化，请重新预览"),
            )
            return
        }
        val parent = mutableState.value.currentPath
        mutableState.value = mutableState.value.copy(renamingFile = true, batchRenameError = null)
        renameFileJob = viewModelScope.launch {
            try {
                when (val result = withContext(ioDispatcher) { batchRenameExecutor.execute(plan, parent) }) {
                    is OperationResult.Failure -> mutableState.value = mutableState.value.copy(
                        renamingFile = false,
                        batchRenamePlan = null,
                        batchRenameError = BrowserOperationError(result.code, result.userMessage),
                    )
                    is OperationResult.Success -> {
                        result.value.renamed.forEach { output ->
                            plan.items.firstOrNull { it.targetName.value == output.name }?.let { item ->
                                relocateBookmark(item.source, output)
                            }
                        }
                        mutableState.value = mutableState.value.copy(
                            selectedEntries = emptySet(),
                            renamingFile = false,
                            batchRenamePlan = null,
                            batchRenameError = null,
                        )
                        load(parent)
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(renamingFile = false)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    renamingFile = false,
                    batchRenamePlan = null,
                    batchRenameError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "批量重命名失败"),
                )
            }
        }
    }

    fun dismissBatchRename() {
        if (mutableState.value.renamingFile) return
        mutableState.value = mutableState.value.copy(batchRenamePlan = null, batchRenameError = null)
    }

    fun clearFinishedTasks() {
        operationTaskStore?.let { store -> viewModelScope.launch(ioDispatcher) { store.clearFinished() } }
    }

    fun pauseTask(id: String) {
        if (id != activeTaskId || taskPaused.value) return
        taskPaused.value = true
        mutableState.value = mutableState.value.copy(controllableTaskPaused = true)
    }

    fun resumeTask(id: String) {
        if (id != activeTaskId || !taskPaused.value) return
        taskPaused.value = false
        mutableState.value = mutableState.value.copy(controllableTaskPaused = false)
    }

    fun cancelTask(id: String) {
        if (id != activeTaskId) return
        viewModelScope.launch(ioDispatcher) {
            updateTask(id, OperationTaskState.CANCELLING, activeCompletedItems(), completedBytes = activeCompletedBytes())
            moveFileJob?.takeIf { it.isActive }?.cancel()
            copyFileJob?.takeIf { it.isActive }?.cancel()
            taskPaused.value = false
        }
    }

    fun recycleEntry(entry: DirectoryEntry) {
        val repository = trashRepository ?: return
        if (fileOperationBusy() || mutableState.value.deletingEntry) return
        val parent = mutableState.value.currentPath
        mutableState.value = mutableState.value.copy(deletingEntry = true, trashError = null)
        viewModelScope.launch {
            val taskId = operationTaskStore?.start(OperationTaskType.DELETE, 1)
            updateTask(taskId, OperationTaskState.RUNNING, 0)
            when (val result = withContext(ioDispatcher) { repository.recycle(entry, parent) }) {
                is OperationResult.Success -> {
                    mutableState.value = mutableState.value.copy(
                        deletingEntry = false, selectedEntries = emptySet(), trashError = null,
                    )
                    updateTask(taskId, OperationTaskState.SUCCESS, 1)
                    load(parent)
                }
                is OperationResult.Failure -> {
                    mutableState.value = mutableState.value.copy(
                        deletingEntry = false,
                        trashError = BrowserOperationError(result.code, result.userMessage),
                    )
                    finishFailedTask(taskId, 0, 1, result)
                }
            }
        }
    }

    fun recycleSelection(entries: List<DirectoryEntry>) {
        val selected = selectedEntriesInDirectoryOrder()
        if (entries.toSet() != selected.toSet() || selected.isEmpty()) return
        if (selected.any { !it.path.value.startsWith("/storage/emulated/0/") }) {
            mutableState.value = mutableState.value.copy(
                trashError = BrowserOperationError(
                    ErrorCode.NOT_WRITABLE,
                    "批量删除仅支持可进入回收站的共享存储项目",
                ),
            )
            return
        }
        val repository = trashRepository ?: return
        if (fileOperationBusy() || mutableState.value.deletingEntry) return
        val parent = mutableState.value.currentPath
        runBatchTrashOperation(
            total = selected.size,
            type = OperationTaskType.DELETE,
            operation = { onProgress -> repository.recycleAll(selected, parent, onProgress) },
            onFinished = { completed ->
                selected.drop(completed).takeIf { it.isNotEmpty() }?.let {
                    selectionToRestore = BrowserMoveSelection(it, parent)
                }
                load(parent)
            },
        )
    }

    fun deleteEntryPermanently(entry: DirectoryEntry) {
        val repository = trashRepository ?: return
        if (fileOperationBusy() || mutableState.value.deletingEntry) return
        val parent = mutableState.value.currentPath
        mutableState.value = mutableState.value.copy(deletingEntry = true, trashError = null)
        viewModelScope.launch {
            val taskId = operationTaskStore?.start(OperationTaskType.DELETE, 1)
            updateTask(taskId, OperationTaskState.RUNNING, 0)
            when (val result = withContext(ioDispatcher) { repository.deletePermanently(entry, parent) }) {
                is OperationResult.Success -> {
                    mutableState.value = mutableState.value.copy(
                        deletingEntry = false, selectedEntries = emptySet(), trashError = null,
                    )
                    updateTask(taskId, OperationTaskState.SUCCESS, 1)
                    load(parent)
                }
                is OperationResult.Failure -> {
                    mutableState.value = mutableState.value.copy(
                        deletingEntry = false, trashError = BrowserOperationError(result.code, result.userMessage),
                    )
                    finishFailedTask(taskId, 0, 1, result)
                }
            }
        }
    }

    fun restoreTrashItem(item: TrashItem) = runTrashItemOperation(item, restore = true)

    fun restoreTrashItem(item: TrashItem, action: RestoreConflictAction, name: String? = null) {
        mutableState.value = mutableState.value.copy(restoreConflictItem = null)
        runTrashItemOperation(item, restore = true, restoreAction = action, restoreName = name)
    }

    fun dismissRestoreConflict() {
        mutableState.value = mutableState.value.copy(restoreConflictItem = null)
    }

    fun deleteTrashItemPermanently(item: TrashItem) = runTrashItemOperation(item, restore = false)

    fun restoreTrashItems(items: List<TrashItem>) {
        val repository = trashRepository ?: return
        val active = items.filter { it.state == com.isaver.filemanager.trash.TrashItemState.ACTIVE }
        if (active.isEmpty()) return
        runBatchTrashOperation(
            total = active.size,
            type = OperationTaskType.RESTORE,
            operation = { onProgress -> repository.restoreAll(active, onProgress) },
        )
    }

    fun clearTrash(items: List<TrashItem>) {
        val repository = trashRepository ?: return
        val active = items.filter { it.state == com.isaver.filemanager.trash.TrashItemState.ACTIVE }
        if (active.isEmpty()) return
        runBatchTrashOperation(
            total = active.size,
            type = OperationTaskType.DELETE,
            operation = { onProgress -> repository.deletePermanentlyAll(active, onProgress) },
        )
    }

    private fun runBatchTrashOperation(
        total: Int,
        type: OperationTaskType,
        operation: suspend (suspend (Int) -> Unit) -> com.isaver.filemanager.trash.TrashBatchResult,
        onFinished: (Int) -> Unit = {},
    ) {
        if (fileOperationBusy() || mutableState.value.deletingEntry || total == 0) return
        mutableState.value = mutableState.value.copy(deletingEntry = true, trashError = null)
        viewModelScope.launch {
            val taskId = operationTaskStore?.start(type, total)
            updateTask(taskId, OperationTaskState.RUNNING, 0)
            try {
                val result = withContext(ioDispatcher) {
                    operation { completed ->
                        updateTask(taskId, OperationTaskState.RUNNING, completed)
                    }
                }
                onFinished(result.completed)
                val failure = result.failure
                if (failure == null) {
                    finishCompletedTask(taskId, result.completed, total)
                } else {
                    finishFailedTask(taskId, result.completed, total, failure)
                }
                mutableState.value = mutableState.value.copy(
                    deletingEntry = false,
                    trashError = failure?.let {
                        BrowserOperationError(
                            it.code,
                            if (result.completed > 0) {
                                "已完成 ${result.completed}/$total 项：${it.userMessage}"
                            } else it.userMessage,
                        )
                    },
                )
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(deletingEntry = false)
                throw cancelled
            } catch (_: Exception) {
                val failure = OperationResult.Failure(ErrorCode.COMMAND_FAILED, "批量操作失败")
                finishFailedTask(taskId, 0, total, failure)
                mutableState.value = mutableState.value.copy(
                    deletingEntry = false,
                    trashError = BrowserOperationError(failure.code, failure.userMessage),
                )
            }
        }
    }

    private fun runTrashItemOperation(
        item: TrashItem,
        restore: Boolean,
        restoreAction: RestoreConflictAction = RestoreConflictAction.CANCEL,
        restoreName: String? = null,
    ) {
        val repository = trashRepository ?: return
        if (fileOperationBusy() || mutableState.value.deletingEntry) return
        mutableState.value = mutableState.value.copy(deletingEntry = true, trashError = null)
        viewModelScope.launch {
            val taskId = operationTaskStore?.start(
                if (restore) OperationTaskType.RESTORE else OperationTaskType.DELETE,
                1,
            )
            updateTask(taskId, OperationTaskState.RUNNING, 0)
            val result = withContext(ioDispatcher) {
                if (restore) repository.restore(item, restoreAction, restoreName) else repository.deletePermanently(item)
            }
            when (result) {
                is OperationResult.Success -> {
                    mutableState.value = mutableState.value.copy(deletingEntry = false, trashError = null)
                    updateTask(taskId, OperationTaskState.SUCCESS, 1)
                }
                is OperationResult.Failure -> {
                    mutableState.value = mutableState.value.copy(
                        deletingEntry = false,
                        trashError = BrowserOperationError(result.code, result.userMessage),
                        restoreConflictItem = item.takeIf { restore && result.code == ErrorCode.ALREADY_EXISTS },
                    )
                    finishFailedTask(taskId, 0, 1, result)
                }
            }
        }
    }

    fun dismissTrashError() {
        mutableState.value = mutableState.value.copy(trashError = null)
    }

    private suspend fun updateTask(
        id: String?,
        state: OperationTaskState,
        completed: Int,
        failed: Int = 0,
        message: String? = null,
        completedBytes: Long? = null,
    ) {
        if (id != null) operationTaskStore?.update(id, state, completed, failed, message, completedBytes)
    }

    private suspend fun awaitTaskResume(id: String?, completed: Int, completedBytes: Long) {
        if (!taskPaused.value) return
        updateTask(id, OperationTaskState.PAUSED, completed, completedBytes = completedBytes)
        taskPaused.first { paused -> !paused }
        updateTask(id, OperationTaskState.RUNNING, completed, completedBytes = completedBytes)
    }

    private suspend fun updateRunningTask(id: String?, completed: Int, completedBytes: Long) {
        activeTaskCompletedBytes = completedBytes
        updateTask(
            id,
            if (taskPaused.value) OperationTaskState.PAUSED else OperationTaskState.RUNNING,
            completed,
            completedBytes = completedBytes,
        )
    }

    private fun ownTask(id: String?) {
        activeTaskId = id
        activeTaskCompletedBytes = 0
        taskPaused.value = false
        mutableState.value = mutableState.value.copy(controllableTaskId = id, controllableTaskPaused = false)
    }

    private fun releaseTask(id: String?) {
        if (activeTaskId != id) return
        activeTaskId = null
        activeTaskCompletedBytes = 0
        taskPaused.value = false
        mutableState.value = mutableState.value.copy(controllableTaskId = null, controllableTaskPaused = false)
    }

    private fun activeCompletedItems() = if (mutableState.value.movingFile) {
        mutableState.value.moveCompletedCount
    } else {
        mutableState.value.copyCompletedCount
    }

    private fun activeCompletedBytes(): Long = activeTaskCompletedBytes

    private fun List<DirectoryEntry>.knownBytes(): Long = sumOf { it.sizeBytes ?: 0L }

    private fun BrowserMoveSelection.totalKnownBytes(): Long? = entries.totalKnownBytes()
    private fun BrowserCopySelection.totalKnownBytes(): Long? = entries.totalKnownBytes()
    private fun List<DirectoryEntry>.totalKnownBytes(): Long? =
        if (all { it.type == EntryType.FILE && it.sizeBytes != null }) knownBytes() else null

    private suspend fun finishFailedTask(
        id: String?,
        completed: Int,
        total: Int,
        failure: OperationResult.Failure,
    ) = withContext(NonCancellable) {
        val state = when {
            failure.code == ErrorCode.OUTCOME_UNCERTAIN -> OperationTaskState.OUTCOME_UNCERTAIN
            completed > 0 -> OperationTaskState.PARTIAL_SUCCESS
            else -> OperationTaskState.FAILED
        }
        updateTask(id, state, completed, total - completed, failure.userMessage)
    }

    private suspend fun finishCompletedTask(id: String?, completed: Int, total: Int) = withContext(NonCancellable) {
        updateTask(
            id,
            if (completed == total) OperationTaskState.SUCCESS else OperationTaskState.PARTIAL_SUCCESS,
            completed,
            total - completed,
            if (completed == total) null else "已跳过 ${total - completed} 项",
        )
    }

    fun compress(outputName: String, format: ArchiveFormat = ArchiveFormat.ZIP) {
        val repository = archiveRepository ?: return
        val current = mutableState.value
        if (current.selectedEntries.isEmpty() || current.compressing) return
        val draft = OutputNameDraft(
            stem = outputName.removeSuffix(".${format.defaultExtension}"),
            extension = format.defaultExtension,
        )
        mutableState.value = current.copy(compressing = true, compressionMessage = null)
        viewModelScope.launch {
            val totalItems = current.selectedEntries.size
            val taskId = operationTaskStore?.start(OperationTaskType.ARCHIVE, totalItems)
            updateTask(
                taskId,
                OperationTaskState.RUNNING,
                0,
                message = "${format.creationLabel} 压缩中",
            )
            try {
                repository.createArchive(
                    sources = current.selectedEntries.toList(),
                    targetDirectory = current.currentPath,
                    outputName = draft,
                    format = format,
                ).collect { state ->
                    when (state) {
                        is ArchiveState.Success -> {
                            mutableState.value = mutableState.value.copy(
                                compressing = false,
                                compressionMessage = "压缩完成",
                                selectedEntries = emptySet(),
                            )
                            updateTask(taskId, OperationTaskState.SUCCESS, totalItems)
                        }
                        is ArchiveState.Failure -> {
                            mutableState.value = mutableState.value.copy(
                                compressing = false,
                                compressionMessage = state.message,
                            )
                            finishFailedTask(
                                taskId,
                                0,
                                totalItems,
                                OperationResult.Failure(state.code, state.message),
                            )
                        }
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(compressing = false)
                withContext(NonCancellable) {
                    updateTask(taskId, OperationTaskState.CANCELLED, 0, message = "压缩已取消")
                }
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    compressing = false,
                    compressionMessage = "压缩失败",
                )
                withContext(NonCancellable) {
                    updateTask(taskId, OperationTaskState.FAILED, 0, totalItems, "压缩失败")
                }
            }
        }
    }

    fun clearCompressionMessage() {
        mutableState.value = mutableState.value.copy(compressionMessage = null)
    }

    fun openRoot(path: RootPath, title: String, recordAccess: Boolean = true) {
        stack.clear()
        forwardStack.clear()
        selectedRootPath = path
        mutableState.value = mutableState.value.copy(rootTitle = title)
        load(path, recordAccess = recordAccess)
    }

    fun restoreSessionOrOpenRoot(path: RootPath, title: String, recordAccess: Boolean = true) {
        val store = browserSessionStore
        if (sessionRestoreAttempted || store == null) {
            openRoot(path, title, recordAccess)
            return
        }
        sessionRestoreAttempted = true
        viewModelScope.launch {
            val session = runCatching { store.session.first() }.getOrNull()
            val current = session?.let { withContext(ioDispatcher) { rootFileSystem.stat(it.currentPath) } }
            if (session == null || current !is OperationResult.Success ||
                current.value.type != EntryType.DIRECTORY || current.value.symbolicLink || !current.value.readable
            ) {
                if (session != null) runCatching { store.clear() }
                openRoot(path, title, recordAccess)
                return@launch
            }
            selectedRootPath = session.rootPath
            stack.clear()
            stack.addAll(session.backStack)
            forwardStack.clear()
            forwardStack.addAll(session.forwardStack)
            mutableState.value = mutableState.value.copy(rootTitle = session.rootTitle)
            load(session.currentPath, recordAccess = false)
        }
    }

    fun back(): BrowserBackResult {
        val previous = stack.removeLastOrNull() ?: return BrowserBackResult.RETURN_HOME
        forwardStack.addLast(mutableState.value.currentPath)
        load(previous)
        return BrowserBackResult.NAVIGATED
    }

    fun forward(): Boolean {
        val next = forwardStack.removeLastOrNull() ?: return false
        stack.addLast(mutableState.value.currentPath)
        load(next)
        return true
    }

    fun toggleCurrentBookmark() {
        val repository = bookmarkRepository ?: return
        val current = mutableState.value
        viewModelScope.launch {
            current.bookmarks.firstOrNull { it.path == current.currentPath }?.let { repository.remove(it) }
                ?: run {
                    val identity = withContext(ioDispatcher) { rootFileSystem.identity(current.currentPath) }
                    repository.add(
                        current.currentPath,
                        if (current.currentPath == selectedRootPath) current.rootTitle else current.title,
                        EntryType.DIRECTORY,
                        (identity as? OperationResult.Success)?.value,
                    )
                }
        }
    }

    fun toggleEntryBookmark(entry: DirectoryEntry) {
        val repository = bookmarkRepository ?: return
        val existing = mutableState.value.bookmarks.firstOrNull { it.path == entry.path }
        viewModelScope.launch {
            if (existing != null) {
                repository.remove(existing)
            } else {
                val identity = withContext(ioDispatcher) { rootFileSystem.identity(entry.path) }
                repository.add(entry.path, entry.name, entry.type, (identity as? OperationResult.Success)?.value)
            }
        }
    }

    fun updateBookmark(bookmark: Bookmark, displayName: String, colorKey: String?, groupName: String?) {
        bookmarkRepository?.let { repository ->
            viewModelScope.launch { repository.updateDetails(bookmark, displayName, colorKey, groupName) }
        }
    }

    fun openBookmark(bookmark: Bookmark) {
        val repository = bookmarkRepository ?: return
        viewModelScope.launch {
            val entry = withContext(ioDispatcher) { rootFileSystem.stat(bookmark.path) }
            val identity = if (entry is OperationResult.Success) {
                withContext(ioDispatcher) { rootFileSystem.identity(bookmark.path) }
            } else null
            val valid = entry is OperationResult.Success && entry.value.type == bookmark.type &&
                (bookmark.identity == null || identity is OperationResult.Success && identity.value == bookmark.identity)
            if (!valid) {
                repository.setAvailability(bookmark.path, false)
                return@launch
            }
            if (!bookmark.available) repository.setAvailability(bookmark.path, true)
            when (bookmark.type) {
                EntryType.DIRECTORY -> openRoot(bookmark.path, bookmark.displayName)
                EntryType.FILE, EntryType.OTHER -> openEntry((entry as OperationResult.Success).value)
            }
        }
    }

    private suspend fun relocateBookmark(source: DirectoryEntry, output: DirectoryEntry) {
        val repository = bookmarkRepository ?: return
        val bookmark = mutableState.value.bookmarks.firstOrNull { it.path == source.path } ?: return
        val identity = withContext(ioDispatcher) { rootFileSystem.identity(output.path) }
        repository.relocate(
            bookmark,
            output.path,
            output.name,
            (identity as? OperationResult.Success)?.value,
        )
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
        if (createDirectoryJob?.isActive == true || fileOperationBusy()) return
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

    private fun fileOperationBusy(): Boolean = mutableState.value.run {
        creatingDirectory || creatingFile || movingFile || copyingFile || renamingFile || deletingEntry || compressing
    }

    fun startDeepSearch(criteria: LocalSearchCriteria) {
        if (deepSearchJob?.isActive == true) return
        val root = mutableState.value.currentPath
        mutableState.value = mutableState.value.copy(
            deepSearchCriteria = criteria,
            deepSearchResults = emptyList(),
            deepSearchRunning = true,
            deepSearchScannedDirectories = 0,
            deepSearchScannedEntries = 0,
            deepSearchSkippedDirectories = 0,
            deepSearchTruncated = false,
            deepSearchError = null,
        )
        deepSearchJob = viewModelScope.launch {
            val taskId = operationTaskStore?.start(OperationTaskType.SEARCH, 1)
            updateTask(taskId, OperationTaskState.RUNNING, 0)
            try {
                val result = withContext(ioDispatcher) {
                    localSearchRepository.search(root, criteria, ::updateDeepSearchProgress)
                }
                when (result) {
                    is OperationResult.Success -> {
                        mutableState.value = mutableState.value.copy(
                            deepSearchResults = result.value.entries,
                            deepSearchRunning = false,
                            deepSearchScannedDirectories = result.value.scannedDirectories,
                            deepSearchScannedEntries = result.value.scannedEntries,
                            deepSearchSkippedDirectories = result.value.skippedDirectories,
                            deepSearchTruncated = result.value.truncated,
                        )
                        updateTask(
                            taskId, OperationTaskState.SUCCESS, 1,
                            message = "找到 ${result.value.entries.size} 项",
                        )
                    }
                    is OperationResult.Failure -> {
                        mutableState.value = mutableState.value.copy(
                            deepSearchRunning = false,
                            deepSearchError = result.userMessage,
                        )
                        updateTask(taskId, OperationTaskState.FAILED, 0, 1, result.userMessage)
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(deepSearchRunning = false)
                withContext(NonCancellable) {
                    updateTask(taskId, OperationTaskState.CANCELLED, 0, message = "搜索已取消")
                }
                throw cancelled
            }
        }
    }

    private fun updateDeepSearchProgress(progress: LocalSearchProgress) {
        mutableState.value = mutableState.value.copy(
            deepSearchScannedDirectories = progress.scannedDirectories,
            deepSearchScannedEntries = progress.scannedEntries,
        )
    }

    fun cancelDeepSearch() {
        deepSearchJob?.cancel()
    }

    fun clearDeepSearch() {
        deepSearchJob?.cancel()
        mutableState.value = mutableState.value.copy(
            deepSearchCriteria = null,
            deepSearchResults = emptyList(),
            deepSearchRunning = false,
            deepSearchScannedDirectories = 0,
            deepSearchScannedEntries = 0,
            deepSearchSkippedDirectories = 0,
            deepSearchTruncated = false,
            deepSearchError = null,
        )
    }

    fun openDeepSearchResultLocation(entry: DirectoryEntry) {
        val parentValue = entry.path.value.substringBeforeLast('/', "").ifEmpty { "/" }
        val parent = RootPath.parse(parentValue).getOrElse { return }
        clearDeepSearch()
        openRoot(parent, parentValue)
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
            canGoForward = forwardStack.isNotEmpty(),
            hasMore = cachedEntries.size > PAGE_SIZE,
            canCreateDirectory = cachedSnapshot?.parentWritable == true && !RootPathRiskPolicy.isProtected(path),
            locationTarget = locationTarget,
            displayMode = previousState.displayMode,
            sortSpec = previousState.sortSpec,
            searchQuery = previousState.searchQuery,
            bookmarks = previousState.bookmarks,
            currentPathBookmarked = previousState.bookmarks.any { it.path == path },
            selectedEntries = restoredSelection?.entries?.toSet()
                ?: restoredCopySelection?.entries?.toSet()
                ?: emptySet(),
            moveSelection = previousState.moveSelection,
            movingFile = previousState.movingFile,
            moveCompletedCount = previousState.moveCompletedCount,
            moveTotalCount = previousState.moveTotalCount,
            movedOutput = previousState.movedOutput,
            fileMoveError = previousState.fileMoveError,
            copySelection = previousState.copySelection,
            copyingFile = previousState.copyingFile,
            copyCompletedCount = previousState.copyCompletedCount,
            copyTotalCount = previousState.copyTotalCount,
            copiedOutput = previousState.copiedOutput,
            fileCopyError = previousState.fileCopyError,
            renamingFile = previousState.renamingFile,
            renamedOutput = previousState.renamedOutput,
            fileRenameError = previousState.fileRenameError,
            creatingFile = previousState.creatingFile,
            createdFile = previousState.createdFile,
            createFileError = previousState.createFileError,
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
            persistSession(path)
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

    private fun persistSession(currentPath: RootPath) {
        val store = browserSessionStore ?: return
        val session = BrowserSession(
            rootPath = selectedRootPath,
            rootTitle = mutableState.value.rootTitle,
            currentPath = currentPath,
            backStack = stack.toList(),
            forwardStack = forwardStack.toList(),
        )
        sessionSaveJob?.cancel()
        sessionSaveJob = viewModelScope.launch(ioDispatcher) {
            runCatching { store.save(session) }
        }
    }

    private fun openExternalFile(
        entry: DirectoryEntry,
        request: Long,
        chooser: Boolean = false,
    ) {
        mutableState.value = mutableState.value.copy(
            openingFile = true,
            externalFileToOpen = null,
            externalOpenChooser = false,
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
                            externalOpenChooser = chooser,
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

    fun createFile(rawName: String) {
        if (createFileJob?.isActive == true || fileOperationBusy()) return
        if (!mutableState.value.canCreateDirectory) {
            mutableState.value = mutableState.value.copy(
                createFileError = BrowserOperationError(ErrorCode.NOT_WRITABLE, "目录不可写"),
            )
            return
        }
        val name = EntryName.parse(rawName).getOrElse {
            mutableState.value = mutableState.value.copy(
                createFileError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "文件名称无效"),
            )
            return
        }
        val parent = mutableState.value.currentPath
        mutableState.value = mutableState.value.copy(
            creatingFile = true,
            createdFile = null,
            createFileError = null,
            locationTarget = null,
        )
        createFileJob = viewModelScope.launch {
            try {
                when (val result = withContext(ioDispatcher) { rootFileSystem.createFileNoReplace(parent, name) }) {
                    is OperationResult.Failure -> if (mutableState.value.currentPath == parent) {
                        mutableState.value = mutableState.value.copy(
                            creatingFile = false,
                            createFileError = BrowserOperationError(result.code, result.userMessage),
                        )
                    }
                    is OperationResult.Success -> if (mutableState.value.currentPath == parent) {
                        mutableState.value = mutableState.value.copy(
                            creatingFile = false,
                            createdFile = result.value,
                        )
                        load(parent, result.value.path)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (mutableState.value.currentPath == parent) {
                    mutableState.value = mutableState.value.copy(creatingFile = false)
                }
                throw cancelled
            } catch (_: Exception) {
                if (mutableState.value.currentPath == parent) {
                    mutableState.value = mutableState.value.copy(
                        creatingFile = false,
                        createFileError = BrowserOperationError(ErrorCode.COMMAND_FAILED, "新建文件失败"),
                    )
                }
            }
        }
    }

    fun consumeCreatedFile() {
        mutableState.value = mutableState.value.copy(createdFile = null)
    }

    fun dismissCreateFileError() {
        mutableState.value = mutableState.value.copy(createFileError = null)
    }

    fun dismissCreateDirectoryError() {
        mutableState.value = mutableState.value.copy(createDirectoryError = null)
    }

    private fun cancelExternalOpen(): Long {
        openFileGeneration += 1L
        openFileJob?.cancel()
        mutableState.value.externalFileToOpen?.let(revokeExport)
        mutableState.value = mutableState.value.copy(
            openingFile = false,
            externalFileToOpen = null,
            externalOpenChooser = false,
        )
        return openFileGeneration
    }

    private fun cancelExternalShare(): Long {
        shareFileGeneration += 1L
        shareFileJob?.cancel()
        mutableState.value.externalFilesToShare.forEach(revokeExport)
        mutableState.value = mutableState.value.copy(
            sharingFile = false,
            externalFilesToShare = emptyList(),
        )
        return shareFileGeneration
    }

    private fun displayTitle(path: RootPath): String = when {
        path == selectedRootPath && path.value == "/" -> "/"
        path == selectedRootPath -> mutableState.value.rootTitle
        else -> path.value.substringAfterLast('/').ifEmpty { "/" }
    }

    private fun selectedEntriesInDirectoryOrder(): List<DirectoryEntry> {
        val selected = mutableState.value.selectedEntries
        return mutableState.value.allEntries.filter { it in selected }
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

private sealed class PendingConflict {
    abstract val index: Int
    abstract val completed: List<DirectoryEntry>
    abstract val persistentAction: ConflictAction?
    abstract val totalCount: Int
    abstract val taskId: String?

    data class Move(
        val selection: BrowserMoveSelection,
        val targetDirectory: RootPath,
        override val index: Int,
        override val completed: List<DirectoryEntry>,
        override val persistentAction: ConflictAction?,
        override val taskId: String?,
    ) : PendingConflict() {
        override val totalCount: Int get() = selection.entries.size
    }

    data class Copy(
        val selection: BrowserCopySelection,
        val targetDirectory: RootPath,
        override val index: Int,
        override val completed: List<DirectoryEntry>,
        override val persistentAction: ConflictAction?,
        override val taskId: String?,
    ) : PendingConflict() {
        override val totalCount: Int get() = selection.entries.size
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
