package com.iamxpp.isaver.ui

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.RootPath

data class BrowserUiState(
    val currentPath: RootPath,
    val allEntries: List<DirectoryEntry> = emptyList(),
    val entries: List<DirectoryEntry> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val canGoBack: Boolean = false,
    val hasMore: Boolean = false,
) {
    val empty: Boolean get() = !loading && errorMessage == null && totalCount == 0
}
