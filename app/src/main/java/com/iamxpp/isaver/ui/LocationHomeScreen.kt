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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    onClearAddError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<StorageLocation.Direct?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<StorageLocation.Direct?>(null) }

    LaunchedEffect(state.saveSuccessVersion) {
        adding = false
        editor = null
    }

    val visibleApps = state.appGroups.filterForQuery(query)
    val visibleCommon = state.commonLocations.filter { it.displayName.contains(query, ignoreCase = true) }
    val visibleCustom = state.customLocations.filter { it.location.displayName.contains(query, ignoreCase = true) }

    if (displayMode == DisplayMode.LIST) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(ISaverBackground),
        ) {
            LocationHomeHeader(
                query = query,
                onQueryChange = { query = it },
                error = state.error,
                onAdd = { onClearAddError(); adding = true },
                onRetry = onRetry,
            )
            LocationList(
                state = state,
                apps = visibleApps,
                common = visibleCommon,
                custom = visibleCustom,
                onOpenLocation = onOpenLocation,
                onEdit = { onClearAddError(); editor = it },
                onRemove = { removal = it },
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        LocationHomeGrid(
            state = state,
            apps = visibleApps,
            common = visibleCommon,
            custom = visibleCustom,
            query = query,
            onQueryChange = { query = it },
            onAdd = { onClearAddError(); adding = true },
            onRetry = onRetry,
            onOpenLocation = onOpenLocation,
            onEdit = { onClearAddError(); editor = it },
            onRemove = { removal = it },
            modifier = modifier.fillMaxSize().background(ISaverBackground),
        )
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
}

@Composable
private fun LocationHomeHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    error: String?,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
) {
    Column {
        FilesLargeTitleHeader(title = "视图", onOverflow = {})
        FilesSearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Button(onClick = onAdd) { Text("添加位置") }
            if (error != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
        if (error != null) Text(error, modifier = Modifier.padding(horizontal = 16.dp))
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
            LocationRow(item.location, item.availability.label, item.availability) {
                onOpenLocation(item.location.path, item.location.displayName)
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
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun LocationHomeGrid(
    state: LocationHomeUiState,
    apps: List<ResolvedAppLocation>,
    common: List<StorageLocation.Direct>,
    custom: List<CustomLocationState>,
    query: String,
    onQueryChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    onOpenLocation: (RootPath, String) -> Unit,
    onEdit: (StorageLocation.Direct) -> Unit,
    onRemove: (StorageLocation.Direct) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.testTag("location-home-grid"),
    ) {
        fullSpanItem("home-header") {
            LocationHomeHeader(query, onQueryChange, state.error, onAdd, onRetry)
        }
        fullSpanItem("section-app") { SectionTitle("应用位置", Modifier.testTag("section-app")) }
        apps.forEach { group ->
            fullSpanItem(group.templateId.value) { Text(group.displayName, modifier = Modifier.padding(16.dp, 8.dp)) }
            if (group.empty) {
                fullSpanItem("${group.templateId.value}.empty") {
                    Text("未找到可用${group.displayName}目录", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            } else {
                locationItems(
                    entries = group.children.map { PresentedLocation(it, it.path.value) },
                    testTag = "grid-app",
                    onOpenLocation = onOpenLocation,
                    onEdit = onEdit,
                    onRemove = onRemove,
                )
            }
        }
        fullSpanItem("section-common") { SectionTitle("通用位置", Modifier.testTag("section-common")) }
        if (common.isEmpty()) {
            fullSpanItem("common-empty") { Text("暂无通用位置", modifier = Modifier.padding(16.dp)) }
        } else {
            locationItems(common.map { PresentedLocation(it, it.path.value) }, "grid-common", onOpenLocation, onEdit, onRemove)
        }
        fullSpanItem("section-custom") { SectionTitle("自定义位置", Modifier.testTag("section-custom")) }
        if (custom.isEmpty()) {
            fullSpanItem("custom-empty") { Text("暂无自定义位置", modifier = Modifier.padding(16.dp)) }
        } else {
            locationItems(custom.map { PresentedLocation(it.location, it.availability.label, it.availability) }, "grid-custom", onOpenLocation, onEdit, onRemove)
        }
        if (state.loading) fullSpanItem("locations-loading") { Text("正在加载位置…", modifier = Modifier.padding(16.dp)) }
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
