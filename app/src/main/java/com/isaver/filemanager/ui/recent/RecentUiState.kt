package com.isaver.filemanager.ui.recent

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.recent.RecentItem

data class RecentUiState(
    val items: List<RecentUiItem> = emptyList(),
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
    val fileInfo: DirectoryEntry? = null,
)

data class RecentUiItem(
    val item: RecentItem,
    val availability: RecentAvailability = RecentAvailability.Checking,
) {
    val available: Boolean get() = availability is RecentAvailability.Available
    val status: String
        get() = when (val current = availability) {
            RecentAvailability.Checking -> "正在检查…"
            is RecentAvailability.Available -> if (current.entry.readable) "可用" else "不可读"
            is RecentAvailability.Unavailable -> "项目不可用"
        }
}

sealed interface RecentAvailability {
    data object Checking : RecentAvailability
    data class Available(val entry: DirectoryEntry) : RecentAvailability
    data class Unavailable(val reason: String) : RecentAvailability
}

sealed interface RecentOpenTarget {
    data class Directory(val path: RootPath, val title: String) : RecentOpenTarget
    data class Archive(val path: RootPath, val title: String) : RecentOpenTarget
    data class File(val entry: DirectoryEntry) : RecentOpenTarget
}
