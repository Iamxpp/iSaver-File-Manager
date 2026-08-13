package com.iamxpp.isaver.ui.files

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamxpp.isaver.ReleaseFeatures
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverCard
import com.iamxpp.isaver.ui.theme.ISaverDivider
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText

data class FilesSaveAction(
    val enabled: Boolean,
    val onSave: () -> Unit,
    val label: String = "存储",
)

@Composable
fun FilesPageHeader(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onOverflow: () -> Unit,
    onBack: (() -> Unit)? = null,
    saveAction: FilesSaveAction? = null,
    topBarTestTag: String = "files-top-bar",
    searchTestTag: String = "files-search",
    statusBarInsets: WindowInsets = WindowInsets.statusBars,
    modifier: Modifier = Modifier,
    overflowMenuContent: @Composable BoxScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ISaverCard)
            .windowInsetsPadding(statusBarInsets.only(WindowInsetsSides.Top)),
    ) {
        FilesTopBar(
            title = title,
            onBack = onBack,
            onOverflow = onOverflow,
            saveAction = saveAction,
            testTag = topBarTestTag,
            overflowMenuContent = overflowMenuContent,
        )
        FilesSearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier
                .testTag(searchTestTag)
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
fun FilesTopBar(
    title: String,
    onOverflow: () -> Unit,
    onBack: (() -> Unit)? = null,
    saveAction: FilesSaveAction? = null,
    testTag: String = "files-top-bar",
    modifier: Modifier = Modifier,
    overflowMenuContent: @Composable BoxScope.() -> Unit = {},
) {
    val actionWidth = if (saveAction?.label == null || saveAction.label.length <= 2) 48.dp else 96.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(ISaverCard)
            .padding(horizontal = 16.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(actionWidth).height(48.dp), contentAlignment = Alignment.Center) {
            if (onBack != null) {
                HeaderAction(
                    contentDescription = "返回",
                    onClick = onBack,
                ) { BackChevron() }
            }
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                color = ISaverPrimaryText,
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag("files-top-bar-title")
                    .semantics { contentDescription = "页面标题：$title" },
            )
        }
        Box(Modifier.width(actionWidth).height(48.dp), contentAlignment = Alignment.Center) {
            if (saveAction == null) {
                HeaderAction(
                    contentDescription = "更多操作",
                    onClick = onOverflow,
                    modifier = Modifier.testTag("files-top-bar-overflow"),
                ) { OverflowGlyph() }
                overflowMenuContent()
            } else {
                TextButton(
                    onClick = saveAction.onSave,
                    enabled = saveAction.enabled,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .width(actionWidth)
                        .height(48.dp)
                        .testTag("files-top-bar-save"),
                ) {
                    Text(
                        text = saveAction.label,
                        color = if (saveAction.enabled) ISaverBlue else ISaverSecondaryText,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
fun FilesBottomBar(
    selectedTab: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(78.dp)
            .background(ISaverCard)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        HomeTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(78.dp)
                    .selectable(
                        selected = selected,
                        onClick = { onSelect(tab) },
                        role = Role.Tab,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TabGlyph(tab = tab, selected = selected)
                Text(
                    text = tab.label,
                    color = if (selected) ISaverBlue else ISaverSecondaryText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
fun FilesSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = ISaverPrimaryText),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(ISaverBackground, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "搜索文件" },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchGlyph()
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索",
                            color = ISaverSecondaryText,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun FileListRow(
    entry: DirectoryEntry,
    displayName: String,
    metadata: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = "列表项：$displayName",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) ISaverBlue.copy(alpha = .10f) else ISaverCard)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                this.selected = selected
            }
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 78.dp)
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EntryGlyph(entry = entry, modifier = Modifier.size(width = 58.dp, height = 48.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    text = displayName,
                    color = ISaverPrimaryText,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metadata,
                    color = ISaverSecondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (entry.type == EntryType.DIRECTORY && enabled) {
                ChevronGlyph(Modifier.padding(horizontal = 14.dp))
            } else {
                Spacer(Modifier.width(16.dp))
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 86.dp),
            thickness = 0.5.dp,
            color = ISaverDivider,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun FileGridCell(
    entry: DirectoryEntry,
    displayName: String,
    metadata: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = "网格项：$displayName",
) {
    Column(
        modifier = modifier
            .heightIn(min = 150.dp)
            .background(if (selected) ISaverBlue.copy(alpha = .10f) else ISaverCard, RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                this.selected = selected
            }
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EntryGlyph(entry = entry, modifier = Modifier.size(width = 84.dp, height = 66.dp))
        Text(
            text = displayName,
            color = ISaverPrimaryText,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = metadata,
            color = ISaverSecondaryText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun <T> FilesGrid(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    itemContent: @Composable LazyGridItemScope.(T) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMN_COUNT),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxSize(),
    ) {
        items(
            items = items,
            key = key,
            itemContent = itemContent,
        )
    }
}

@Composable
fun FilesOverflowMenu(
    expanded: Boolean,
    displayMode: DisplayMode,
    sortSpec: SortSpec,
    onDismissRequest: () -> Unit,
    onDisplayModeChange: (DisplayMode) -> Unit,
    onSortFieldChange: (SortField) -> Unit,
    onSortDirectionToggle: () -> Unit,
    onCreateFolder: () -> Unit,
    onCreateFile: (() -> Unit)? = null,
    onCompress: () -> Unit,
    onConnectServer: () -> Unit,
    canCreateFolder: Boolean,
    canCreateFile: Boolean = false,
    canCompress: Boolean,
    canConnectServer: Boolean,
    onAddLocation: (() -> Unit)? = null,
    addLocationLabel: String = "添加位置",
    onOpenTasks: (() -> Unit)? = null,
    onGoForward: (() -> Unit)? = null,
    onOpenDeepSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .widthIn(min = 280.dp)
            .background(ISaverCard),
    ) {
        if (onAddLocation != null) {
            FilesMenuItem(
                text = addLocationLabel,
                onClick = onAddLocation,
                modifier = Modifier.testTag("views-add-location-menu"),
            )
        }
        FilesMenuItem(
            text = "新建文件夹",
            enabled = canCreateFolder,
            onClick = onCreateFolder,
        )
        if (onCreateFile != null) {
            FilesMenuItem(
                text = "新建文件",
                enabled = canCreateFile,
                onClick = onCreateFile,
            )
        }
        if (onOpenTasks != null) {
            FilesMenuItem(text = "任务中心", onClick = onOpenTasks)
        }
        if (onGoForward != null) {
            FilesMenuItem(text = "前进", onClick = onGoForward)
        }
        if (onOpenDeepSearch != null) {
            FilesMenuItem(text = "深度搜索", onClick = onOpenDeepSearch)
        }
        FilesMenuItem(
            text = "压缩文件",
            enabled = canCompress,
            onClick = onCompress,
        )
        if (ReleaseFeatures.remoteServers) {
            FilesMenuItem(
                text = "连接服务器",
                enabled = canConnectServer,
                onClick = onConnectServer,
            )
        }
        HorizontalDivider(color = ISaverDivider)
        DisplayMode.entries.forEach { mode ->
            FilesMenuItem(
                text = mode.label,
                selected = displayMode == mode,
                onClick = { onDisplayModeChange(mode) },
            )
        }
        HorizontalDivider(color = ISaverDivider)
        SortField.entries.forEach { field ->
            FilesMenuItem(
                text = field.label,
                selected = sortSpec.field == field,
                onClick = { onSortFieldChange(field) },
            )
        }
        FilesMenuItem(
            text = if (sortSpec.direction == SortDirection.ASCENDING) "升序" else "降序",
            selected = true,
            onClick = onSortDirectionToggle,
        )
    }
}

@Composable
private fun FilesMenuItem(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    val selectionModifier = if (selected == null) {
        modifier
    } else {
        modifier.semantics { this.selected = selected }
    }
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = if (enabled) ISaverPrimaryText else ISaverSecondaryText,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = {
            MenuSelectionMark(selected = selected == true)
        },
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = selectionModifier.heightIn(min = 48.dp),
    )
}

private val DisplayMode.label: String
    get() = when (this) {
        DisplayMode.LIST -> "列表"
        DisplayMode.GRID -> "图标"
    }

private const val GRID_COLUMN_COUNT = 3

private val SortField.label: String
    get() = when (this) {
        SortField.DISPLAY_NAME -> "名称"
        SortField.TYPE -> "种类"
        SortField.MODIFIED_AT -> "日期"
        SortField.SIZE -> "大小"
    }

private val HomeTab.label: String
    get() = when (this) {
        HomeTab.RECENT -> "最近项目"
        HomeTab.VIEWS -> "视图"
        HomeTab.BROWSE -> "浏览"
    }

@Composable
private fun SearchGlyph() {
    Canvas(Modifier.size(24.dp)) {
        drawCircle(
            color = ISaverSecondaryText,
            radius = size.minDimension * .29f,
            center = Offset(size.width * .42f, size.height * .42f),
            style = Stroke(width = 2.dp.toPx()),
        )
        drawLine(
            color = ISaverSecondaryText,
            start = Offset(size.width * .62f, size.height * .62f),
            end = Offset(size.width * .84f, size.height * .84f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun EntryGlyph(entry: DirectoryEntry, modifier: Modifier = Modifier) {
    when (entry.type) {
        EntryType.DIRECTORY -> FolderGlyph(modifier)
        EntryType.FILE, EntryType.OTHER -> DocumentGlyph(modifier)
    }
}

@Composable
private fun FolderGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val folderColor = ISaverBlue.copy(alpha = .62f)
        drawRoundRect(
            color = folderColor.copy(alpha = .82f),
            topLeft = Offset(size.width * .04f, size.height * .12f),
            size = Size(size.width * .45f, size.height * .34f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
        )
        drawRoundRect(
            color = folderColor,
            topLeft = Offset(size.width * .02f, size.height * .27f),
            size = Size(size.width * .96f, size.height * .68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
        )
    }
}

@Composable
private fun DocumentGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val strokeWidth = 1.dp.toPx()
        val fold = size.minDimension * .24f
        val left = size.width * .2f
        val top = size.height * .04f
        val right = size.width * .8f
        val bottom = size.height * .96f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(left, top)
            lineTo(right - fold, top)
            lineTo(right, top + fold)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }
        drawPath(path, color = ISaverCard)
        drawPath(path, color = ISaverDivider, style = Stroke(strokeWidth))
        drawLine(ISaverDivider, Offset(right - fold, top), Offset(right - fold, top + fold), strokeWidth)
        drawLine(ISaverDivider, Offset(right - fold, top + fold), Offset(right, top + fold), strokeWidth)
    }
}

@Composable
private fun ChevronGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        drawLine(ISaverSecondaryText, Offset(size.width * .35f, size.height * .2f), Offset(size.width * .65f, size.height * .5f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(ISaverSecondaryText, Offset(size.width * .65f, size.height * .5f), Offset(size.width * .35f, size.height * .8f), 2.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun MenuSelectionMark(selected: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Canvas(Modifier.size(18.dp)) {
                drawLine(ISaverBlue, Offset(size.width * .12f, size.height * .55f), Offset(size.width * .4f, size.height * .82f), 2.5.dp.toPx(), StrokeCap.Round)
                drawLine(ISaverBlue, Offset(size.width * .4f, size.height * .82f), Offset(size.width * .9f, size.height * .18f), 2.5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun HeaderAction(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

@Composable
private fun BackChevron() {
    Canvas(Modifier.size(24.dp)) {
        drawLine(ISaverBlue, Offset(size.width * .62f, size.height * .18f), Offset(size.width * .32f, size.height * .5f), 2.5.dp.toPx(), StrokeCap.Round)
        drawLine(ISaverBlue, Offset(size.width * .32f, size.height * .5f), Offset(size.width * .62f, size.height * .82f), 2.5.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun OverflowGlyph() {
    Canvas(Modifier.size(30.dp)) {
        drawCircle(ISaverBlue, style = Stroke(width = 2.dp.toPx()))
        val radius = 1.5.dp.toPx()
        listOf(.32f, .5f, .68f).forEach { x ->
            drawCircle(ISaverBlue, radius, Offset(size.width * x, size.height * .5f))
        }
    }
}

@Composable
private fun TabGlyph(tab: HomeTab, selected: Boolean) {
    val color = if (selected) ISaverBlue else ISaverSecondaryText
    Canvas(Modifier.size(28.dp)) {
        when (tab) {
            HomeTab.RECENT -> {
                drawCircle(color, style = Stroke(2.5.dp.toPx()))
                drawLine(color, center, Offset(center.x, size.height * .28f), 2.5.dp.toPx(), StrokeCap.Round)
                drawLine(color, center, Offset(size.width * .29f, size.height * .62f), 2.5.dp.toPx(), StrokeCap.Round)
            }
            HomeTab.VIEWS -> {
                drawRoundRect(color, topLeft = Offset(size.width * .08f, size.height * .25f), size = androidx.compose.ui.geometry.Size(size.width * .84f, size.height * .58f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(2.5.dp.toPx()))
                drawCircle(color, 3.dp.toPx(), Offset(size.width * .72f, size.height * .28f))
            }
            HomeTab.BROWSE -> {
                drawRoundRect(color, topLeft = Offset(size.width * .06f, size.height * .25f), size = androidx.compose.ui.geometry.Size(size.width * .88f, size.height * .62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                drawRoundRect(color.copy(alpha = .78f), topLeft = Offset(size.width * .08f, size.height * .15f), size = androidx.compose.ui.geometry.Size(size.width * .42f, size.height * .25f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
        }
    }
}
