package com.isaver.filemanager.ui.archive

import com.isaver.filemanager.archive.ArchiveListing
import com.isaver.filemanager.archive.ArchiveNode
import com.isaver.filemanager.archive.ArchiveState
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.ui.files.DisplayMode
import com.isaver.filemanager.ui.files.HomeTab

data class ArchiveUiState(
    val source: RootPath? = null,
    val sourceName: String = "",
    val sourceTab: HomeTab = HomeTab.VIEWS,
    val loading: Boolean = false,
    val listing: ArchiveListing? = null,
    val prefix: String = "",
    val nodes: List<ArchiveNode> = emptyList(),
    val searchQuery: String = "",
    val displayMode: DisplayMode = DisplayMode.LIST,
    val errorMessage: String? = null,
    val operation: ArchiveState? = null,
    val extractionTargetRequested: Boolean = false,
) {
    val visibleNodes: List<ArchiveNode>
        get() = nodes.filter { it.name.contains(searchQuery, ignoreCase = true) }

    val title: String
        get() = prefix.substringAfterLast('/').ifEmpty { sourceName }

    val empty: Boolean
        get() = !loading && errorMessage == null && listing != null && visibleNodes.isEmpty()
}

enum class ArchiveBackResult {
    NAVIGATED,
    CLOSE_ARCHIVE,
}
