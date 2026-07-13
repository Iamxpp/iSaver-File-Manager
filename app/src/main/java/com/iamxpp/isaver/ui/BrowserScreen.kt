package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FileGridCell
import com.iamxpp.isaver.ui.files.FileListRow
import com.iamxpp.isaver.ui.files.FilesGrid
import com.iamxpp.isaver.ui.files.FilesOverflowMenu
import com.iamxpp.isaver.ui.files.FilesPageHeader
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortSpec
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText
import java.text.DateFormat
import java.util.Date

@Composable
fun BrowserScreen(
    state: BrowserUiState,
    onEnterDirectory: (DirectoryEntry) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchQueryChange: (String) -> Unit = {},
    onDisplayModeChange: (DisplayMode) -> Unit = {},
    onSortChange: (SortSpec) -> Unit = {},
    onCreateDirectory: (String) -> Unit = {},
    onDismissPresentationError: () -> Unit = {},
    onDismissCreateError: () -> Unit = {},
    onCompress: (() -> Unit)? = null,
    onConnectServer: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var createDialogVisible by remember { mutableStateOf(false) }
    var unavailableFeature by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().background(ISaverBackground)) {
        FilesPageHeader(
            title = state.title,
            query = state.searchQuery,
            onQueryChange = onSearchQueryChange,
            onBack = if (state.canGoBack) onBack else null,
            onOverflow = { menuExpanded = true },
            topBarTestTag = "browser-top-bar",
            searchTestTag = "browser-search",
            overflowMenuContent = {
                FilesOverflowMenu(
                    expanded = menuExpanded,
                    displayMode = state.displayMode,
                    sortSpec = state.sortSpec,
                    onDismissRequest = { menuExpanded = false },
                    onDisplayModeChange = {
                        menuExpanded = false
                        onDisplayModeChange(it)
                    },
                    onSortFieldChange = {
                        menuExpanded = false
                        onSortChange(state.sortSpec.copy(field = it))
                    },
                    onSortDirectionToggle = {
                        menuExpanded = false
                        onSortChange(
                            state.sortSpec.copy(
                                direction = if (state.sortSpec.direction == SortDirection.ASCENDING) {
                                    SortDirection.DESCENDING
                                } else {
                                    SortDirection.ASCENDING
                                },
                            ),
                        )
                    },
                    onCreateFolder = {
                        menuExpanded = false
                        createDialogVisible = true
                    },
                    onCompress = {
                        menuExpanded = false
                        onCompress?.invoke() ?: run { unavailableFeature = "压缩文件将在后续阶段提供" }
                    },
                    onConnectServer = {
                        menuExpanded = false
                        onConnectServer?.invoke() ?: run { unavailableFeature = "连接服务器将在后续阶段提供" }
                    },
                    canCreateFolder = state.canCreateDirectory && !state.creatingDirectory,
                    canCompress = true,
                    canConnectServer = true,
                )
            },
        )
        BrowserContent(
            state = state,
            onEnterDirectory = onEnterDirectory,
            onRetry = onRetry,
            onLoadMore = onLoadMore,
            modifier = Modifier.weight(1f),
        )
    }

    if (createDialogVisible) {
        CreateFolderDialog(
            onDismiss = { createDialogVisible = false },
            onConfirm = {
                createDialogVisible = false
                onCreateDirectory(it)
            },
        )
    }
    state.createDirectoryError?.let {
        MessageDialog(it.userMessage, "关闭", onDismissCreateError)
    }
    state.presentationError?.let {
        MessageDialog(it, "关闭", onDismissPresentationError)
    }
    unavailableFeature?.let { message ->
        MessageDialog(message, "知道了") { unavailableFeature = null }
    }
}

@Composable
private fun BrowserContent(
    state: BrowserUiState,
    onEnterDirectory: (DirectoryEntry) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ISaverCard)) {
        when {
            state.loading -> StatusContent("正在读取目录", true)
            state.errorMessage != null -> Column(Modifier.padding(24.dp)) {
                Text(state.errorMessage)
                Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("重试") }
            }
            state.empty -> StatusContent("此目录为空")
            state.displayMode == DisplayMode.GRID -> FilesGrid(
                items = state.entries,
                key = { it.path.value },
            ) { entry ->
                FileGridCell(
                    entry = entry,
                    displayName = entry.name,
                    metadata = metadata(entry),
                    enabled = entry.type == EntryType.DIRECTORY && entry.readable && !entry.symbolicLink,
                    onClick = { onEnterDirectory(entry) },
                    modifier = targetModifier(state, entry),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.entries, key = { it.path.value }) { entry ->
                    FileListRow(
                        entry = entry,
                        displayName = entry.name,
                        metadata = metadata(entry),
                        enabled = entry.type == EntryType.DIRECTORY && entry.readable && !entry.symbolicLink,
                        onClick = { onEnterDirectory(entry) },
                        modifier = targetModifier(state, entry),
                    )
                }
                if (state.hasMore) {
                    item { TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
                }
            }
        }
        if (state.creatingDirectory) {
            Text(
                "正在新建文件夹",
                color = ISaverSecondaryText,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

private fun targetModifier(state: BrowserUiState, entry: DirectoryEntry): Modifier =
    if (state.locationTarget == entry.path) {
        Modifier.semantics { contentDescription = "新建文件夹定位目标" }
    } else {
        Modifier
    }

@Composable
private fun StatusContent(message: String, progress: Boolean = false) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        if (progress) CircularProgressIndicator(color = ISaverBlue)
        Text(message, color = ISaverSecondaryText, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun CreateFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember {
        mutableStateOf(TextFieldValue("未命名文件夹", selection = TextRange(0, "未命名文件夹".length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.semantics { contentDescription = "文件夹名称" },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MessageDialog(message: String, button: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(button) } },
    )
}

private fun metadata(entry: DirectoryEntry): String {
    val date = entry.modifiedAtEpochSeconds?.let {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1_000))
    }
    val detail = when {
        entry.type == EntryType.DIRECTORY -> "文件夹"
        entry.sizeBytes != null -> formatSize(entry.sizeBytes)
        else -> "文件"
    }
    return listOfNotNull(date, detail).joinToString(" · ")
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824 -> "${bytes / 1_048_576} MB"
    else -> "${bytes / 1_073_741_824} GB"
}
