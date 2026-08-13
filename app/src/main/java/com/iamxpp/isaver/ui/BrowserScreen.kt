package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPathRiskPolicy
import com.iamxpp.isaver.fileops.ConflictAction
import com.iamxpp.isaver.fileops.BatchRenameCase
import com.iamxpp.isaver.fileops.BatchRenameMode
import com.iamxpp.isaver.fileops.BatchRenameRule
import com.iamxpp.isaver.remote.RemoteConnectionDraft
import com.iamxpp.isaver.remote.RemoteConnectionUiState
import com.iamxpp.isaver.remote.RemoteProtocol
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FileGridCell
import com.iamxpp.isaver.ui.files.FileListRow
import com.iamxpp.isaver.ui.files.FilesGrid
import com.iamxpp.isaver.ui.files.FilesOverflowMenu
import com.iamxpp.isaver.ui.files.FilesPageHeader
import com.iamxpp.isaver.ui.files.FilesSaveAction
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortSpec
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverDivider
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText
import com.iamxpp.isaver.tasks.OperationTask
import com.iamxpp.isaver.tasks.OperationTaskState
import com.iamxpp.isaver.search.LocalSearchCriteria
import com.iamxpp.isaver.search.SearchEntryType
import com.iamxpp.isaver.trash.TrashItem
import com.iamxpp.isaver.trash.RestoreConflictAction
import com.iamxpp.isaver.preview.PreviewContent
import com.iamxpp.isaver.archive.ArchiveFormat
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import com.iamxpp.isaver.trash.TrashItemState
import java.text.DateFormat
import java.util.Date

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BrowserScreen(
    state: BrowserUiState,
    onEnterDirectory: (DirectoryEntry) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit = {},
    onAddCurrentToVirtualView: (() -> Unit)? = null,
    onAddEntryToVirtualView: ((DirectoryEntry) -> Unit)? = null,
    onStartDeepSearch: (LocalSearchCriteria) -> Unit = {},
    onCancelDeepSearch: () -> Unit = {},
    onClearDeepSearch: () -> Unit = {},
    onOpenDeepSearchResultLocation: (DirectoryEntry) -> Unit = {},
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchQueryChange: (String) -> Unit = {},
    onDisplayModeChange: (DisplayMode) -> Unit = {},
    onSortChange: (SortSpec) -> Unit = {},
    onCreateDirectory: (String) -> Unit = {},
    onCreateFile: ((String) -> Unit)? = null,
    onToggleSelection: (DirectoryEntry) -> Unit = {},
    onSelectAllVisible: () -> Unit = {},
    onInvertVisibleSelection: () -> Unit = {},
    onSelectSameType: () -> Unit = {},
    onOpenEntry: (DirectoryEntry) -> Unit = onToggleSelection,
    onOpenWithEntry: ((DirectoryEntry) -> Unit)? = null,
    onSelectEntry: (DirectoryEntry) -> Unit = onToggleSelection,
    onClearSelection: () -> Unit = {},
    onDismissFileInfo: () -> Unit = {},
    onShowFileInfo: (DirectoryEntry) -> Unit = {},
    onCalculateChecksum: () -> Unit = {},
    onChecksumAlgorithmChange: (com.iamxpp.isaver.fileops.ChecksumAlgorithm) -> Unit = {},
    onDismissFileOpenError: () -> Unit = {},
    onDismissPreview: () -> Unit = {},
    onShareEntry: ((DirectoryEntry) -> Unit)? = null,
    onShareSelection: (() -> Unit)? = null,
    onRecycleSelection: ((List<DirectoryEntry>) -> Unit)? = null,
    onDismissFileShareError: () -> Unit = {},
    onMoveEntry: ((DirectoryEntry) -> Unit)? = null,
    onMoveSelection: (() -> Unit)? = null,
    onDismissFileMoveError: () -> Unit = {},
    onCopyEntry: ((DirectoryEntry) -> Unit)? = null,
    onCopySelection: (() -> Unit)? = null,
    onDismissFileCopyError: () -> Unit = {},
    onResolveConflict: (ConflictAction, Boolean) -> Unit = { _, _ -> },
    onRenameEntry: ((DirectoryEntry, String) -> Unit)? = null,
    onPreviewBatchRename: ((BatchRenameRule) -> Unit)? = null,
    onExecuteBatchRename: (() -> Unit)? = null,
    onDismissBatchRename: () -> Unit = {},
    onClearFinishedTasks: () -> Unit = {},
    onPauseTask: (String) -> Unit = {},
    onResumeTask: (String) -> Unit = {},
    onCancelTask: (String) -> Unit = {},
    onRecycleEntry: ((DirectoryEntry) -> Unit)? = null,
    onDeleteEntryPermanently: ((DirectoryEntry) -> Unit)? = null,
    onRestoreTrashItem: (TrashItem) -> Unit = {},
    onRestoreTrashItemWithAction: (TrashItem, RestoreConflictAction, String?) -> Unit = { _, _, _ -> },
    onDismissRestoreConflict: () -> Unit = {},
    onDeleteTrashItemPermanently: (TrashItem) -> Unit = {},
    onRestoreAllTrashItems: (List<TrashItem>) -> Unit = {},
    onClearTrash: (List<TrashItem>) -> Unit = {},
    onDismissTrashError: () -> Unit = {},
    onDismissFileRenameError: () -> Unit = {},
    onDismissCompressionMessage: () -> Unit = {},
    onDismissPresentationError: () -> Unit = {},
    onDismissCreateError: () -> Unit = {},
    onDismissCreateFileError: () -> Unit = {},
    onCompress: ((String, ArchiveFormat) -> Unit)? = null,
    onConnectServer: ((RemoteConnectionDraft) -> Unit)? = null,
    remoteConnectionState: RemoteConnectionUiState = RemoteConnectionUiState.Idle,
    onDismissRemoteMessage: () -> Unit = {},
    onRefreshRemote: () -> Unit = {},
    onCreateRemoteDirectory: (String) -> Unit = {},
    saveAction: FilesSaveAction? = null,
    fileActionsEnabled: Boolean = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var createDialogVisible by remember { mutableStateOf(false) }
    var createFileDialogVisible by remember { mutableStateOf(false) }
    var compressDialogVisible by remember { mutableStateOf(false) }
    var serverDialogVisible by remember { mutableStateOf(false) }
    var actionEntry by remember { mutableStateOf<DirectoryEntry?>(null) }
    var renameDialogEntry by remember { mutableStateOf<DirectoryEntry?>(null) }
    var batchRenameDialogVisible by remember { mutableStateOf(false) }
    var taskCenterVisible by remember { mutableStateOf(false) }
    var deepSearchVisible by remember { mutableStateOf(false) }
    var deleteEntry by remember { mutableStateOf<DirectoryEntry?>(null) }
    var batchDeleteVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.currentPath) {
        actionEntry = null
    }
    LaunchedEffect(state.selectionMode) {
        if (!state.selectionMode) batchRenameDialogVisible = false
    }

    Column(modifier.fillMaxSize().background(ISaverBackground)) {
        FilesPageHeader(
            title = state.title,
            query = state.searchQuery,
            onQueryChange = onSearchQueryChange,
            onBack = if (state.canGoBack) onBack else null,
            onOverflow = { menuExpanded = true },
            saveAction = saveAction,
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
                    onCreateFile = onCreateFile?.let {
                        {
                            menuExpanded = false
                            createFileDialogVisible = true
                        }
                    },
                    onCompress = {
                        menuExpanded = false
                        if (onCompress != null) compressDialogVisible = true
                    },
                    onConnectServer = {
                        menuExpanded = false
                        if (onConnectServer != null) serverDialogVisible = true
                    },
                    canCreateFolder = state.canCreateDirectory && !state.creatingDirectory && !state.creatingFile,
                    canCreateFile = state.canCreateDirectory && !state.creatingDirectory && !state.creatingFile,
                    canCompress = onCompress != null &&
                        state.selectedEntries.isNotEmpty() &&
                        !state.compressing,
                    canConnectServer = onConnectServer != null,
                    onAddLocation = onAddCurrentToVirtualView?.let { add ->
                        {
                            menuExpanded = false
                            add()
                        }
                    },
                    addLocationLabel = "添加当前路径到虚拟视图位置",
                    onOpenTasks = {
                        menuExpanded = false
                        taskCenterVisible = true
                    },
                    onGoForward = if (state.canGoForward) {
                        {
                            menuExpanded = false
                            onForward()
                        }
                    } else null,
                    onOpenDeepSearch = if (fileActionsEnabled && saveAction == null) {
                        {
                            menuExpanded = false
                            deepSearchVisible = true
                        }
                    } else null,
                )
            },
        )
        if (RootPathRiskPolicy.isProtected(state.currentPath)) {
            Text(
                "系统保护区域 · 只读浏览",
                color = ISaverSecondaryText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (state.selectionMode) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已选择 ${state.selectedEntries.size} 项", modifier = Modifier.weight(1f))
                    TextButton(onClick = onSelectAllVisible) { Text("全选") }
                    TextButton(onClick = onInvertVisibleSelection) { Text("反选") }
                    TextButton(
                        onClick = onSelectSameType,
                        enabled = state.selectedEntries.map { it.type }.distinct().size == 1,
                    ) { Text("同类") }
                    TextButton(onClick = onClearSelection) { Text("清除") }
                }
                if (onShareSelection != null || onMoveSelection != null || onCopySelection != null ||
                    onPreviewBatchRename != null || onRecycleSelection != null) {
                    Row(Modifier.fillMaxWidth()) {
                        onShareSelection?.let { share ->
                            TextButton(
                                enabled = !state.sharingFile,
                                onClick = share,
                                modifier = Modifier.weight(1f),
                            ) { Text("分享") }
                        }
                        onMoveSelection?.let { move ->
                            TextButton(
                                enabled = !state.movingFile,
                                onClick = move,
                                modifier = Modifier.weight(1f),
                            ) { Text("移动到") }
                        }
                        onCopySelection?.let { copy ->
                            TextButton(
                                enabled = !state.copyingFile,
                                onClick = copy,
                                modifier = Modifier.weight(1f),
                            ) { Text("复制到") }
                        }
                        if (onPreviewBatchRename != null && state.selectedEntries.size > 1) {
                            TextButton(
                                enabled = !state.renamingFile,
                                onClick = { batchRenameDialogVisible = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("批量重命名") }
                        }
                    }
                    onRecycleSelection?.let {
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                enabled = !state.deletingEntry,
                                onClick = { batchDeleteVisible = true },
                            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        BrowserContent(
            state = state,
            onEnterDirectory = onEnterDirectory,
            onRetry = onRetry,
            onLoadMore = onLoadMore,
            onToggleSelection = onToggleSelection,
            onOpenEntry = onOpenEntry,
            onSelectEntry = onSelectEntry,
            onLongPressEntry = { entry ->
                if (fileActionsEnabled) {
                    if (state.selectionMode) {
                        onSelectEntry(entry)
                    } else {
                        actionEntry = entry
                    }
                }
            },
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
    if (compressDialogVisible) {
        CompressDialog(
            onDismiss = { compressDialogVisible = false },
            onConfirm = { name, format ->
                compressDialogVisible = false
                onCompress?.invoke(name, format)
            },
        )
    }
    if (serverDialogVisible) {
        ServerConnectionDialog(
            onDismiss = { serverDialogVisible = false },
            onConfirm = { draft ->
                serverDialogVisible = false
                onConnectServer?.invoke(draft)
            },
        )
    }
    if (createFileDialogVisible) {
        CreateFileDialog(
            onDismiss = { createFileDialogVisible = false },
            onConfirm = {
                createFileDialogVisible = false
                onCreateFile?.invoke(it)
            },
        )
    }
    actionEntry?.let { entry ->
        FileActionsDialog(
            entry = entry,
            openWithVisible = onOpenWithEntry != null && entry.type == EntryType.FILE,
            openWithEnabled = !state.openingFile && entry.readable && !entry.symbolicLink,
            shareVisible = onShareEntry != null && entry.type != EntryType.OTHER,
            shareEnabled = !state.sharingFile && entry.readable && !entry.symbolicLink,
            moveVisible = onMoveEntry != null && entry.type != EntryType.OTHER,
            moveEnabled = !state.movingFile,
            copyVisible = onCopyEntry != null && entry.type != EntryType.OTHER,
             copyEnabled = !state.copyingFile,
            renameVisible = onRenameEntry != null && entry.type != EntryType.OTHER,
             renameEnabled = !state.renamingFile,
            compressVisible = onCompress != null,
            deleteVisible = onRecycleEntry != null || onDeleteEntryPermanently != null,
            deleteEnabled = !state.deletingEntry,
            onShare = {
                actionEntry = null
                onShareEntry?.invoke(entry)
            },
            onOpenWith = {
                actionEntry = null
                onOpenWithEntry?.invoke(entry)
            },
            onCompress = {
                actionEntry = null
                onClearSelection()
                onSelectEntry(entry)
                compressDialogVisible = true
            },
            onMove = {
                actionEntry = null
                onMoveEntry?.invoke(entry)
            },
             onCopy = {
                actionEntry = null
                onCopyEntry?.invoke(entry)
             },
             onRename = {
                 actionEntry = null
                 renameDialogEntry = entry
             },
            onDelete = {
                actionEntry = null
                deleteEntry = entry
            },
            onAddToVirtualView = {
                actionEntry = null
                onAddEntryToVirtualView?.invoke(entry)
                onClearSelection()
            },
            onInfo = {
                actionEntry = null
                onShowFileInfo(entry)
            },
            onSelect = {
                actionEntry = null
                onSelectEntry(entry)
            },
            onDismiss = { actionEntry = null },
        )
    }
    state.createDirectoryError?.let {
        MessageDialog(it.userMessage, "关闭", onDismissCreateError)
    }
    state.createFileError?.let {
        MessageDialog(it.userMessage, "关闭", onDismissCreateFileError)
    }
    state.presentationError?.let {
        MessageDialog(it, "关闭", onDismissPresentationError)
    }
    state.compressionMessage?.let {
        MessageDialog(it, "关闭", onDismissCompressionMessage)
    }
    state.fileInfo?.let {
        FileInfoDialog(
            entry = it,
            metadata = state.fileMetadata,
            metadataLoading = state.fileMetadataLoading,
            metadataError = state.fileMetadataError,
            checksumRunning = state.checksumRunning,
            checksumAlgorithm = state.checksumAlgorithm,
            checksumValue = state.checksumValue,
            checksumError = state.checksumError?.userMessage,
            onCalculateChecksum = onCalculateChecksum,
            onChecksumAlgorithmChange = onChecksumAlgorithmChange,
            onDismiss = onDismissFileInfo,
        )
    }
    state.fileOpenError?.let { MessageDialog(it.userMessage, "关闭", onDismissFileOpenError) }
    if (state.preview != null || state.previewLoading || state.previewError != null) {
        PreviewDialog(
            entry = state.previewEntry,
            content = state.preview,
            loading = state.previewLoading,
            error = state.previewError?.userMessage,
            onDismiss = onDismissPreview,
        )
    }
    state.fileShareError?.let { MessageDialog(it.userMessage, "关闭", onDismissFileShareError) }
    state.conflictPrompt?.let { prompt ->
        ConflictDialog(prompt, onResolveConflict)
    } ?: run {
        state.fileMoveError?.let { MessageDialog(it.userMessage, "关闭", onDismissFileMoveError) }
        state.fileCopyError?.let { MessageDialog(it.userMessage, "关闭", onDismissFileCopyError) }
    }
    state.fileRenameError?.let { MessageDialog(it.userMessage, "关闭", onDismissFileRenameError) }
    state.trashError?.let { MessageDialog(it.userMessage, "关闭", onDismissTrashError) }
    state.restoreConflictItem?.let { item ->
        RestoreConflictDialog(
            item = item,
            onKeepBoth = { onRestoreTrashItemWithAction(item, RestoreConflictAction.KEEP_BOTH, null) },
            onRename = { name -> onRestoreTrashItemWithAction(item, RestoreConflictAction.RENAME, name) },
            onDismiss = onDismissRestoreConflict,
        )
    }
    deleteEntry?.let { entry ->
        DeleteConfirmationDialog(
            entry = entry,
            sharedStorage = entry.path.value.startsWith("/storage/emulated/0/"),
            onRecycle = {
                deleteEntry = null
                onRecycleEntry?.invoke(entry)
            },
            onPermanent = {
                deleteEntry = null
                onDeleteEntryPermanently?.invoke(entry)
            },
            onDismiss = { deleteEntry = null },
        )
    }
    if (batchDeleteVisible && onRecycleSelection != null) {
        BatchDeleteConfirmationDialog(
            count = state.selectedEntries.size,
            onConfirm = {
                batchDeleteVisible = false
                onRecycleSelection(state.selectedEntries.toList())
            },
            onDismiss = { batchDeleteVisible = false },
        )
    }
    renameDialogEntry?.let { entry ->
        RenameDialog(
            initialName = entry.name,
            onDismiss = { renameDialogEntry = null },
            onConfirm = { newName ->
                renameDialogEntry = null
                onRenameEntry?.invoke(entry, newName)
            },
        )
    }
    if (batchRenameDialogVisible && onPreviewBatchRename != null && onExecuteBatchRename != null) {
        BatchRenameDialog(
            plan = state.batchRenamePlan,
            error = state.batchRenameError?.userMessage,
            running = state.renamingFile,
            onPreview = onPreviewBatchRename,
            onExecute = onExecuteBatchRename,
            onDismiss = {
                batchRenameDialogVisible = false
                onDismissBatchRename()
            },
        )
    }
    if (taskCenterVisible) {
        OperationTaskDialog(
            tasks = state.operationTasks,
            controllableTaskId = state.controllableTaskId,
            controllableTaskPaused = state.controllableTaskPaused,
            onClearFinished = onClearFinishedTasks,
            onPause = onPauseTask,
            onResume = onResumeTask,
            onCancel = onCancelTask,
            onDismiss = { taskCenterVisible = false },
        )
    }
    if (deepSearchVisible) {
        DeepSearchDialog(
            state = state,
            onStart = onStartDeepSearch,
            onCancel = onCancelDeepSearch,
            onOpenLocation = {
                deepSearchVisible = false
                onOpenDeepSearchResultLocation(it)
            },
            onDismiss = {
                if (!state.deepSearchRunning) {
                    deepSearchVisible = false
                    onClearDeepSearch()
                }
            },
        )
    }
    when (remoteConnectionState) {
        is RemoteConnectionUiState.Connected -> RemoteBrowserDialog(
            state = remoteConnectionState,
            onRefresh = onRefreshRemote,
            onCreateDirectory = onCreateRemoteDirectory,
            onDismiss = onDismissRemoteMessage,
        )
        is RemoteConnectionUiState.Error -> MessageDialog(
            remoteConnectionState.message,
            "关闭",
            onDismissRemoteMessage,
        )
        RemoteConnectionUiState.Connecting,
        RemoteConnectionUiState.Idle -> Unit
    }
}

@Composable
internal fun BrowserContent(
    state: BrowserUiState,
    onEnterDirectory: (DirectoryEntry) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onToggleSelection: (DirectoryEntry) -> Unit = {},
    onOpenEntry: (DirectoryEntry) -> Unit = onToggleSelection,
    onSelectEntry: (DirectoryEntry) -> Unit = onToggleSelection,
    onLongPressEntry: (DirectoryEntry) -> Unit = onSelectEntry,
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
                    enabled = entry.type != EntryType.DIRECTORY || (entry.readable && !entry.symbolicLink),
                    selected = entry in state.selectedEntries,
                    onClick = {
                        if (state.selectionMode) onSelectEntry(entry)
                        else if (entry.type == EntryType.DIRECTORY) onEnterDirectory(entry)
                        else onOpenEntry(entry)
                    },
                    onLongClick = { onLongPressEntry(entry) },
                    modifier = targetModifier(state, entry),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.entries, key = { it.path.value }) { entry ->
                    FileListRow(
                        entry = entry,
                        displayName = entry.name,
                        metadata = metadata(entry),
                        enabled = entry.type != EntryType.DIRECTORY || (entry.readable && !entry.symbolicLink),
                        selected = entry in state.selectedEntries,
                        onClick = {
                            if (state.selectionMode) onSelectEntry(entry)
                            else if (entry.type == EntryType.DIRECTORY) onEnterDirectory(entry)
                            else onOpenEntry(entry)
                        },
                        onLongClick = { onLongPressEntry(entry) },
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
        if (state.creatingFile) {
            Text(
                "正在新建文件",
                color = ISaverSecondaryText,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
        if (state.openingFile) {
            Text(
                "正在准备打开文件",
                color = ISaverSecondaryText,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
        if (state.sharingFile) {
            Text(
                "正在准备分享文件",
                color = ISaverSecondaryText,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
        if (state.movingFile) {
            Text(
                "正在安全移动 ${state.moveCompletedCount}/${state.moveTotalCount} 项",
                color = ISaverSecondaryText,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
        if (state.copyingFile) {
            Text(
                "正在安全复制 ${state.copyCompletedCount}/${state.copyTotalCount} 项",
                color = ISaverSecondaryText,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

@Composable
private fun RestoreConflictDialog(
    item: TrashItem,
    onKeepBoth: () -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(item.originalName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复目标已存在") },
        text = {
            Column {
                Text("回收项目仍保留在回收站，请选择恢复方式。")
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("改名恢复") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("改名恢复") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onKeepBoth) { Text("保留两者") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun PreviewDialog(
    entry: DirectoryEntry?,
    content: PreviewContent?,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry?.name ?: "文件预览", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            when {
                loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                content is PreviewContent.Text -> Text(
                    content.value,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    color = ISaverPrimaryText,
                )
                content is PreviewContent.Image -> {
                    val bitmap = remember(content.bytes) { decodePreviewBitmap(content.bytes) }
                    if (bitmap == null) {
                        Text("图片无法解码", color = MaterialTheme.colorScheme.error)
                    } else {
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription = entry?.name,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun decodePreviewBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 4096 || bounds.outHeight / sampleSize > 4096) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

@Composable
private fun FileActionsDialog(
    entry: DirectoryEntry,
    openWithVisible: Boolean,
    openWithEnabled: Boolean,
    shareVisible: Boolean,
    shareEnabled: Boolean,
    moveVisible: Boolean,
    moveEnabled: Boolean,
    copyVisible: Boolean,
    copyEnabled: Boolean,
    renameVisible: Boolean,
    renameEnabled: Boolean,
    compressVisible: Boolean,
    deleteVisible: Boolean,
    deleteEnabled: Boolean,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onCompress: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddToVirtualView: () -> Unit,
    onInfo: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = buildList {
        if (openWithVisible) add(FileAction("打开方式", openWithEnabled, onOpenWith))
        if (shareVisible) add(FileAction("分享", shareEnabled, onShare))
        if (moveVisible) add(FileAction("移动到", moveEnabled, onMove))
        if (copyVisible) add(FileAction("复制到", copyEnabled, onCopy))
        if (renameVisible) add(FileAction("重命名", renameEnabled, onRename))
        if (compressVisible) add(FileAction("压缩", true, onCompress))
        add(FileAction("添加到虚拟视图位置", true, onAddToVirtualView, fullWidth = true))
        add(FileAction("属性", true, onInfo))
        add(FileAction("多选", true, onSelect))
        if (deleteVisible) add(FileAction("删除", deleteEnabled, onDelete, destructive = true))
    }

    Dialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            color = ISaverCard,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .testTag("file-actions-dialog"),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "文件操作",
                    color = ISaverPrimaryText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = entry.name,
                    color = ISaverSecondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("file-actions-list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    actionRows(actions).forEach { rowActions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            rowActions.forEach { action ->
                                FileActionButton(action, Modifier.weight(1f))
                            }
                            if (rowActions.size == 1 && !rowActions.single().fullWidth) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private data class FileAction(
    val title: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val fullWidth: Boolean = false,
)

private fun actionRows(actions: List<FileAction>): List<List<FileAction>> = buildList {
    val pending = mutableListOf<FileAction>()
    actions.forEach { action ->
        if (action.fullWidth) {
            if (pending.isNotEmpty()) add(pending.toList().also { pending.clear() })
            add(listOf(action))
        } else {
            pending += action
            if (pending.size == 2) add(pending.toList().also { pending.clear() })
        }
    }
    if (pending.isNotEmpty()) add(pending.toList())
}

@Composable
private fun FileActionButton(
    action: FileAction,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = action.title,
            color = when {
                !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                action.destructive -> MaterialTheme.colorScheme.error
                else -> ISaverPrimaryText
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
private fun CreateFileDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember {
        mutableStateOf(TextFieldValue("未命名.txt", selection = TextRange(0, "未命名".length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件") },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.semantics { contentDescription = "文件名称" },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.text) }, enabled = name.text.isNotBlank()) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember {
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(0, initialName.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.semantics { contentDescription = "新文件名" },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.text) }, enabled = name.text.isNotBlank()) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BatchRenameDialog(
    plan: com.iamxpp.isaver.fileops.BatchRenamePlan?,
    error: String?,
    running: Boolean,
    onPreview: (BatchRenameRule) -> Unit,
    onExecute: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(BatchRenameMode.FIND_REPLACE) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("1") }
    var width by remember { mutableStateOf("1") }
    var renameCase by remember { mutableStateOf(BatchRenameCase.LOWERCASE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量重命名") },
        text = {
            Column {
                listOf(BatchRenameMode.entries.take(3), BatchRenameMode.entries.drop(3)).forEach { options ->
                    Row(Modifier.fillMaxWidth()) {
                        options.forEach { option ->
                        TextButton(
                            onClick = { mode = option },
                            enabled = !running,
                            modifier = Modifier.weight(1f),
                        ) { Text(option.label()) }
                        }
                    }
                }
                when (mode) {
                    BatchRenameMode.FIND_REPLACE, BatchRenameMode.REGEX -> {
                        BatchRenameField(first, { first = it }, if (mode == BatchRenameMode.REGEX) "正则表达式" else "查找")
                        BatchRenameField(second, { second = it }, "替换为")
                    }
                    BatchRenameMode.PREFIX_SUFFIX -> {
                        BatchRenameField(first, { first = it }, "前缀")
                        BatchRenameField(second, { second = it }, "后缀")
                    }
                    BatchRenameMode.NUMBERING -> {
                        BatchRenameField(first, { first = it }, "前缀")
                        BatchRenameField(second, { second = it }, "后缀")
                        Row {
                            BatchRenameField(start, { start = it.filter(Char::isDigit) }, "起始序号", Modifier.weight(1f))
                            BatchRenameField(width, { width = it.filter(Char::isDigit) }, "序号位数", Modifier.weight(1f))
                        }
                    }
                    BatchRenameMode.CASE -> Row {
                        TextButton(onClick = { renameCase = BatchRenameCase.LOWERCASE }) { Text("小写") }
                        TextButton(onClick = { renameCase = BatchRenameCase.UPPERCASE }) { Text("大写") }
                    }
                }
                TextButton(
                    enabled = !running,
                    onClick = {
                        onPreview(
                            BatchRenameRule(
                                mode = mode,
                                find = first,
                                replacement = second,
                                prefix = first,
                                suffix = second,
                                startNumber = start.toIntOrNull() ?: 1,
                                numberWidth = width.toIntOrNull() ?: 1,
                                renameCase = renameCase,
                            ),
                        )
                    },
                ) { Text("生成预览") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                plan?.let { preview ->
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        items(preview.items, key = { it.source.path.value }) { item ->
                            Text(
                                "${item.source.name}  →  ${item.targetName.value}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onExecute, enabled = plan != null && !running) {
                Text(if (running) "正在重命名" else "执行")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("取消") } },
    )
}

@Composable
private fun OperationTaskDialog(
    tasks: List<OperationTask>,
    controllableTaskId: String?,
    controllableTaskPaused: Boolean,
    onClearFinished: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("任务中心") },
        text = {
            if (tasks.isEmpty()) {
                Text("暂无任务", color = ISaverSecondaryText)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(tasks, key = { it.id }) { task ->
                        ListItem(
                            headlineContent = { Text(task.type.taskLabel()) },
                            supportingContent = {
                                val bytes = task.totalBytes?.let {
                                    " · ${formatTaskBytes(task.completedBytes)}/${formatTaskBytes(it)}"
                                } ?: ""
                                Text("${task.state.stateLabel()} · ${task.completedItems}/${task.totalItems}$bytes" +
                                    (task.message?.let { " · $it" } ?: ""))
                            },
                            trailingContent = if (task.id == controllableTaskId) {
                                {
                                    Row {
                                        TextButton(
                                            onClick = {
                                                if (controllableTaskPaused) onResume(task.id) else onPause(task.id)
                                            },
                                        ) { Text(if (controllableTaskPaused) "继续" else "暂停") }
                                        TextButton(onClick = { onCancel(task.id) }) { Text("取消") }
                                    }
                                }
                            } else null,
                            colors = ListItemDefaults.colors(containerColor = ISaverCard),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onClearFinished) { Text("清理已完成") } },
    )
}

private fun bookmarkColor(key: String?): Color = when (key) {
    "GREEN" -> Color(0xFF2E8B57)
    "RED" -> Color(0xFFD24A4A)
    "YELLOW" -> Color(0xFFD39A20)
    else -> ISaverBlue
}

private fun formatTaskBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun DeleteConfirmationDialog(
    entry: DirectoryEntry,
    sharedStorage: Boolean,
    onRecycle: () -> Unit,
    onPermanent: () -> Unit,
    onDismiss: () -> Unit,
) {
    var permanentConfirmation by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (permanentConfirmation) "永久删除" else "删除 ${entry.name}") },
        text = {
            Text(
                if (permanentConfirmation) "此操作不可恢复。确认永久删除此项目？"
                else if (sharedStorage) "项目将移入 iSaver 回收站，可稍后恢复。"
                else "此位置不支持回收站，只能永久删除。",
            )
        },
        confirmButton = {
            if (permanentConfirmation || !sharedStorage) {
                TextButton(onClick = onPermanent) { Text("确认永久删除", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = onRecycle) { Text("移入回收站") }
            }
        },
        dismissButton = {
            if (sharedStorage && !permanentConfirmation) {
                TextButton(onClick = { permanentConfirmation = true }) {
                    Text("永久删除", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
internal fun TrashDialog(
    items: List<TrashItem>,
    busy: Boolean,
    onRestore: (TrashItem) -> Unit,
    onDelete: (TrashItem) -> Unit,
    onRestoreAll: (List<TrashItem>) -> Unit,
    onClear: (List<TrashItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var clearConfirmation by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("回收站") },
        text = {
            if (items.isEmpty()) {
                Text("回收站为空", color = ISaverSecondaryText)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(items, key = { it.id }) { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(item.originalName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (item.state == TrashItemState.ACTIVE) item.originalParent.value else "需要核对回收结果",
                                color = ISaverSecondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.state == TrashItemState.ACTIVE) {
                                Row {
                                    TextButton(enabled = !busy, onClick = { onRestore(item) }) { Text("恢复") }
                                    TextButton(enabled = !busy, onClick = { onDelete(item) }) {
                                        Text("永久删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = ISaverDivider)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = !busy && items.any { it.state == TrashItemState.ACTIVE },
                    onClick = { onRestoreAll(items.filter { it.state == TrashItemState.ACTIVE }) },
                ) { Text("恢复全部") }
                TextButton(
                    enabled = !busy && items.any { it.state == TrashItemState.ACTIVE },
                    onClick = { clearConfirmation = true },
                ) { Text("清空回收站", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
            }
        },
    )
    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            title = { Text("清空回收站") },
            text = { Text("将永久删除所有可核对的回收项目，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirmation = false
                    onClear(items.filter { it.state == TrashItemState.ACTIVE })
                }) { Text("确认清空回收站", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun BatchDeleteConfirmationDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 $count 个项目") },
        text = { Text("共享存储项目将移入 iSaver 回收站；其他位置需要逐项确认永久删除。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("继续") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BookmarkDialog(
    bookmarks: List<com.iamxpp.isaver.bookmarks.Bookmark>,
    onOpen: (com.iamxpp.isaver.bookmarks.Bookmark) -> Unit,
    onUpdate: (com.iamxpp.isaver.bookmarks.Bookmark, String, String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<com.iamxpp.isaver.bookmarks.Bookmark?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书签") },
        text = {
            if (bookmarks.isEmpty()) {
                Text("暂无书签")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(bookmarks.sortedWith(compareBy({ it.groupName.orEmpty() }, { it.displayName })), key = { it.path.value }) { bookmark ->
                        ListItem(
                            leadingContent = {
                                Box(
                                    Modifier.size(14.dp).background(bookmarkColor(bookmark.colorKey), CircleShape),
                                )
                            },
                            headlineContent = {
                                Text(bookmark.displayName, color = if (bookmark.available) ISaverPrimaryText else ISaverSecondaryText)
                            },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        bookmark.groupName,
                                        if (bookmark.type == EntryType.FILE) "文件" else "文件夹",
                                        if (!bookmark.available) "不可用" else null,
                                        bookmark.path.value,
                                    ).joinToString(" · "),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = { TextButton(onClick = { editing = bookmark }) { Text("编辑") } },
                            modifier = Modifier.clickable { onOpen(bookmark) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
    editing?.let { bookmark ->
        BookmarkEditDialog(
            bookmark = bookmark,
            onConfirm = { name, color, group ->
                editing = null
                onUpdate(bookmark, name, color, group)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun BookmarkEditDialog(
    bookmark: com.iamxpp.isaver.bookmarks.Bookmark,
    onConfirm: (String, String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(bookmark.path) { mutableStateOf(bookmark.displayName) }
    var group by remember(bookmark.path) { mutableStateOf(bookmark.groupName.orEmpty()) }
    var color by remember(bookmark.path) { mutableStateOf(bookmark.colorKey) }
    val colors = listOf<String?>(null, "BLUE", "GREEN", "RED", "YELLOW")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑书签") },
        text = {
            Column {
                TextField(value = name, onValueChange = { name = it }, label = { Text("名称") })
                TextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("分组") },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    colors.forEach { option ->
                        TextButton(onClick = { color = option }) {
                            val label = when (option) {
                                "BLUE" -> "蓝"
                                "GREEN" -> "绿"
                                "RED" -> "红"
                                "YELLOW" -> "黄"
                                else -> "默认"
                            }
                            Text(if (color == option) "$label ✓" else label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, color, group) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeepSearchDialog(
    state: BrowserUiState,
    onStart: (LocalSearchCriteria) -> Unit,
    onCancel: () -> Unit,
    onOpenLocation: (DirectoryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var regularExpression by remember { mutableStateOf(false) }
    var extension by remember { mutableStateOf("") }
    var entryType by remember { mutableStateOf(SearchEntryType.ALL) }
    var minimumMegabytes by remember { mutableStateOf("") }
    var maximumMegabytes by remember { mutableStateOf("") }
    var recentDays by remember { mutableStateOf("") }
    val hasSearch = state.deepSearchCriteria != null
    AlertDialog(
        onDismissRequest = { if (!state.deepSearchRunning) onDismiss() },
        title = { Text("深度搜索") },
        text = {
            Column {
                Text(
                    "范围：${state.currentPath.value}",
                    color = ISaverSecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!hasSearch) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            regularExpression = !regularExpression
                        },
                    ) {
                        Checkbox(regularExpression, null)
                        Text("正则表达式")
                    }
                    TextField(
                        value = extension,
                        onValueChange = { extension = it },
                        label = { Text("扩展名，例如 txt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        SearchEntryType.entries.forEach { type ->
                            TextButton(
                                onClick = { entryType = type },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    when (type) {
                                        SearchEntryType.ALL -> "全部"
                                        SearchEntryType.FILE -> "文件"
                                        SearchEntryType.DIRECTORY -> "文件夹"
                                    },
                                    color = if (entryType == type) ISaverBlue else ISaverSecondaryText,
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextField(
                            value = minimumMegabytes,
                            onValueChange = { minimumMegabytes = it.filter(Char::isDigit) },
                            label = { Text("最小 MB") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextField(
                            value = maximumMegabytes,
                            onValueChange = { maximumMegabytes = it.filter(Char::isDigit) },
                            label = { Text("最大 MB") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextField(
                        value = recentDays,
                        onValueChange = { recentDays = it.filter(Char::isDigit) },
                        label = { Text("最近 N 天修改") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    Text(
                        "已扫描 ${state.deepSearchScannedDirectories} 个目录、${state.deepSearchScannedEntries} 个项目",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (state.deepSearchRunning) {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
                    }
                    state.deepSearchError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (!state.deepSearchRunning && state.deepSearchError == null) {
                        val summary = buildString {
                            append("找到 ${state.deepSearchResults.size} 项")
                            if (state.deepSearchSkippedDirectories > 0) {
                                append("，跳过 ${state.deepSearchSkippedDirectories} 个不可读目录")
                            }
                            if (state.deepSearchTruncated) append("，已达到扫描上限")
                        }
                        Text(summary, color = ISaverSecondaryText, modifier = Modifier.padding(top = 8.dp))
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(state.deepSearchResults, key = { it.path.value }) { entry ->
                                ListItem(
                                    headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Text(
                                            entry.path.value.substringBeforeLast('/', "/"),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.clickable { onOpenLocation(entry) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.deepSearchRunning -> TextButton(onClick = onCancel) { Text("取消搜索") }
                hasSearch -> TextButton(onClick = onDismiss) { Text("关闭") }
                else -> TextButton(
                    onClick = {
                        val bytesPerMegabyte = 1024L * 1024L
                        val nowSeconds = System.currentTimeMillis() / 1_000L
                        onStart(
                            LocalSearchCriteria(
                                query = query,
                                regularExpression = regularExpression,
                                extension = extension,
                                entryType = entryType,
                                minimumSizeBytes = minimumMegabytes.toLongOrNull()
                                    ?.coerceAtMost(Long.MAX_VALUE / bytesPerMegabyte)?.times(bytesPerMegabyte),
                                maximumSizeBytes = maximumMegabytes.toLongOrNull()
                                    ?.coerceAtMost(Long.MAX_VALUE / bytesPerMegabyte)?.times(bytesPerMegabyte),
                                modifiedAfterEpochSeconds = recentDays.toLongOrNull()
                                    ?.let { nowSeconds - it.coerceAtMost(365_000L) * 86_400L },
                            ),
                        )
                    },
                ) { Text("开始") }
            }
        },
        dismissButton = if (!state.deepSearchRunning && !hasSearch) {
            { TextButton(onClick = onDismiss) { Text("取消") } }
        } else null,
    )
}

private fun com.iamxpp.isaver.tasks.OperationTaskType.taskLabel(): String = when (this) {
    com.iamxpp.isaver.tasks.OperationTaskType.COPY -> "复制"
    com.iamxpp.isaver.tasks.OperationTaskType.MOVE -> "移动"
    com.iamxpp.isaver.tasks.OperationTaskType.DELETE -> "删除"
    com.iamxpp.isaver.tasks.OperationTaskType.RESTORE -> "恢复"
    com.iamxpp.isaver.tasks.OperationTaskType.ARCHIVE -> "压缩"
    com.iamxpp.isaver.tasks.OperationTaskType.EXTRACT -> "解压"
    com.iamxpp.isaver.tasks.OperationTaskType.CHECKSUM -> "校验和"
    com.iamxpp.isaver.tasks.OperationTaskType.SEARCH -> "深度搜索"
}

private fun OperationTaskState.stateLabel(): String = when (this) {
    OperationTaskState.QUEUED -> "等待中"
    OperationTaskState.RUNNING -> "运行中"
    OperationTaskState.PAUSED -> "已暂停"
    OperationTaskState.CANCELLING -> "取消中"
    OperationTaskState.NEEDS_ACTION -> "需处理"
    OperationTaskState.SUCCESS -> "成功"
    OperationTaskState.PARTIAL_SUCCESS -> "部分成功"
    OperationTaskState.FAILED -> "失败"
    OperationTaskState.CANCELLED -> "已取消"
    OperationTaskState.OUTCOME_UNCERTAIN -> "结果不确定"
    OperationTaskState.NEEDS_REVIEW -> "需核对"
}

@Composable
private fun BatchRenameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.semantics { contentDescription = label },
    )
}

private fun BatchRenameMode.label(): String = when (this) {
    BatchRenameMode.FIND_REPLACE -> "替换"
    BatchRenameMode.PREFIX_SUFFIX -> "前后缀"
    BatchRenameMode.NUMBERING -> "序号"
    BatchRenameMode.CASE -> "大小写"
    BatchRenameMode.REGEX -> "正则"
}

@Composable
private fun MessageDialog(message: String, button: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(button) } },
    )
}

@Composable
private fun ConflictDialog(
    prompt: BrowserConflictPrompt,
    onResolve: (ConflictAction, Boolean) -> Unit,
) {
    val operation = if (prompt.operation == BrowserConflictOperation.MOVE) "移动" else "复制"
    AlertDialog(
        onDismissRequest = { onResolve(ConflictAction.CANCEL, false) },
        title = { Text("目标位置存在同名项目") },
        text = {
            Column {
                Text(prompt.entryName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "已$operation ${prompt.completedCount}/${prompt.totalCount} 项",
                    color = ISaverSecondaryText,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = { onResolve(ConflictAction.KEEP_BOTH, false) }) { Text("保留两者") }
                TextButton(onClick = { onResolve(ConflictAction.SKIP, false) }) { Text("跳过") }
                TextButton(onClick = { onResolve(ConflictAction.REPLACE, false) }) {
                    Text("替换", color = MaterialTheme.colorScheme.error)
                }
                if (prompt.entryType == EntryType.DIRECTORY) {
                    TextButton(onClick = { onResolve(ConflictAction.MERGE, false) }) { Text("合并目录") }
                }
                if (prompt.totalCount > 1) {
                    TextButton(onClick = { onResolve(ConflictAction.KEEP_BOTH, true) }) { Text("全部保留两者") }
                    TextButton(onClick = { onResolve(ConflictAction.SKIP, true) }) { Text("全部跳过") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onResolve(ConflictAction.CANCEL, false) }) { Text("取消") } },
    )
}

@Composable
private fun CompressDialog(onDismiss: () -> Unit, onConfirm: (String, ArchiveFormat) -> Unit) {
    var name by remember { mutableStateOf("archive") }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩文件") },
        text = {
            Column {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("压缩文件名称") },
                    suffix = { Text(".${format.defaultExtension}") },
                )
                Row(Modifier.fillMaxWidth()) {
                    listOf(
                        ArchiveFormat.ZIP,
                        ArchiveFormat.TAR,
                        ArchiveFormat.TAR_GZ,
                        ArchiveFormat.SEVEN_Z,
                    ).forEach { candidate ->
                        TextButton(
                            onClick = { format = candidate },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (format == candidate) "[${candidate.creationLabel}]" else candidate.creationLabel)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val suffix = ".${format.defaultExtension}"
                    val stem = if (name.endsWith(suffix, ignoreCase = true)) name.dropLast(suffix.length) else name
                    onConfirm("$stem$suffix", format)
                },
                enabled = name.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RemoteBrowserDialog(
    state: RemoteConnectionUiState.Connected,
    onRefresh: () -> Unit,
    onCreateDirectory: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("远程：${state.host}") },
        text = {
            Column {
                Text("路径：${state.path.value}", color = ISaverSecondaryText)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(state.entries, key = { it.path.value }) { entry ->
                        Text(
                            text = if (entry.directory) "📁 ${entry.name}" else entry.name,
                            color = com.iamxpp.isaver.ui.theme.ISaverPrimaryText,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
                TextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("新建远程文件夹") },
                    singleLine = true,
                )
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = folderName.isNotBlank(),
                onClick = {
                    onCreateDirectory(folderName)
                    folderName = ""
                },
            ) { Text("新建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ServerConnectionDialog(
    onDismiss: () -> Unit,
    onConfirm: (RemoteConnectionDraft) -> Unit,
) {
    var protocol by remember { mutableStateOf(RemoteProtocol.SFTP) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(protocol.defaultPort.toString()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var allowPlaintext by remember { mutableStateOf(false) }
    val secure = protocol != RemoteProtocol.FTP
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接服务器") },
        text = {
            Column {
                Row {
                    RemoteProtocol.entries.forEach { option ->
                        TextButton(onClick = {
                            protocol = option
                            port = option.defaultPort.toString()
                        }) { Text(option.name) }
                    }
                }
                TextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    modifier = Modifier.semantics { contentDescription = "服务器地址" },
                )
                TextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("端口") },
                    singleLine = true,
                )
                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.semantics { contentDescription = "用户名" },
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.semantics { contentDescription = "密码" },
                )
                if (secure) {
                    TextField(
                        value = fingerprint,
                        onValueChange = { fingerprint = it },
                        label = { Text(if (protocol == RemoteProtocol.SFTP) "主机密钥指纹" else "证书指纹") },
                        singleLine = true,
                        modifier = Modifier.semantics { contentDescription = "安全指纹" },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowPlaintext, onCheckedChange = { allowPlaintext = it })
                        Text("我了解普通 FTP 可能明文传输")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && username.isNotBlank() && password.isNotEmpty() &&
                    (port.toIntOrNull() ?: 0) in 1..65_535 &&
                    (secure && fingerprint.isNotBlank() || !secure && allowPlaintext),
                onClick = {
                    onConfirm(
                        RemoteConnectionDraft(
                            protocol = protocol,
                            host = host,
                            port = port.toIntOrNull() ?: protocol.defaultPort,
                            username = username,
                            password = password,
                            fingerprint = fingerprint,
                            allowPlaintext = allowPlaintext,
                        ),
                    )
                },
            ) { Text("连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
