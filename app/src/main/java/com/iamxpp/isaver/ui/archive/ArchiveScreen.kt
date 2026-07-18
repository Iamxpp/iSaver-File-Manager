package com.iamxpp.isaver.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.archive.ArchiveFormat
import com.iamxpp.isaver.archive.ArchiveNode
import com.iamxpp.isaver.archive.ArchiveProgress
import com.iamxpp.isaver.archive.ArchiveState
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FileGridCell
import com.iamxpp.isaver.ui.files.FileListRow
import com.iamxpp.isaver.ui.files.FilesGrid
import com.iamxpp.isaver.ui.files.FilesPageHeader
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText

@Composable
fun ArchiveScreen(
    state: ArchiveUiState,
    onBack: () -> Unit,
    onEnter: (ArchiveNode) -> Unit,
    onQueryChange: (String) -> Unit,
    onDisplayModeChange: (DisplayMode) -> Unit,
    onChooseExtractionTarget: () -> Unit,
    onRetry: () -> Unit,
    onCancelExtraction: () -> Unit,
    onDismissOperation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(ISaverBackground)) {
        FilesPageHeader(
            title = state.title,
            query = state.searchQuery,
            onQueryChange = onQueryChange,
            onBack = onBack,
            onOverflow = { menuExpanded = true },
            topBarTestTag = "archive-top-bar",
            searchTestTag = "archive-search",
            overflowMenuContent = {
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(ISaverCard),
                ) {
                    DropdownMenuItem(
                        text = { Text("解压", color = ISaverPrimaryText) },
                        enabled = state.listing != null && state.operation == null,
                        onClick = {
                            menuExpanded = false
                            onChooseExtractionTarget()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("列表", color = ISaverPrimaryText) },
                        onClick = {
                            menuExpanded = false
                            onDisplayModeChange(DisplayMode.LIST)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("图标", color = ISaverPrimaryText) },
                        onClick = {
                            menuExpanded = false
                            onDisplayModeChange(DisplayMode.GRID)
                        },
                    )
                }
            },
        )
        state.listing?.let { listing ->
            Text(
                listing.format.label,
                color = ISaverBlue,
                modifier = Modifier.fillMaxWidth().background(ISaverCard)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        ArchiveContent(state, onEnter, onRetry, Modifier.weight(1f))
    }
    state.operation?.let { operation ->
        ArchiveOperationDialog(operation, onCancelExtraction, onDismissOperation)
    }
}

@Composable
private fun ArchiveContent(
    state: ArchiveUiState,
    onEnter: (ArchiveNode) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().background(ISaverCard)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = ISaverBlue)
            state.errorMessage != null -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(state.errorMessage, color = ISaverSecondaryText)
                TextButton(onClick = onRetry) { Text("重试") }
            }
            state.empty -> Text("压缩包为空", color = ISaverSecondaryText, modifier = Modifier.align(Alignment.Center))
            state.displayMode == DisplayMode.GRID -> FilesGrid(
                items = state.visibleNodes,
                key = ArchiveNode::path,
            ) { node ->
                FileGridCell(
                    entry = node.asDirectoryEntry(state.source),
                    displayName = node.name,
                    metadata = node.metadata(),
                    onClick = { if (node.directory) onEnter(node) },
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.visibleNodes, key = ArchiveNode::path) { node ->
                    FileListRow(
                        entry = node.asDirectoryEntry(state.source),
                        displayName = node.name,
                        metadata = node.metadata(),
                        onClick = { if (node.directory) onEnter(node) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchiveOperationDialog(
    operation: ArchiveState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancellable = operation is ArchiveState.Preparing || operation is ArchiveState.Running
    AlertDialog(
        onDismissRequest = {
            if (operation is ArchiveState.Success || operation is ArchiveState.Failure) onDismiss()
        },
        title = {
            Text(
                when (operation) {
                    ArchiveState.Preparing, is ArchiveState.Running -> "正在解压"
                    is ArchiveState.Publishing -> "正在完成"
                    ArchiveState.Cleaning -> "正在清理"
                    ArchiveState.Finalizing -> "正在完成"
                    is ArchiveState.Success -> "解压完成"
                    is ArchiveState.Failure -> "解压失败"
                },
            )
        },
        text = { Text(operation.message(), color = ISaverSecondaryText) },
        confirmButton = {
            if (operation is ArchiveState.Success || operation is ArchiveState.Failure) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (cancellable) TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

private fun ArchiveState.message(): String = when (this) {
    ArchiveState.Preparing -> "正在准备压缩包"
    is ArchiveState.Running -> when (val current = progress) {
        ArchiveProgress.Preparing -> "正在准备"
        is ArchiveProgress.Entry -> listOf(
            current.path,
            if (current.totalBytes == null) "${current.completedBytes} B" else
                "${current.completedBytes} / ${current.totalBytes} B",
        ).joinToString("\n")
        is ArchiveProgress.Publishing -> "${current.completedEntries} / ${current.totalEntries ?: "—"} 项"
    }
    is ArchiveState.Publishing -> path
    ArchiveState.Cleaning -> "正在清理解压临时目录"
    ArchiveState.Finalizing -> "正在原子发布解压目录"
    is ArchiveState.Success -> "${entryCount} 项 · ${expandedBytes} B"
    is ArchiveState.Failure -> message
}

private fun ArchiveNode.asDirectoryEntry(source: RootPath?): DirectoryEntry = DirectoryEntry(
    path = source ?: RootPath.parse("/").getOrThrow(),
    name = name,
    type = if (directory) EntryType.DIRECTORY else EntryType.FILE,
    sizeBytes = sizeBytes,
    modifiedAtEpochSeconds = null,
    readable = true,
    writable = false,
    symbolicLink = false,
)

private fun ArchiveNode.metadata(): String = when {
    directory -> "文件夹"
    sizeBytes == null -> "文件"
    compressedSizeBytes == null -> "${formatSize(sizeBytes)}"
    else -> "${formatSize(sizeBytes)} · 压缩后 ${formatSize(compressedSizeBytes)}"
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "${bytes / 1_024L} KB"
    bytes < 1_073_741_824L -> "${bytes / 1_048_576L} MB"
    else -> "${bytes / 1_073_741_824L} GB"
}

private val ArchiveFormat.label: String
    get() = when (this) {
        ArchiveFormat.ZIP -> "ZIP"
        ArchiveFormat.TAR -> "TAR"
        ArchiveFormat.TAR_GZ -> "TAR.GZ"
        ArchiveFormat.SEVEN_Z -> "7Z"
        ArchiveFormat.RAR -> "RAR"
    }
