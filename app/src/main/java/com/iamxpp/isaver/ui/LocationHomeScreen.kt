package com.iamxpp.isaver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootPathRiskPolicy
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.locations.ResolvedAppLocation
import com.iamxpp.isaver.locations.StorageLocation
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FileGridCell
import com.iamxpp.isaver.ui.files.FileListRow
import com.iamxpp.isaver.ui.files.FilesOverflowMenu
import com.iamxpp.isaver.ui.files.FilesPageHeader
import com.iamxpp.isaver.ui.files.FilesSaveAction
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.files.SortSpec
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText
import com.iamxpp.isaver.ui.virtualviews.VirtualViewGridCell
import com.iamxpp.isaver.ui.virtualviews.VirtualViewListRow
import com.iamxpp.isaver.ui.virtualviews.VirtualViewUiState
import com.iamxpp.isaver.virtualviews.VirtualViewNode

@Composable
fun LocationHomeScreen(
    state: LocationHomeUiState,
    displayMode: DisplayMode,
    onOpenLocation: (RootPath, String) -> Unit,
    onAdd: (String, String) -> Unit,
    onEdit: (LocationId, String, String) -> Unit,
    onRemove: (LocationId) -> Unit,
    onRetry: () -> Unit,
    onRevalidate: (LocationId) -> Unit = {},
    onClearAddError: () -> Unit = {},
    sortSpec: SortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
    onDisplayModeChange: (DisplayMode) -> Unit = {},
    onSortChange: (SortSpec) -> Unit = {},
    saveAction: FilesSaveAction? = null,
    virtualViewState: VirtualViewUiState? = null,
    onOpenVirtualFolder: (VirtualViewNode.VirtualFolder) -> Unit = {},
    onOpenVirtualReference: (VirtualViewNode.RealReference) -> Unit = { reference ->
        if (reference.entryType == EntryType.DIRECTORY) onOpenLocation(reference.targetPath, reference.displayName)
    },
    onRetryVirtualReference: (VirtualViewNode.RealReference) -> Unit = {},
    onAddVirtualReferenceAgain: (VirtualViewNode.RealReference) -> Unit = {},
    onRebindVirtualReference: (VirtualViewNode.RealReference) -> Unit = {},
    onNavigateVirtual: (String?) -> Unit = {},
    onCreateVirtualFolder: ((String) -> Unit)? = null,
    onRenameVirtualNode: (String, String) -> Unit = { _, _ -> },
    onMoveVirtualNode: (String, String?) -> Unit = { _, _ -> },
    onDeleteVirtualFolder: (String, Boolean) -> Unit = { _, _ -> },
    onDismissVirtualDelete: () -> Unit = {},
    onRemoveVirtualReference: (String) -> Unit = {},
    onOpenTrash: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<StorageLocation.Direct?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<StorageLocation.Direct?>(null) }
    var managedVirtualNode by remember { mutableStateOf<VirtualViewNode?>(null) }
    var virtualTargetError by remember { mutableStateOf<String?>(null) }
    val openedVirtualFolder = virtualViewState?.takeIf { it.currentFolderId != null }
    val currentVirtualFolder = openedVirtualFolder?.let { opened ->
        opened.breadcrumbs.lastOrNull()
            ?: opened.allFolders.firstOrNull { it.id == opened.currentFolderId }
    }
    val virtualParentId = currentVirtualFolder?.parentId

    val openVirtualFolder: (VirtualViewNode.VirtualFolder) -> Unit = { folder ->
        virtualTargetError = null
        onOpenVirtualFolder(folder)
    }
    val openVirtualReference: (VirtualViewNode.RealReference) -> Unit = { reference ->
        if (saveAction != null && reference.entryType != EntryType.DIRECTORY) {
            virtualTargetError = "文件不能作为保存位置。"
        } else {
            virtualTargetError = null
            onOpenVirtualReference(reference)
        }
    }
    val navigateVirtual: (String?) -> Unit = { folderId ->
        virtualTargetError = null
        onNavigateVirtual(folderId)
    }

    LaunchedEffect(state.saveSuccessVersion) {
        adding = false
        editor = null
    }
    LaunchedEffect(saveAction != null) {
        if (saveAction == null) virtualTargetError = null
    }
    LaunchedEffect(virtualViewState?.currentFolderId) {
        query = ""
    }

    BackHandler(enabled = openedVirtualFolder != null) {
        navigateVirtual(virtualParentId)
    }

    val content = sortLocationContent(
        apps = state.appGroups.filterForQuery(query),
        common = state.commonLocations.filter { it.displayName.contains(query, ignoreCase = true) },
        custom = state.customLocations.filter { it.location.displayName.contains(query, ignoreCase = true) },
        sortSpec = sortSpec,
        virtualMode = virtualViewState != null,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ISaverBackground),
    ) {
        LocationHomeHeader(
            title = currentVirtualFolder?.displayName ?: if (openedVirtualFolder == null) "视图" else "虚拟视图位置",
            query = query,
            onQueryChange = { query = it },
            error = state.error,
            displayMode = displayMode,
            sortSpec = sortSpec,
            onAdd = { onClearAddError(); adding = true },
            onRetry = onRetry,
            onDisplayModeChange = onDisplayModeChange,
            onSortChange = onSortChange,
            saveAction = saveAction,
            virtualMode = virtualViewState != null,
            onCreateVirtualFolder = onCreateVirtualFolder,
            onBack = openedVirtualFolder?.let { { navigateVirtual(virtualParentId) } },
        )
        val targetHint = virtualTargetError ?: saveAction?.disabledReason?.takeIf {
            !saveAction.enabled && virtualViewState != null
        }
        if (targetHint != null) {
            Text(
                text = targetHint,
                color = ISaverSecondaryText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = "目标不可用：$targetHint" },
            )
        }
        if (openedVirtualFolder != null) {
            VirtualFolderContent(
                state = openedVirtualFolder,
                query = query,
                displayMode = displayMode,
                onOpenVirtualFolder = openVirtualFolder,
                onOpenVirtualReference = openVirtualReference,
                onManageVirtualNode = { managedVirtualNode = it },
                modifier = Modifier.weight(1f),
            )
        } else if (displayMode == DisplayMode.LIST) {
            LocationList(
                state = state,
                content = content,
                onOpenLocation = onOpenLocation,
                onEdit = { onClearAddError(); editor = it },
                onRemove = { removal = it },
                onRevalidate = onRevalidate,
                virtualViewState = virtualViewState,
                onOpenVirtualFolder = openVirtualFolder,
                onOpenVirtualReference = openVirtualReference,
                onManageVirtualNode = { managedVirtualNode = it },
                onOpenTrash = onOpenTrash,
                modifier = Modifier.weight(1f),
            )
        } else {
            LocationHomeGrid(
                state = state,
                content = content,
                onOpenLocation = onOpenLocation,
                onEdit = { onClearAddError(); editor = it },
                onRemove = { removal = it },
                onRevalidate = onRevalidate,
                virtualViewState = virtualViewState,
                onOpenVirtualFolder = openVirtualFolder,
                onOpenVirtualReference = openVirtualReference,
                onManageVirtualNode = { managedVirtualNode = it },
                onOpenTrash = onOpenTrash,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (adding || editor != null) {
        val current = editor
        CustomLocationDialog(
            initialName = current?.displayName.orEmpty(),
            initialPath = current?.path?.value.orEmpty(),
            error = state.addError,
            operationInProgress = state.operationInProgress,
            onConfirm = { name, rawPath ->
                if (current == null) onAdd(name, rawPath) else onEdit(current.id, name, rawPath)
            },
            onDismiss = { onClearAddError(); adding = false; editor = null },
        )
    }

    removal?.let { location ->
        AlertDialog(
            onDismissRequest = { if (!state.operationInProgress) removal = null },
            title = { Text("移除视图") },
            text = { Text("只会移除 iSaver 中的视图，不会改动磁盘内容。") },
            confirmButton = {
                Button(
                    enabled = !state.operationInProgress,
                    onClick = { onRemove(location.id); removal = null },
                ) { Text("确认移除") }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.operationInProgress,
                    onClick = { removal = null },
                ) { Text("取消") }
            },
        )
    }

    managedVirtualNode?.let { node ->
        VirtualNodeManagementDialog(
            node = node,
            folders = virtualViewState?.allFolders.orEmpty(),
            operationInProgress = virtualViewState?.operationInProgress == true,
            onRename = { name -> onRenameVirtualNode(node.id, name); managedVirtualNode = null },
            onMove = { folderId -> onMoveVirtualNode(node.id, folderId); managedVirtualNode = null },
            onOpen = {
                if (node is VirtualViewNode.RealReference) openVirtualReference(node)
                managedVirtualNode = null
            },
            onRetry = {
                if (node is VirtualViewNode.RealReference) onRetryVirtualReference(node)
                managedVirtualNode = null
            },
            onAddAgain = {
                if (node is VirtualViewNode.RealReference) onAddVirtualReferenceAgain(node)
                managedVirtualNode = null
            },
            onRebind = {
                if (node is VirtualViewNode.RealReference) onRebindVirtualReference(node)
                managedVirtualNode = null
            },
            onRemove = {
                when (node) {
                    is VirtualViewNode.VirtualFolder -> onDeleteVirtualFolder(node.id, false)
                    is VirtualViewNode.RealReference -> onRemoveVirtualReference(node.id)
                }
                managedVirtualNode = null
            },
            onDismiss = { managedVirtualNode = null },
        )
    }

    virtualViewState?.confirmDeleteFolderId?.let { folderId ->
        AlertDialog(
            onDismissRequest = onDismissVirtualDelete,
            title = { Text("移除虚拟文件夹") },
            text = { Text("只会移除这个虚拟分组及其中的引用，不会删除设备上的任何文件或文件夹。") },
            confirmButton = {
                TextButton(onClick = { onDeleteVirtualFolder(folderId, true) }) {
                    Text("确认移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismissVirtualDelete) { Text("取消") } },
        )
    }
}

@Composable
private fun LocationHomeHeader(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    error: String?,
    displayMode: DisplayMode,
    sortSpec: SortSpec,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    onDisplayModeChange: (DisplayMode) -> Unit,
    onSortChange: (SortSpec) -> Unit,
    saveAction: FilesSaveAction?,
    virtualMode: Boolean,
    onCreateVirtualFolder: ((String) -> Unit)?,
    onBack: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var creatingVirtualFolder by remember { mutableStateOf(false) }

    Column {
        FilesPageHeader(
            title = title,
            query = query,
            onQueryChange = onQueryChange,
            onOverflow = { menuExpanded = true },
            onBack = onBack,
            saveAction = saveAction,
            topBarTestTag = "views-top-bar",
            searchTestTag = "views-search",
            overflowMenuContent = {
                FilesOverflowMenu(
                    expanded = menuExpanded,
                    displayMode = displayMode,
                    sortSpec = sortSpec,
                    onDismissRequest = { menuExpanded = false },
                    onDisplayModeChange = {
                        menuExpanded = false
                        onDisplayModeChange(it)
                    },
                    onSortFieldChange = {
                        menuExpanded = false
                        onSortChange(sortSpec.copy(field = it))
                    },
                    onSortDirectionToggle = {
                        menuExpanded = false
                        onSortChange(
                            sortSpec.copy(
                                direction = if (sortSpec.direction == SortDirection.ASCENDING) {
                                    SortDirection.DESCENDING
                                } else {
                                    SortDirection.ASCENDING
                                },
                            ),
                        )
                    },
                    onCreateFolder = { menuExpanded = false },
                    onCompress = { menuExpanded = false },
                    canCreateFolder = false,
                    canCompress = false,
                    onAddLocation = {
                        menuExpanded = false
                        if (virtualMode) creatingVirtualFolder = true else onAdd()
                    },
                    addLocationLabel = if (virtualMode) "新建虚拟视图文件夹" else "添加位置",
                )
            },
        )
        if (error != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
            ) {
                Text(
                    text = error,
                    color = ISaverPrimaryText,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                TextButton(onClick = onRetry) { Text("重试", color = ISaverBlue) }
            }
        }
    }
    if (creatingVirtualFolder && onCreateVirtualFolder != null) {
        VirtualFolderNameDialog(
            title = "新建虚拟视图文件夹",
            initialName = "",
            onConfirm = { onCreateVirtualFolder(it); creatingVirtualFolder = false },
            onDismiss = { creatingVirtualFolder = false },
        )
    }
}

@Composable
private fun VirtualFolderContent(
    state: VirtualViewUiState,
    query: String,
    displayMode: DisplayMode,
    onOpenVirtualFolder: (VirtualViewNode.VirtualFolder) -> Unit,
    onOpenVirtualReference: (VirtualViewNode.RealReference) -> Unit,
    onManageVirtualNode: (VirtualViewNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val children = state.children.filter { it.displayName.contains(query, ignoreCase = true) }
    when {
        state.loading -> VirtualFolderStatus("正在加载目录", modifier)
        state.error != null -> VirtualFolderStatus(state.error, modifier)
        children.isEmpty() -> VirtualFolderStatus(
            if (query.isEmpty()) "此目录为空" else "没有匹配项目",
            modifier,
        )
        displayMode == DisplayMode.LIST -> LazyColumn(
            modifier = modifier.background(ISaverCard).testTag("virtual-folder-list"),
        ) {
            items(children, key = { it.id }) { node ->
                VirtualViewListRow(node, onOpenVirtualFolder, onOpenVirtualReference, onManageVirtualNode)
            }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = modifier.background(ISaverCard).testTag("virtual-folder-grid"),
        ) {
            gridItems(children, key = { it.id }) { node ->
                VirtualViewGridCell(node, onOpenVirtualFolder, onOpenVirtualReference, onManageVirtualNode)
            }
        }
    }
}

@Composable
private fun VirtualFolderStatus(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(ISaverCard),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = ISaverSecondaryText)
    }
}

@Composable
private fun LocationList(
    state: LocationHomeUiState,
    content: SortedLocationContent,
    onOpenLocation: (RootPath, String) -> Unit,
    onEdit: (StorageLocation.Direct) -> Unit,
    onRemove: (StorageLocation.Direct) -> Unit,
    onRevalidate: (LocationId) -> Unit,
    virtualViewState: VirtualViewUiState?,
    onOpenVirtualFolder: (VirtualViewNode.VirtualFolder) -> Unit,
    onOpenVirtualReference: (VirtualViewNode.RealReference) -> Unit,
    onManageVirtualNode: (VirtualViewNode) -> Unit,
    onOpenTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        content.sectionOrder.forEach { section ->
            when (section) {
                LocationSection.APP -> {
                    item(key = "section-app") { SectionTitle("应用位置") }
                    content.apps.forEach { group ->
                        item(key = group.templateId.value) {
                            Text(
                                text = group.displayName,
                                color = ISaverPrimaryText,
                                modifier = Modifier.padding(16.dp, 8.dp),
                            )
                        }
                        if (group.empty) {
                            item(key = "${group.templateId.value}.empty") {
                                Text(
                                    "未找到可用${group.displayName}目录",
                                    color = ISaverSecondaryText,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        } else {
                            items(group.children, key = { it.id.value }) { location ->
                                LocationRow(location, location.path.value) {
                                    onOpenLocation(location.path, location.displayName)
                                }
                            }
                        }
                    }
                }

                LocationSection.COMMON -> {
                    item(key = "section-common") { SectionTitle("通用位置") }
                    items(content.common, key = { it.id.value }) { location ->
                        LocationRow(location, location.path.value) {
                            onOpenLocation(location.path, location.displayName)
                        }
                    }
                    item(key = "common.trash") { TrashLocationRow(onOpenTrash) }
                }

                LocationSection.CUSTOM -> {
                    if (virtualViewState != null) {
                        item(key = "section-custom") {
                            VirtualSectionHeader(virtualViewState)
                        }
                        if (virtualViewState.children.isEmpty() && !virtualViewState.loading) {
                            item(key = "virtual-empty") {
                                Text("暂无虚拟视图文件夹", color = ISaverSecondaryText, modifier = Modifier.padding(16.dp))
                            }
                        }
                        items(virtualViewState.children, key = { it.id }) { node ->
                            VirtualViewListRow(node, onOpenVirtualFolder, onOpenVirtualReference, onManageVirtualNode)
                        }
                    } else {
                    item(key = "section-custom") { SectionTitle("自定义位置") }
                    items(content.custom, key = { it.location.id.value }) { item ->
                        LocationRow(item.location, item.availability.label(item.location.path), item.availability) {
                            onOpenLocation(item.location.path, item.location.displayName)
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            TextButton(
                                onClick = { onRevalidate(item.location.id) },
                                modifier = Modifier.semantics {
                                    contentDescription = "重新校验视图：${item.location.displayName}"
                                },
                            ) { Text("重新校验") }
                            TextButton(
                                onClick = { onEdit(item.location) },
                                modifier = Modifier.semantics {
                                    contentDescription = "编辑视图：${item.location.displayName}"
                                },
                            ) { Text("编辑") }
                            TextButton(
                                onClick = { onRemove(item.location) },
                                modifier = Modifier.semantics {
                                    contentDescription = "移除视图：${item.location.displayName}"
                                },
                            ) { Text("移除视图") }
                        }
                    }
                    }
                }
            }
        }
        if (state.loading) item {
            Text("正在加载位置…", color = ISaverSecondaryText, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = ISaverSecondaryText,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun VirtualSectionHeader(
    state: VirtualViewUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionTitle("虚拟视图位置")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp, 4.dp)) }
    }
}

@Composable
private fun TrashLocationRow(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ISaverCard)
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "列表项：回收站" }
            .padding(horizontal = 16.dp)
            .heightIn(min = 78.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 58.dp, height = 48.dp)) {
            val color = ISaverSecondaryText
            drawRoundRect(
                color,
                topLeft = Offset(size.width * .25f, size.height * .27f),
                size = Size(size.width * .5f, size.height * .62f),
                style = Stroke(2.dp.toPx()),
            )
            drawLine(color, Offset(size.width * .2f, size.height * .2f), Offset(size.width * .8f, size.height * .2f), 2.dp.toPx())
            drawLine(color, Offset(size.width * .4f, size.height * .1f), Offset(size.width * .6f, size.height * .1f), 2.dp.toPx())
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("回收站", color = ISaverPrimaryText)
            Text("已删除项目", color = ISaverSecondaryText)
        }
        Text("›", color = ISaverSecondaryText)
    }
}

