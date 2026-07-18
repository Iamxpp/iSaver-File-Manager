package com.iamxpp.isaver.ui.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.recent.RecentActivity
import com.iamxpp.isaver.recent.RecentItemType
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FileGridCell
import com.iamxpp.isaver.ui.files.FileListRow
import com.iamxpp.isaver.ui.files.FilesGrid
import com.iamxpp.isaver.ui.files.FilesPageHeader
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText

@Composable
fun RecentScreen(
    state: RecentUiState,
    displayMode: DisplayMode,
    onOpen: (RecentUiItem) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val items = state.items.filter { it.item.displayName.contains(query, ignoreCase = true) }
    Column(modifier.fillMaxSize().background(ISaverBackground)) {
        FilesPageHeader(
            title = "最近项目",
            query = query,
            onQueryChange = { query = it },
            onOverflow = onRefresh,
            topBarTestTag = "recent-top-bar",
            searchTestTag = "recent-search",
        )
        Box(Modifier.fillMaxSize().background(ISaverCard)) {
            when {
                items.isEmpty() && !state.refreshing -> Text(
                    "暂无最近项目",
                    color = ISaverSecondaryText,
                    modifier = Modifier.align(Alignment.Center),
                )
                displayMode == DisplayMode.GRID -> FilesGrid(
                    items = items,
                    key = { it.item.path.value },
                ) { item ->
                    val entry = item.displayEntry()
                    FileGridCell(
                        entry = entry,
                        displayName = item.item.displayName,
                        metadata = item.metadata(),
                        enabled = item.available,
                        onClick = { onOpen(item) },
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.item.path.value }) { item ->
                        val entry = item.displayEntry()
                        FileListRow(
                            entry = entry,
                            displayName = item.item.displayName,
                            metadata = item.metadata(),
                            enabled = item.available,
                            onClick = { onOpen(item) },
                        )
                    }
                }
            }
            if (state.refreshing) {
                Text(
                    "正在检查最近项目…",
                    color = ISaverSecondaryText,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
    }
}

private fun RecentUiItem.metadata(): String = when (availability) {
    RecentAvailability.Checking -> "${item.activity.label()} · $status"
    is RecentAvailability.Unavailable -> status
    is RecentAvailability.Available -> item.activity.label()
}

private fun RecentActivity.label(): String = when (this) {
    RecentActivity.ACCESSED -> "已访问"
    RecentActivity.SAVED -> "已保存"
    RecentActivity.COMPRESSED -> "已压缩"
    RecentActivity.EXTRACTED -> "已解压"
}

private fun RecentUiItem.displayEntry(): DirectoryEntry =
    (availability as? RecentAvailability.Available)?.entry ?: DirectoryEntry(
        path = item.path,
        name = item.displayName,
        type = when (item.type) {
            RecentItemType.DIRECTORY -> EntryType.DIRECTORY
            RecentItemType.FILE, RecentItemType.ARCHIVE -> EntryType.FILE
        },
        sizeBytes = null,
        modifiedAtEpochSeconds = null,
        readable = false,
        writable = false,
        symbolicLink = false,
    )
