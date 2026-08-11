package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.transfer.TransferUiState
import com.iamxpp.isaver.remote.RemoteConnectionDraft
import com.iamxpp.isaver.remote.RemoteConnectionUiState
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FilesBottomBar
import com.iamxpp.isaver.ui.files.FilesSaveAction
import com.iamxpp.isaver.ui.files.HomeTab
import com.iamxpp.isaver.ui.files.SortSpec
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.recent.RecentScreen
import com.iamxpp.isaver.ui.recent.RecentUiItem
import com.iamxpp.isaver.ui.recent.RecentUiState
import com.iamxpp.isaver.ui.archive.ArchiveScreen
import com.iamxpp.isaver.ui.archive.ArchiveUiState

@Composable
fun ISaverHomeScreen(
    homeState: ISaverHomeUiState,
    locationState: LocationHomeUiState,
    browserState: BrowserUiState,
    displayMode: DisplayMode,
    sortSpec: SortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
    onSelectTab: (HomeTab) -> Unit,
    onOpenLocation: (RootPath, String) -> Unit,
    onAddCustomLocation: (String, String) -> Unit,
    onEditCustomLocation: (LocationId, String, String) -> Unit,
    onRemoveCustomLocation: (LocationId) -> Unit,
    onRetryLocations: () -> Unit,
    onClearLocationError: () -> Unit = {},
    onRevalidateCustomLocation: (LocationId) -> Unit = {},
    onEnterDirectory: (DirectoryEntry) -> Unit,
    onBrowserBack: () -> Unit,
    onRetryBrowser: () -> Unit,
    onLoadMore: () -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onDisplayModeChange: (DisplayMode) -> Unit = {},
    onSortChange: (SortSpec) -> Unit = {},
    onCreateDirectory: (String) -> Unit = {},
    onToggleSelection: (DirectoryEntry) -> Unit = {},
    onOpenBrowserEntry: (DirectoryEntry) -> Unit = {},
    onClearBrowserSelection: () -> Unit = {},
    onDismissFileInfo: () -> Unit = {},
    onDismissFileOpenError: () -> Unit = {},
    onShareBrowserEntry: (DirectoryEntry) -> Unit = {},
    onDismissFileShareError: () -> Unit = {},
    onMoveBrowserEntry: ((DirectoryEntry) -> Unit)? = null,
    onMoveHere: () -> Unit = {},
    onDismissFileMoveError: () -> Unit = {},
    onCompress: (String) -> Unit = {},
    onDismissCompressionMessage: () -> Unit = {},
    onConnectServer: ((RemoteConnectionDraft) -> Unit)? = null,
    remoteConnectionState: RemoteConnectionUiState = RemoteConnectionUiState.Idle,
    onDismissRemoteMessage: () -> Unit = {},
    onRefreshRemote: () -> Unit = {},
    onCreateRemoteDirectory: (String) -> Unit = {},
    transferState: TransferUiState = TransferUiState.Idle,
    onSave: () -> Unit = {},
    onStemChange: (String) -> Unit = {},
    onExtensionChange: (String) -> Unit = {},
    onRetryTransfer: () -> Unit = {},
    onAcknowledgeUncertain: () -> Unit = {},
    onContinueQueued: () -> Unit = {},
    recentState: RecentUiState = RecentUiState(),
    onOpenRecent: (RecentUiItem) -> Unit = {},
    onRefreshRecent: () -> Unit = {},
    onDismissRecentFileInfo: () -> Unit = {},
    archiveState: ArchiveUiState = ArchiveUiState(),
    onArchiveBack: () -> Unit = {},
    onEnterArchiveDirectory: (com.iamxpp.isaver.archive.ArchiveNode) -> Unit = {},
    onArchiveQueryChange: (String) -> Unit = {},
    onArchiveDisplayModeChange: (DisplayMode) -> Unit = {},
    onChooseExtractionTarget: () -> Unit = {},
    onRetryArchive: () -> Unit = {},
    onCancelExtraction: () -> Unit = {},
    onDismissArchiveOperation: () -> Unit = {},
    onExtractHere: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val saveMode = transferState != TransferUiState.Idle
    val saveAction = if (saveMode) {
        FilesSaveAction(
            enabled = (transferState as? TransferUiState.Choosing)?.canSave == true &&
                homeState.destination is HomeDestination.Browser,
            onSave = onSave,
        )
    } else {
        null
    }
    val extractionDestination = homeState.destination as? HomeDestination.ExtractionTarget
    val extractionAction = extractionDestination?.let {
        FilesSaveAction(
            enabled = it.targetBrowser != null && browserState.canCreateDirectory,
            onSave = onExtractHere,
            label = "解压到此处",
        )
    }
    val moveDestination = homeState.destination as? HomeDestination.MoveTarget
    val moveAction = moveDestination?.let {
        FilesSaveAction(
            enabled = it.targetBrowser != null &&
                browserState.canCreateDirectory &&
                browserState.currentPath != it.sourceBrowser.path &&
                !browserState.movingFile,
            onSave = onMoveHere,
            label = if (browserState.movingFile) "正在移动" else "移动到这里",
        )
    }

    Column(modifier.fillMaxSize().background(ISaverBackground)) {
        when (homeState.destination) {
            is HomeDestination.Tab -> when (homeState.selectedTab) {
                HomeTab.RECENT -> RecentScreen(
                    state = recentState,
                    displayMode = displayMode,
                    onOpen = onOpenRecent,
                    onRefresh = onRefreshRecent,
                    modifier = Modifier.weight(1f),
                )
                HomeTab.VIEWS -> LocationHomeScreen(
                    state = locationState,
                    displayMode = displayMode,
                    onOpenLocation = onOpenLocation,
                    onAdd = onAddCustomLocation,
                    onEdit = onEditCustomLocation,
                    onRemove = onRemoveCustomLocation,
                    onRetry = onRetryLocations,
                    onClearAddError = onClearLocationError,
                    onRevalidate = onRevalidateCustomLocation,
                    sortSpec = sortSpec,
                    onDisplayModeChange = onDisplayModeChange,
                    onSortChange = onSortChange,
                    saveAction = saveAction,
                    modifier = Modifier.weight(1f),
                )
                HomeTab.BROWSE -> Unit
            }
            is HomeDestination.Browser -> BrowserScreen(
                state = browserState,
                onEnterDirectory = onEnterDirectory,
                onBack = onBrowserBack,
                onRetry = onRetryBrowser,
                onLoadMore = onLoadMore,
                onSearchQueryChange = onSearchQueryChange,
                onDisplayModeChange = onDisplayModeChange,
                onSortChange = onSortChange,
                onCreateDirectory = onCreateDirectory,
                onToggleSelection = onToggleSelection,
                onOpenEntry = onOpenBrowserEntry,
                onSelectEntry = onToggleSelection,
                onClearSelection = onClearBrowserSelection,
                onDismissFileInfo = onDismissFileInfo,
                onDismissFileOpenError = onDismissFileOpenError,
                onShareEntry = onShareBrowserEntry,
                onDismissFileShareError = onDismissFileShareError,
                onMoveEntry = if (saveMode) null else onMoveBrowserEntry,
                onDismissFileMoveError = onDismissFileMoveError,
                onCompress = onCompress,
                onDismissCompressionMessage = onDismissCompressionMessage,
                onConnectServer = onConnectServer,
                remoteConnectionState = remoteConnectionState,
                onDismissRemoteMessage = onDismissRemoteMessage,
                onRefreshRemote = onRefreshRemote,
                onCreateRemoteDirectory = onCreateRemoteDirectory,
                saveAction = saveAction,
                modifier = Modifier.weight(1f),
            )
            is HomeDestination.Archive -> ArchiveScreen(
                state = archiveState,
                onBack = onArchiveBack,
                onEnter = onEnterArchiveDirectory,
                onQueryChange = onArchiveQueryChange,
                onDisplayModeChange = onArchiveDisplayModeChange,
                onChooseExtractionTarget = onChooseExtractionTarget,
                onRetry = onRetryArchive,
                onCancelExtraction = onCancelExtraction,
                onDismissOperation = onDismissArchiveOperation,
                modifier = Modifier.weight(1f),
            )
            is HomeDestination.ExtractionTarget -> if (
                homeState.selectedTab == HomeTab.BROWSE && extractionDestination?.targetBrowser != null
            ) {
                BrowserScreen(
                    state = browserState,
                    onEnterDirectory = onEnterDirectory,
                    onBack = onBrowserBack,
                    onRetry = onRetryBrowser,
                    onLoadMore = onLoadMore,
                    onSearchQueryChange = onSearchQueryChange,
                    onDisplayModeChange = onDisplayModeChange,
                    onSortChange = onSortChange,
                    saveAction = extractionAction,
                    modifier = Modifier.weight(1f),
                )
            } else if (homeState.selectedTab == HomeTab.RECENT) {
                RecentScreen(
                    state = recentState,
                    displayMode = displayMode,
                    onOpen = onOpenRecent,
                    onRefresh = onRefreshRecent,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LocationHomeScreen(
                    state = locationState,
                    displayMode = displayMode,
                    onOpenLocation = onOpenLocation,
                    onAdd = onAddCustomLocation,
                    onEdit = onEditCustomLocation,
                    onRemove = onRemoveCustomLocation,
                    onRetry = onRetryLocations,
                    onClearAddError = onClearLocationError,
                    onRevalidate = onRevalidateCustomLocation,
                    sortSpec = sortSpec,
                    onDisplayModeChange = onDisplayModeChange,
                    onSortChange = onSortChange,
                    saveAction = extractionAction,
                    modifier = Modifier.weight(1f),
                )
            }
            is HomeDestination.MoveTarget -> if (
                homeState.selectedTab == HomeTab.BROWSE && moveDestination?.targetBrowser != null
            ) {
                BrowserScreen(
                    state = browserState,
                    onEnterDirectory = onEnterDirectory,
                    onBack = onBrowserBack,
                    onRetry = onRetryBrowser,
                    onLoadMore = onLoadMore,
                    onSearchQueryChange = onSearchQueryChange,
                    onDisplayModeChange = onDisplayModeChange,
                    onSortChange = onSortChange,
                    onCreateDirectory = onCreateDirectory,
                    onDismissFileMoveError = onDismissFileMoveError,
                    fileActionsEnabled = false,
                    saveAction = moveAction,
                    modifier = Modifier.weight(1f),
                )
            } else if (homeState.selectedTab == HomeTab.RECENT) {
                RecentScreen(
                    state = recentState,
                    displayMode = displayMode,
                    onOpen = onOpenRecent,
                    onRefresh = onRefreshRecent,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LocationHomeScreen(
                    state = locationState,
                    displayMode = displayMode,
                    onOpenLocation = onOpenLocation,
                    onAdd = onAddCustomLocation,
                    onEdit = onEditCustomLocation,
                    onRemove = onRemoveCustomLocation,
                    onRetry = onRetryLocations,
                    onClearAddError = onClearLocationError,
                    onRevalidate = onRevalidateCustomLocation,
                    sortSpec = sortSpec,
                    onDisplayModeChange = onDisplayModeChange,
                    onSortChange = onSortChange,
                    saveAction = moveAction,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (saveMode) {
            InlineSaveBar(
                state = transferState,
                itemCount = when (homeState.destination) {
                    is HomeDestination.Browser -> browserState.totalCount
                    is HomeDestination.Tab -> if (homeState.selectedTab == HomeTab.VIEWS) {
                        locationState.visibleLocationCount()
                    } else {
                        0
                    }
                    is HomeDestination.Archive,
                    is HomeDestination.ExtractionTarget,
                    is HomeDestination.MoveTarget -> 0
                },
                onStemChange = onStemChange,
                onExtensionChange = onExtensionChange,
                onRetryTransfer = onRetryTransfer,
                onAcknowledgeUncertain = onAcknowledgeUncertain,
                onContinueQueued = onContinueQueued,
            )
        }
        FilesBottomBar(
            selectedTab = homeState.selectedTab,
            onSelect = onSelectTab,
            modifier = Modifier.testTag("files-bottom-bar"),
        )
    }
    recentState.fileInfo?.let { FileInfoDialog(it, onDismissRecentFileInfo) }
}

private fun LocationHomeUiState.visibleLocationCount(): Int =
    appGroups.sumOf { it.children.size } + commonLocations.size + customLocations.size
