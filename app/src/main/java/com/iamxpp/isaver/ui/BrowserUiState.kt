package com.iamxpp.isaver.ui

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.export.ExternalFileGrant
import com.iamxpp.isaver.fileops.ConflictAction
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.files.SortSpec

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
    val presentationError: String? = null,
    val selectedEntries: Set<DirectoryEntry> = emptySet(),
    val fileInfo: DirectoryEntry? = null,
    val archiveToOpen: DirectoryEntry? = null,
    val openingFile: Boolean = false,
    val externalFileToOpen: ExternalFileGrant? = null,
    val externalOpenChooser: Boolean = false,
    val fileOpenError: BrowserOperationError? = null,
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
    val availableActions: Set<ConflictAction> = setOf(
        ConflictAction.CANCEL,
        ConflictAction.SKIP,
        ConflictAction.KEEP_BOTH,
    ),
)
