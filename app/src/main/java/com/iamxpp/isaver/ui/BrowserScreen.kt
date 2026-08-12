package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPathRiskPolicy
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
import java.text.DateFormat
import java.util.Date

@Composable
@OptIn(ExperimentalMaterial3Api::class)
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
    onToggleSelection: (DirectoryEntry) -> Unit = {},
    onOpenEntry: (DirectoryEntry) -> Unit = onToggleSelection,
    onSelectEntry: (DirectoryEntry) -> Unit = onToggleSelection,
    onClearSelection: () -> Unit = {},
    onDismissFileInfo: () -> Unit = {},
    onDismissFileOpenError: () -> Unit = {},
    onShareEntry: ((DirectoryEntry) -> Unit)? = null,
    onDismissFileShareError: () -> Unit = {},
    onMoveEntry: ((DirectoryEntry) -> Unit)? = null,
    onDismissFileMoveError: () -> Unit = {},
    onCopyEntry: ((DirectoryEntry) -> Unit)? = null,
    onDismissFileCopyError: () -> Unit = {},
    onRenameEntry: ((DirectoryEntry, String) -> Unit)? = null,
    onDismissFileRenameError: () -> Unit = {},
    onDismissCompressionMessage: () -> Unit = {},
    onDismissPresentationError: () -> Unit = {},
    onDismissCreateError: () -> Unit = {},
    onCompress: ((String) -> Unit)? = null,
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
    var compressDialogVisible by remember { mutableStateOf(false) }
    var serverDialogVisible by remember { mutableStateOf(false) }
    var actionEntry by remember { mutableStateOf<DirectoryEntry?>(null) }
    var renameDialogEntry by remember { mutableStateOf<DirectoryEntry?>(null) }

    LaunchedEffect(state.currentPath) {
        actionEntry = null
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
                    onCompress = {
                        menuExpanded = false
                        if (onCompress != null) compressDialogVisible = true
                    },
                    onConnectServer = {
                        menuExpanded = false
                        if (onConnectServer != null) serverDialogVisible = true
                    },
                    canCreateFolder = state.canCreateDirectory && !state.creatingDirectory,
                    canCompress = onCompress != null &&
                        state.selectedEntries.isNotEmpty() &&
                        !state.compressing,
                    canConnectServer = onConnectServer != null,
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选择 ${state.selectedEntries.size} 项", modifier = Modifier.weight(1f))
                TextButton(onClick = onClearSelection) { Text("清除") }
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
                    if (entry !in state.selectedEntries) onSelectEntry(entry)
                    actionEntry = entry
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
            onConfirm = { name ->
                compressDialogVisible = false
                onCompress?.invoke(name)
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
    actionEntry?.let { entry ->
        FileActionsSheet(
            entry = entry,
            shareVisible = onShareEntry != null && entry.type == EntryType.FILE,
            shareEnabled = !state.sharingFile,
            moveVisible = onMoveEntry != null && entry.type == EntryType.FILE,
            moveEnabled = !state.movingFile,
            copyVisible = onCopyEntry != null && entry.type == EntryType.FILE,
             copyEnabled = !state.copyingFile,
             renameVisible = onRenameEntry != null && entry.type == EntryType.FILE,
             renameEnabled = !state.renamingFile,
            compressVisible = onCompress != null,
            onShare = {
                actionEntry = null
                onShareEntry?.invoke(entry)
            },
            onCompress = {
                actionEntry = null
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
            onClearSelection = {
                actionEntry = null
                onClearSelection()
            },
            onDismiss = { actionEntry = null },
        )
    }
    state.createDirectoryError?.let {
        MessageDialog(it.userMessage, "关闭", onDismissCreateError)
    }
    state.presentationError?.let {
        MessageDialog(it, "关闭", onDismissPresentationError)
    }
    state.compressionMessage?.let {
        MessageDialog(it, "关闭", onDismissCompressionMessage)
    }
    state.fileInfo?.let { FileInfoDialog(it, onDismissFileInfo) }
    state.fileOpenError?.let { MessageDialog(it.userMessage, "关闭", onDismissFileOpenError) }
    state.fileShareError?.let { MessageDialog(it.userMessage, "关闭", onDismissFileShareError) }
    state.fileMoveError?.let { MessageDialog(it.userMessage, "关闭", onDismissFileMoveError) }
    state.fileCopyError?.let { MessageDialog(it.userMessage, "关闭", onDismissFileCopyError) }
    state.fileRenameError?.let { MessageDialog(it.userMessage, "关闭", onDismissFileRenameError) }
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
                "正在安全移动文件",
                color = ISaverSecondaryText,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FileActionsSheet(
    entry: DirectoryEntry,
    shareVisible: Boolean,
    shareEnabled: Boolean,
    moveVisible: Boolean,
    moveEnabled: Boolean,
    copyVisible: Boolean,
    copyEnabled: Boolean,
    renameVisible: Boolean,
    renameEnabled: Boolean,
    compressVisible: Boolean,
    onShare: () -> Unit,
    onCompress: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onClearSelection: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ISaverCard,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = "文件操作",
                color = ISaverPrimaryText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                text = entry.name,
                color = ISaverSecondaryText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            HorizontalDivider(color = ISaverDivider)
            if (shareVisible) {
                FileActionRow(
                    title = "分享",
                    description = "分享到其他应用",
                    enabled = shareEnabled,
                    onClick = onShare,
                )
            }
            if (moveVisible) {
                FileActionRow(
                    title = "移动到",
                    description = "选择新的文件夹",
                    enabled = moveEnabled,
                    onClick = onMove,
                )
            }
            if (copyVisible) {
                FileActionRow(
                    title = "复制到",
                    description = "复制到其他文件夹",
                    enabled = copyEnabled,
                    onClick = onCopy,
                )
            }
            if (renameVisible) {
                FileActionRow(
                    title = "重命名",
                    description = "修改文件名称",
                    enabled = renameEnabled,
                    onClick = onRename,
                )
            }
            if (compressVisible) {
                FileActionRow(
                    title = "压缩",
                    description = "在当前目录创建 ZIP",
                    onClick = onCompress,
                )
            }
            HorizontalDivider(color = ISaverDivider)
            TextButton(
                onClick = onClearSelection,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("取消选择")
            }
        }
    }
}

@Composable
private fun FileActionRow(
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        colors = ListItemDefaults.colors(containerColor = ISaverCard),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    )
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
private fun MessageDialog(message: String, button: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(button) } },
    )
}

@Composable
private fun CompressDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("archive.zip") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩文件") },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("压缩文件名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("确定") }
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
