package com.iamxpp.isaver.ui

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.export.ExternalFileGrant
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
    val fileOpenError: BrowserOperationError? = null,
    val sharingFile: Boolean = false,
    val externalFileToShare: ExternalFileGrant? = null,
    val fileShareError: BrowserOperationError? = null,
    val compressing: Boolean = false,
    val compressionMessage: String? = null,
) {
    val empty: Boolean get() = !loading && !refreshing && errorMessage == null && totalCount == 0
    val selectionMode: Boolean get() = selectedEntries.isNotEmpty()
}

data class BrowserOperationError(
    val code: ErrorCode,
    val userMessage: String,
)
