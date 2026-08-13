package com.iamxpp.isaver.ui.dualpane

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.fileops.ConflictAction
import com.iamxpp.isaver.ui.BrowserScreen
import com.iamxpp.isaver.ui.BrowserUiState
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverBlue
import com.iamxpp.isaver.ui.theme.ISaverDivider
import com.iamxpp.isaver.ui.theme.ISaverPrimaryText
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText

data class DualPaneBrowserCallbacks(
    val enterDirectory: (DirectoryEntry) -> Unit,
    val back: () -> Unit,
    val forward: () -> Unit,
    val retry: () -> Unit,
    val loadMore: () -> Unit,
    val query: (String) -> Unit,
    val toggleSelection: (DirectoryEntry) -> Unit,
    val clearSelection: () -> Unit,
    val openEntry: (DirectoryEntry) -> Unit,
    val resolveConflict: (ConflictAction, Boolean) -> Unit,
    val dismissMoveError: () -> Unit,
    val dismissCopyError: () -> Unit,
    val dismissOpenError: () -> Unit,
    val dismissPreview: () -> Unit,
    val editPreview: (DirectoryEntry) -> Unit = {},
)

@Composable
fun DualPaneScreen(
    state: DualPaneState,
    primaryState: BrowserUiState,
    secondaryState: BrowserUiState,
    primaryCallbacks: DualPaneBrowserCallbacks,
    secondaryCallbacks: DualPaneBrowserCallbacks,
    onActivate: (PaneId) -> Unit,
    onClose: () -> Unit,
    onSync: () -> Unit,
    onSwap: () -> Unit,
    onToggleLock: () -> Unit,
    onCopyToOther: () -> Unit,
    onMoveToOther: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    Column(modifier.fillMaxSize().border(1.dp, ISaverDivider).padding(bottom = 1.dp)) {
        DualPaneToolbar(
            state = state,
            selectedCount = if (state.activePane == PaneId.PRIMARY) {
                primaryState.selectedEntries.size
            } else {
                secondaryState.selectedEntries.size
            },
            targetWritable = if (state.activePane == PaneId.PRIMARY) {
                secondaryState.canCreateDirectory
            } else {
                primaryState.canCreateDirectory
            },
            targetDifferent = primaryState.currentPath != secondaryState.currentPath,
            onClose = onClose,
            onSync = onSync,
            onSwap = onSwap,
            onToggleLock = onToggleLock,
            onCopyToOther = onCopyToOther,
            onMoveToOther = onMoveToOther,
        )
        val content: @Composable (Modifier) -> Unit = { paneModifier ->
            if (landscape) {
                Row(paneModifier) {
                    Pane(PaneId.PRIMARY, state, primaryState, primaryCallbacks, onActivate, Modifier.weight(1f))
                    Pane(PaneId.SECONDARY, state, secondaryState, secondaryCallbacks, onActivate, Modifier.weight(1f))
                }
            } else {
                Column(paneModifier) {
                    Pane(PaneId.PRIMARY, state, primaryState, primaryCallbacks, onActivate, Modifier.weight(1f))
                    Pane(PaneId.SECONDARY, state, secondaryState, secondaryCallbacks, onActivate, Modifier.weight(1f))
                }
            }
        }
        content(Modifier.weight(1f))
    }
}

