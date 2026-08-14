package com.isaver.filemanager.ui

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.export.ExternalFileGrant
import com.isaver.filemanager.fileops.ConflictAction
import com.isaver.filemanager.fileops.BatchRenamePlan
import com.isaver.filemanager.ui.files.DisplayMode
import com.isaver.filemanager.ui.files.SortDirection
import com.isaver.filemanager.ui.files.SortField
import com.isaver.filemanager.tasks.OperationTask
import com.isaver.filemanager.trash.TrashItem
import com.isaver.filemanager.ui.files.SortSpec
import com.isaver.filemanager.bookmarks.Bookmark
import com.isaver.filemanager.data.root.RootFileMetadata
import com.isaver.filemanager.fileops.ChecksumAlgorithm
import com.isaver.filemanager.fileops.FilePermissions
import com.isaver.filemanager.search.LocalSearchCriteria
import com.isaver.filemanager.preview.PreviewContent

data class BrowserUiState(
    val currentPath: RootPath,
    val rootTitle: String = "内部存储",
    val title: String = rootTitle,
    val allEntries: List<DirectoryEntry> = emptyList(),
    val entries: List<DirectoryEntry> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val hasMore: Boolean = false,
    val canCreateDirectory: Boolean = false,
    val creatingDirectory: Boolean = false,
    val createDirectoryError: BrowserOperationError? = null,
    val creatingFile: Boolean = false,
    val createdFile: DirectoryEntry? = null,
    val createFileError: BrowserOperationError? = null,
    val locationTarget: RootPath? = null,
    val displayMode: DisplayMode = DisplayMode.LIST,
    val sortSpec: SortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
    val searchQuery: String = "",
    val deepSearchCriteria: LocalSearchCriteria? = null,
    val deepSearchResults: List<DirectoryEntry> = emptyList(),
    val deepSearchRunning: Boolean = false,
    val deepSearchScannedDirectories: Int = 0,
    val deepSearchScannedEntries: Int = 0,
    val deepSearchSkippedDirectories: Int = 0,
    val deepSearchTruncated: Boolean = false,
    val deepSearchError: String? = null,
    val presentationError: String? = null,
    val selectedEntries: Set<DirectoryEntry> = emptySet(),
    val fileInfo: DirectoryEntry? = null,
    val fileMetadata: RootFileMetadata? = null,
    val fileMetadataLoading: Boolean = false,
    val fileMetadataError: String? = null,
    val permissionRunning: Boolean = false,
    val permissionError: BrowserOperationError? = null,
    val permissionConfirmation: FilePermissions? = null,
    val checksumRunning: Boolean = false,
    val checksumAlgorithm: ChecksumAlgorithm = ChecksumAlgorithm.SHA256,
    val checksumValue: String? = null,
    val checksumError: BrowserOperationError? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val currentPathBookmarked: Boolean = false,
    val archiveToOpen: DirectoryEntry? = null,
    val openingFile: Boolean = false,
    val externalFileToOpen: ExternalFileGrant? = null,
    val externalOpenChooser: Boolean = false,
    val fileOpenError: BrowserOperationError? = null,
    val preview: PreviewContent? = null,
    val previewEntry: DirectoryEntry? = null,
    val previewLoading: Boolean = false,
    val previewError: BrowserOperationError? = null,
    val sharingFile: Boolean = false,
    val externalFilesToShare: List<ExternalFileGrant> = emptyList(),
    val fileShareError: BrowserOperationError? = null,
    val moveSelection: BrowserMoveSelection? = null,
    val movingFile: Boolean = false,
    val moveCompletedCount: Int = 0,
    val moveTotalCount: Int = 0,
    val movedOutput: DirectoryEntry? = null,
    val fileMoveError: BrowserOperationError? = null,
    val copySelection: BrowserCopySelection? = null,
    val copyingFile: Boolean = false,
    val copyCompletedCount: Int = 0,
    val copyTotalCount: Int = 0,
    val copiedOutput: DirectoryEntry? = null,
    val fileCopyError: BrowserOperationError? = null,
    val conflictPrompt: BrowserConflictPrompt? = null,
    val renamingFile: Boolean = false,
    val renamedOutput: DirectoryEntry? = null,
    val fileRenameError: BrowserOperationError? = null,
    val batchRenamePlan: BatchRenamePlan? = null,
    val batchRenameError: BrowserOperationError? = null,
    val operationTasks: List<OperationTask> = emptyList(),
    val controllableTaskId: String? = null,
    val controllableTaskPaused: Boolean = false,
    val trashItems: List<TrashItem> = emptyList(),
    val deletingEntry: Boolean = false,
    val trashError: BrowserOperationError? = null,
    val restoreConflictItem: TrashItem? = null,
    val compressing: Boolean = false,
    val compressionMessage: String? = null,
) {
    val empty: Boolean get() = !loading && !refreshing && errorMessage == null && totalCount == 0
    val selectionMode: Boolean get() = selectedEntries.isNotEmpty()
    val externalFileToShare: ExternalFileGrant? get() = externalFilesToShare.singleOrNull()
}

data class BrowserMoveSelection(
    val entries: List<DirectoryEntry>,
    val sourceDirectory: RootPath,
) {
    constructor(entry: DirectoryEntry, sourceDirectory: RootPath) : this(listOf(entry), sourceDirectory)
}

data class BrowserCopySelection(
    val entries: List<DirectoryEntry>,
    val sourceDirectory: RootPath,
) {
    constructor(entry: DirectoryEntry, sourceDirectory: RootPath) : this(listOf(entry), sourceDirectory)
}

data class BrowserOperationError(
    val code: ErrorCode,
    val userMessage: String,
)

enum class BrowserConflictOperation { MOVE, COPY }

data class BrowserConflictPrompt(
    val operation: BrowserConflictOperation,
    val entryName: String,
    val completedCount: Int,
    val totalCount: Int,
    val entryType: EntryType = EntryType.FILE,
    val availableActions: Set<ConflictAction> = setOf(
        ConflictAction.CANCEL,
        ConflictAction.SKIP,
        ConflictAction.KEEP_BOTH,
        ConflictAction.REPLACE,
        ConflictAction.MERGE,
    ),
)
