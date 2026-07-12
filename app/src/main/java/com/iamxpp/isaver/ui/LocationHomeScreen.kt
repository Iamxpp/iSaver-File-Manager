package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.locations.ResolvedAppLocation
import com.iamxpp.isaver.locations.StorageLocation
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FileGridCell
import com.iamxpp.isaver.ui.files.FileListRow
import com.iamxpp.isaver.ui.files.FilesGrid
import com.iamxpp.isaver.ui.files.FilesLargeTitleHeader
import com.iamxpp.isaver.ui.files.FilesSearchField
import com.iamxpp.isaver.ui.theme.ISaverBackground

@Composable
fun LocationHomeScreen(
    state: LocationHomeUiState,
    displayMode: DisplayMode,
    onOpenLocation: (RootPath, String) -> Unit,
    onAdd: (String, String) -> Unit,
    onEdit: (LocationId, String, String) -> Unit,
    onRemove: (LocationId) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<StorageLocation.Direct?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<StorageLocation.Direct?>(null) }

    val visibleApps = state.appGroups.filterForQuery(query)
    val visibleCommon = state.commonLocations.filter { it.displayName.contains(query, ignoreCase = true) }
    val visibleCustom = state.customLocations.filter { it.location.displayName.contains(query, ignoreCase = true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ISaverBackground),
    ) {
        FilesLargeTitleHeader(title = "视图", onOverflow = {})
        FilesSearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Button(onClick = { adding = true }) { Text("添加位置") }
            if (state.error != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
        if (state.error != null) Text(state.error, modifier = Modifier.padding(horizontal = 16.dp))

        if (displayMode == DisplayMode.LIST) {
            LocationList(
                state = state,
                apps = visibleApps,
                common = visibleCommon,
                custom = visibleCustom,
                onOpenLocation = onOpenLocation,
                onEdit = { editor = it },
                onRemove = { removal = it },
                modifier = Modifier.weight(1f),
            )
        } else {
            val entries = buildList {
                visibleApps.forEach { addAll(it.children.map { location -> PresentedLocation(location, location.path.value) }) }
                addAll(visibleCommon.map { PresentedLocation(it, it.path.value) })
                addAll(visibleCustom.map { PresentedLocation(it.location, it.availability.label, it.availability) })
            }
            Column(Modifier.weight(1f)) {
                Text("应用位置", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                visibleApps.forEach { group ->
                    Text(group.displayName, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                    if (group.empty) Text("未找到可用${group.displayName}目录", modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                Text("通用位置 · 自定义位置", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                FilesGrid(
                    items = entries,
                    key = { it.location.id.value },
                    modifier = Modifier.weight(1f),
                ) { item ->
                    Column {
                        FileGridCell(
                            entry = item.location.asDirectoryEntry(),
                            displayName = item.location.displayName,
                            metadata = item.metadata,
                            onClick = {
                                if (item.availability == null || item.availability is LocationAvailability.Available) {
                                    onOpenLocation(item.location.path, item.location.displayName)
                                }
                            },
                        )
                        if (item.location.source == StorageLocation.Source.CUSTOM) {
                            Row {
                                TextButton(
                                    onClick = { editor = item.location },
                                    modifier = Modifier.semantics { contentDescription = "编辑视图：${item.location.displayName}" },
                                ) { Text("编辑") }
                                TextButton(
                                    onClick = { removal = item.location },
                                    modifier = Modifier.semantics { contentDescription = "移除视图：${item.location.displayName}" },
                                ) { Text("移除") }
                            }
                        }
                    }
                }
            }
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
            onDismiss = { adding = false; editor = null },
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
}

@Composable
private fun LocationList(
    state: LocationHomeUiState,
    apps: List<ResolvedAppLocation>,
    common: List<StorageLocation.Direct>,
    custom: List<CustomLocationState>,
    onOpenLocation: (RootPath, String) -> Unit,
    onEdit: (StorageLocation.Direct) -> Unit,
    onRemove: (StorageLocation.Direct) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        item { SectionTitle("应用位置") }
        apps.forEach { group ->
            item(key = group.templateId.value) { Text(group.displayName, modifier = Modifier.padding(16.dp, 8.dp)) }
            if (group.empty) {
                item(key = "${group.templateId.value}.empty") {
                    Text("未找到可用${group.displayName}目录", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            } else {
                items(group.children, key = { it.id.value }) { location ->
                    LocationRow(location, location.path.value) { onOpenLocation(location.path, location.displayName) }
                }
            }
        }

        item { SectionTitle("通用位置") }
        items(common, key = { it.id.value }) { location ->
            LocationRow(location, location.path.value) { onOpenLocation(location.path, location.displayName) }
        }

        item { SectionTitle("自定义位置") }
        items(custom, key = { it.location.id.value }) { item ->
            LocationRow(item.location, item.availability.label) {
                if (item.availability is LocationAvailability.Available) {
                    onOpenLocation(item.location.path, item.location.displayName)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TextButton(
                    onClick = { onEdit(item.location) },
                    modifier = Modifier.semantics { contentDescription = "编辑视图：${item.location.displayName}" },
                ) { Text("编辑") }
                TextButton(
                    onClick = { onRemove(item.location) },
                    modifier = Modifier.semantics { contentDescription = "移除视图：${item.location.displayName}" },
                ) { Text("移除视图") }
            }
        }
        if (state.loading) item { Text("正在加载位置…", modifier = Modifier.padding(16.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun LocationRow(location: StorageLocation.Direct, metadata: String, onClick: () -> Unit) {
    FileListRow(
        entry = location.asDirectoryEntry(),
        displayName = location.displayName,
        metadata = metadata,
        onClick = onClick,
    )
}

private fun List<ResolvedAppLocation>.filterForQuery(query: String): List<ResolvedAppLocation> = mapNotNull { group ->
    if (query.isEmpty() || group.displayName.contains(query, ignoreCase = true)) group
    else group.copy(children = group.children.filter { it.displayName.contains(query, ignoreCase = true) })
        .takeIf { it.children.isNotEmpty() }
}

private data class PresentedLocation(
    val location: StorageLocation.Direct,
    val metadata: String,
    val availability: LocationAvailability? = null,
)

private val LocationAvailability.label: String
    get() = when (this) {
        LocationAvailability.Checking -> "正在检查…"
        is LocationAvailability.Available -> if (writable) "可读写" else "只读"
        is LocationAvailability.Unavailable -> reason
    }

private fun StorageLocation.Direct.asDirectoryEntry() = DirectoryEntry(
    path = path,
    name = displayName,
    type = EntryType.DIRECTORY,
    sizeBytes = null,
    modifiedAtEpochSeconds = null,
    readable = true,
    writable = true,
    symbolicLink = false,
)