@Composable
private fun Pane(
    pane: PaneId,
    dualState: DualPaneState,
    browserState: BrowserUiState,
    callbacks: DualPaneBrowserCallbacks,
    onActivate: (PaneId) -> Unit,
    modifier: Modifier,
) {
    val active = dualState.activePane == pane
    Column(
        modifier
            .border(if (active) 2.dp else 1.dp, if (active) ISaverBlue else ISaverDivider)
            .semantics { contentDescription = "${if (pane == PaneId.PRIMARY) "主" else "副"}窗口${if (active) "，已激活" else ""}" },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clickable { onActivate(pane) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (pane == PaneId.PRIMARY) "主窗" else "副窗",
                color = if (active) ISaverBlue else ISaverSecondaryText,
            )
            Text(
                browserState.currentPath.value,
                color = ISaverPrimaryText,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            if (dualState.lockedPane == pane) Text("已锁定", color = ISaverSecondaryText)
        }
        HorizontalDivider(color = ISaverDivider)
        BrowserScreen(
            state = browserState,
            onEnterDirectory = {
                onActivate(pane)
                callbacks.enterDirectory(it)
            },
            onBack = callbacks.back,
            onForward = callbacks.forward,
            onRetry = callbacks.retry,
            onLoadMore = callbacks.loadMore,
            onSearchQueryChange = callbacks.query,
            onToggleSelection = {
                onActivate(pane)
                callbacks.toggleSelection(it)
            },
            onSelectEntry = {
                onActivate(pane)
                callbacks.toggleSelection(it)
            },
            onClearSelection = callbacks.clearSelection,
            onOpenEntry = {
                onActivate(pane)
                callbacks.openEntry(it)
            },
            onResolveConflict = callbacks.resolveConflict,
            onDismissFileMoveError = callbacks.dismissMoveError,
            onDismissFileCopyError = callbacks.dismissCopyError,
            onDismissFileOpenError = callbacks.dismissOpenError,
            onDismissPreview = callbacks.dismissPreview,
            onEditPreview = callbacks.editPreview,
            fileActionsEnabled = false,
            selectionOnlyLongPress = true,
            forceListMode = true,
            compactHeader = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DualPaneToolbar(
    state: DualPaneState,
    selectedCount: Int,
    targetWritable: Boolean,
    targetDifferent: Boolean,
    onClose: () -> Unit,
    onSync: () -> Unit,
    onSwap: () -> Unit,
    onToggleLock: () -> Unit,
    onCopyToOther: () -> Unit,
    onMoveToOther: () -> Unit,
) {
    val targetUnlocked = state.lockedPane != state.activePane.other()
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaneToolbarAction(PaneAction.CLOSE, "关闭双窗口", true, onClose)
        PaneToolbarAction(PaneAction.SYNC, "同步到另一窗口", targetUnlocked, onSync)
        PaneToolbarAction(PaneAction.SWAP, "交换窗口", state.lockedPane == null, onSwap)
        PaneToolbarAction(
            PaneAction.LOCK,
            if (state.lockedPane == state.activePane) "解锁当前窗口" else "锁定当前窗口",
            true,
            onToggleLock,
        )
        val transferEnabled = selectedCount > 0 && targetWritable && targetDifferent && targetUnlocked
        PaneToolbarAction(PaneAction.COPY, "复制到另一窗口", transferEnabled, onCopyToOther)
        PaneToolbarAction(PaneAction.MOVE, "移动到另一窗口", transferEnabled, onMoveToOther)
    }
}

private enum class PaneAction { CLOSE, SYNC, SWAP, LOCK, COPY, MOVE }

@Composable
private fun PaneToolbarAction(
    action: PaneAction,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else .35f)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 2.dp.toPx()
            val color = ISaverBlue
            fun arrow(y: Float) {
                drawLine(color, Offset(size.width * .18f, size.height * y), Offset(size.width * .82f, size.height * y), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .82f, size.height * y), Offset(size.width * .65f, size.height * (y - .17f)), stroke, StrokeCap.Round)
            }
            when (action) {
                PaneAction.CLOSE -> {
                    drawLine(color, Offset(size.width * .25f, size.height * .25f), Offset(size.width * .75f, size.height * .75f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(size.width * .75f, size.height * .25f), Offset(size.width * .25f, size.height * .75f), stroke, StrokeCap.Round)
                }
                PaneAction.SYNC, PaneAction.SWAP -> {
                    arrow(.34f)
                    drawLine(color, Offset(size.width * .82f, size.height * .66f), Offset(size.width * .18f, size.height * .66f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(size.width * .18f, size.height * .66f), Offset(size.width * .35f, size.height * .83f), stroke, StrokeCap.Round)
                }
                PaneAction.LOCK -> {
                    drawRect(color, Offset(size.width * .25f, size.height * .45f), Size(size.width * .5f, size.height * .4f), style = Stroke(stroke))
                    drawArc(color, 180f, -180f, false, Offset(size.width * .34f, size.height * .12f), Size(size.width * .32f, size.height * .5f), style = Stroke(stroke))
                }
                PaneAction.COPY -> {
                    drawRect(color, Offset(size.width * .32f, size.height * .18f), Size(size.width * .5f, size.height * .5f), style = Stroke(stroke))
                    drawRect(color, Offset(size.width * .18f, size.height * .32f), Size(size.width * .5f, size.height * .5f), style = Stroke(stroke))
                }
                PaneAction.MOVE -> arrow(.5f)
            }
        }
    }
}