@Composable
private fun VirtualFolderNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(enabled = name.trim().isNotEmpty(), onClick = { onConfirm(name.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VirtualNodeManagementDialog(
    node: VirtualViewNode,
    folders: List<VirtualViewNode.VirtualFolder>,
    operationInProgress: Boolean,
    onRename: (String) -> Unit,
    onMove: (String?) -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onAddAgain: () -> Unit,
    onRebind: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var renameVisible by remember { mutableStateOf(false) }
    var moveVisible by remember { mutableStateOf(false) }
    if (!renameVisible && !moveVisible) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.medium, color = ISaverCard) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(node.displayName, color = ISaverPrimaryText, modifier = Modifier.padding(20.dp, 12.dp))
                    if (node is VirtualViewNode.RealReference) {
                        TextButton(enabled = !operationInProgress, onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                            Text("打开")
                        }
                    }
                    TextButton(enabled = !operationInProgress, onClick = { renameVisible = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("编辑备注")
                    }
                    TextButton(enabled = !operationInProgress, onClick = { moveVisible = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("移动到")
                    }
                    if (node is VirtualViewNode.RealReference) {
                        TextButton(enabled = !operationInProgress, onClick = onAddAgain, modifier = Modifier.fillMaxWidth()) {
                            Text("再添加到其他位置")
                        }
                        TextButton(enabled = !operationInProgress, onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text("重试校验")
                        }
                        TextButton(enabled = !operationInProgress, onClick = onRebind, modifier = Modifier.fillMaxWidth()) {
                            Text("重新定位")
                        }
                        Text(
                            "${if (node.available) "可用" else "不可用"} · ${node.targetPath.value}",
                            color = ISaverSecondaryText,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    TextButton(enabled = !operationInProgress, onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                        Text(if (node is VirtualViewNode.VirtualFolder) "移除虚拟文件夹" else "从虚拟视图移除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (renameVisible) {
        VirtualFolderNameDialog("编辑备注", node.displayName, onRename, onDismiss)
    }
    if (moveVisible) {
        var selected by remember { mutableStateOf<String?>(node.parentId) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("移动到") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (node is VirtualViewNode.VirtualFolder) {
                        item {
                            Row(Modifier.fillMaxWidth().clickable { selected = null }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selected == null, onClick = { selected = null })
                                Text("虚拟视图根目录")
                            }
                        }
                    }
                    items(folders.filter { it.id != node.id }, key = { it.id }) { folder ->
                        Row(Modifier.fillMaxWidth().clickable { selected = folder.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected == folder.id, onClick = { selected = folder.id })
                            Text(folder.displayName)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onMove(selected) }) { Text("移动") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }
}

@Composable
private fun LocationHomeGrid(
    state: LocationHomeUiState,
    content: SortedLocationContent,
    onOpenLocation: (RootPath, String) -> Unit,
    onEdit: (StorageLocation.Direct) -> Unit,
    onRemove: (StorageLocation.Direct) -> Unit,
    onRevalidate: (LocationId) -> Unit,
    virtualViewState: VirtualViewUiState?,
    onOpenVirtualFolder: (VirtualViewNode.VirtualFolder) -> Unit,
    onOpenVirtualReference: (VirtualViewNode.RealReference) -> Unit,
    onManageVirtualNode: (VirtualViewNode) -> Unit,
    onOpenTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.testTag("location-home-grid"),
    ) {
        content.sectionOrder.forEach { section ->
            when (section) {
                LocationSection.APP -> {
                    fullSpanItem("section-app") { SectionTitle("应用位置", Modifier.testTag("section-app")) }
                    content.apps.forEach { group ->
                        fullSpanItem(group.templateId.value) {
                            Text(
                                text = group.displayName,
                                color = ISaverPrimaryText,
                                modifier = Modifier.padding(16.dp, 8.dp),
                            )
                        }
                        if (group.empty) {
                            fullSpanItem("${group.templateId.value}.empty") {
                                Text(
                                    "未找到可用${group.displayName}目录",
                                    color = ISaverSecondaryText,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        } else {
                            locationItems(
                                entries = group.children.map { PresentedLocation(it, it.path.value) },
                                testTag = "grid-app",
                                onOpenLocation = onOpenLocation,
                                onEdit = onEdit,
                                onRemove = onRemove,
                                onRevalidate = onRevalidate,
                            )
                        }
                    }
                }

                LocationSection.COMMON -> {
                    fullSpanItem("section-common") { SectionTitle("通用位置", Modifier.testTag("section-common")) }
                    if (content.common.isEmpty()) {
                        fullSpanItem("common-empty") {
                            Text("暂无通用位置", color = ISaverSecondaryText, modifier = Modifier.padding(16.dp))
                        }
                    } else {
                        locationItems(
                            content.common.map { PresentedLocation(it, it.path.value) },
                            "grid-common",
                            onOpenLocation,
                            onEdit,
                            onRemove,
                            onRevalidate,
                        )
                    }
                    fullSpanItem("common.trash") { TrashLocationRow(onOpenTrash) }
                }

                LocationSection.CUSTOM -> {
                    if (virtualViewState != null) {
                        fullSpanItem("section-custom") {
                            VirtualSectionHeader(virtualViewState, Modifier.testTag("section-custom"))
                        }
                        if (virtualViewState.children.isEmpty() && !virtualViewState.loading) {
                            fullSpanItem("virtual-empty") {
                                Text("暂无虚拟视图文件夹", color = ISaverSecondaryText, modifier = Modifier.padding(16.dp))
                            }
                        } else {
                            gridItems(virtualViewState.children, key = { it.id }) { node ->
                                VirtualViewGridCell(node, onOpenVirtualFolder, onOpenVirtualReference, onManageVirtualNode)
                            }
                        }
                    } else {
                    fullSpanItem("section-custom") { SectionTitle("自定义位置", Modifier.testTag("section-custom")) }
                    if (content.custom.isEmpty()) {
                        fullSpanItem("custom-empty") {
                            Text("暂无自定义位置", color = ISaverSecondaryText, modifier = Modifier.padding(16.dp))
                        }
                    } else {
                        locationItems(
                            content.custom.map {
                                PresentedLocation(it.location, it.availability.label(it.location.path), it.availability)
                            },
                            "grid-custom",
                            onOpenLocation,
                            onEdit,
                            onRemove,
                            onRevalidate,
                        )
                    }
                    }
                }
            }
        }
        if (state.loading) fullSpanItem("locations-loading") {
            Text("正在加载位置…", color = ISaverSecondaryText, modifier = Modifier.padding(16.dp))
        }
    }
}

private fun LazyGridScope.fullSpanItem(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.locationItems(
    entries: List<PresentedLocation>,
    testTag: String,
    onOpenLocation: (RootPath, String) -> Unit,
    onEdit: (StorageLocation.Direct) -> Unit,
    onRemove: (StorageLocation.Direct) -> Unit,
    onRevalidate: (LocationId) -> Unit,
) {
    gridItems(entries, key = { it.location.id.value }) { item ->
        Column(Modifier.testTag(testTag)) {
            FileGridCell(
                entry = item.location.asDirectoryEntry(item.availability),
                displayName = item.location.displayName,
                metadata = item.metadata,
                enabled = item.availability.isOpenable,
                onClick = { onOpenLocation(item.location.path, item.location.displayName) },
            )
            if (item.location.source == StorageLocation.Source.CUSTOM) {
                Row {
                    TextButton(
                        onClick = { onRevalidate(item.location.id) },
                        modifier = Modifier.semantics {
                            contentDescription = "重新校验视图：${item.location.displayName}"
                        },
                    ) { Text("校验") }
                    TextButton(
                        onClick = { onEdit(item.location) },
                        modifier = Modifier.semantics { contentDescription = "编辑视图：${item.location.displayName}" },
                    ) { Text("编辑") }
                    TextButton(
                        onClick = { onRemove(item.location) },
                        modifier = Modifier.semantics { contentDescription = "移除视图：${item.location.displayName}" },
                    ) { Text("移除") }
                }
            }
        }
    }
}

@Composable
private fun LocationRow(
    location: StorageLocation.Direct,
    metadata: String,
    availability: LocationAvailability? = null,
    onClick: () -> Unit,
) {
    FileListRow(
        entry = location.asDirectoryEntry(availability),
        displayName = location.displayName,
        metadata = metadata,
        enabled = availability.isOpenable,
        onClick = onClick,
    )
}

private fun List<ResolvedAppLocation>.filterForQuery(query: String): List<ResolvedAppLocation> = mapNotNull { group ->
    if (query.isEmpty() || group.displayName.contains(query, ignoreCase = true)) group
    else group.copy(children = group.children.filter { it.displayName.contains(query, ignoreCase = true) })
        .takeIf { it.children.isNotEmpty() }
}

private fun sortLocationContent(
    apps: List<ResolvedAppLocation>,
    common: List<StorageLocation.Direct>,
    custom: List<CustomLocationState>,
    sortSpec: SortSpec,
    virtualMode: Boolean,
): SortedLocationContent {
    val itemDirection = when (sortSpec.field) {
        SortField.TYPE -> SortDirection.ASCENDING
        SortField.DISPLAY_NAME, SortField.MODIFIED_AT, SortField.SIZE -> sortSpec.direction
    }
    val locationComparator = locationComparator(itemDirection)
    val groupComparator = appGroupComparator(itemDirection)
    return SortedLocationContent(
        apps = apps
            .sortedWith(groupComparator)
            .map { group -> group.copy(children = group.children.sortedWith(locationComparator)) },
        common = common.sortedWith(locationComparator),
        custom = custom.sortedWith { left, right ->
            locationComparator.compare(left.location, right.location)
        },
        sectionOrder = locationSectionOrder(apps.isNotEmpty(), virtualMode, sortSpec),
    )
}

internal fun locationSectionOrder(
    hasApps: Boolean,
    virtualMode: Boolean,
    sortSpec: SortSpec,
): List<LocationSection> {
    val baseOrder = buildList {
        if (hasApps) add(LocationSection.APP)
        if (virtualMode) {
            add(LocationSection.CUSTOM)
            add(LocationSection.COMMON)
        } else {
            add(LocationSection.COMMON)
            add(LocationSection.CUSTOM)
        }
    }
    return if (!virtualMode && sortSpec.field == SortField.TYPE &&
        sortSpec.direction == SortDirection.DESCENDING
    ) {
        baseOrder.reversed()
    } else {
        baseOrder
    }
}

private fun locationComparator(direction: SortDirection): Comparator<StorageLocation.Direct> =
    Comparator<StorageLocation.Direct> { left, right ->
        compareDisplayText(left.displayName, right.displayName)
            .takeIf { it != 0 }
            ?: compareDisplayText(left.id.value, right.id.value).takeIf { it != 0 }
            ?: compareDisplayText(left.path.value, right.path.value)
    }.inDirection(direction)

private fun appGroupComparator(direction: SortDirection): Comparator<ResolvedAppLocation> =
    Comparator<ResolvedAppLocation> { left, right ->
        compareDisplayText(left.displayName, right.displayName)
            .takeIf { it != 0 }
            ?: compareDisplayText(left.templateId.value, right.templateId.value)
    }.inDirection(direction)

private fun compareDisplayText(left: String, right: String): Int =
    left.compareTo(right, ignoreCase = true).takeIf { it != 0 } ?: left.compareTo(right)

private fun <T> Comparator<T>.inDirection(direction: SortDirection): Comparator<T> =
    if (direction == SortDirection.ASCENDING) this else reversed()

private data class SortedLocationContent(
    val apps: List<ResolvedAppLocation>,
    val common: List<StorageLocation.Direct>,
    val custom: List<CustomLocationState>,
    val sectionOrder: List<LocationSection>,
)

internal enum class LocationSection { APP, COMMON, CUSTOM }

private data class PresentedLocation(
    val location: StorageLocation.Direct,
    val metadata: String,
    val availability: LocationAvailability? = null,
)

private fun LocationAvailability.label(path: RootPath): String {
    if (RootPathRiskPolicy.isProtected(path)) return "系统保护区域 · 只读"
    return when (this) {
        LocationAvailability.Checking -> "正在检查…"
        is LocationAvailability.Available -> if (writable) "可读写" else "只读"
        is LocationAvailability.Unavailable -> reason
    }
}

private val LocationAvailability?.isOpenable: Boolean
    get() = this == null || this is LocationAvailability.Available && readable

private fun StorageLocation.Direct.asDirectoryEntry(availability: LocationAvailability? = null) = DirectoryEntry(
    path = path,
    name = displayName,
    type = EntryType.DIRECTORY,
    sizeBytes = null,
    modifiedAtEpochSeconds = null,
    readable = (availability as? LocationAvailability.Available)?.readable ?: (availability == null),
    writable = (availability as? LocationAvailability.Available)?.writable ?: (availability == null),
    symbolicLink = false,
)
