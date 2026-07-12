package com.iamxpp.isaver.ui

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.RootPath

data class BrowserUiState(
    val currentPath: RootPath,
    val rootTitle: String = "内部存储",
    val allEntries: List<DirectoryEntry> = emptyList(),
    val entries: List<DirectoryEntry> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val canGoBack: Boolean = false,
    val hasMore: Boolean = false,
    val canCreateDirectory: Boolean = false,
    val creatingDirectory: Boolean = false,
    val createDirectoryError: BrowserOperationError? = null,
    val locationTarget: RootPath? = null,
) {
    val empty: Boolean get() = !loading && errorMessage == null && totalCount == 0
}

data class BrowserOperationError(
    val code: ErrorCode,
    val userMessage: String,
)
