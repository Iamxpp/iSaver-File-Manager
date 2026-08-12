package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.fileops.ConflictAction
import com.iamxpp.isaver.fileops.BatchRenameRule
import com.iamxpp.isaver.trash.TrashItem
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
import com.iamxpp.isaver.search.LocalSearchCriteria
import com.iamxpp.isaver.trash.RestoreConflictAction
import com.iamxpp.isaver.archive.ArchiveFormat

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
    onBrowserForward: () -> Unit = {},
    onToggleCurrentBookmark: () -> Unit = {},
    onOpenBookmark: (com.iamxpp.isaver.bookmarks.Bookmark) -> Unit = {},
    onStartDeepSearch: (LocalSearchCriteria) -> Unit = {},
    onCancelDeepSearch: () -> Unit = {},
    onClearDeepSearch: () -> Unit = {},
    onOpenDeepSearchResultLocation: (DirectoryEntry) -> Unit = {},
    onRetryBrowser: () -> Unit,
    onLoadMore: () -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onDisplayModeChange: (DisplayMode) -> Unit = {},
    onSortChange: (SortSpec) -> Unit = {},
    onCreateDirectory: (String) -> Unit = {},
    onCreateFile: (String) -> Unit = {},
    onDismissCreateDirectoryError: () -> Unit = {},
    onDismissCreateFileError: () -> Unit = {},
    onToggleSelection: (DirectoryEntry) -> Unit = {},
    onSelectAllVisible: () -> Unit = {},
    onInvertVisibleSelection: () -> Unit = {},
    onSelectSameType: () -> Unit = {},
    onOpenBrowserEntry: (DirectoryEntry) -> Unit = {},
    onOpenWithBrowserEntry: (DirectoryEntry) -> Unit = {},
    onClearBrowserSelection: () -> Unit = {},
    onDismissFileInfo: () -> Unit = {},
    onShowFileInfo: (DirectoryEntry) -> Unit = {},
    onCalculateChecksum: () -> Unit = {},
    onChecksumAlgorithmChange: (com.iamxpp.isaver.fileops.ChecksumAlgorithm) -> Unit = {},
    onDismissFileOpenError: () -> Unit = {},
    onDismissPreview: () -> Unit = {},
    onShareBrowserEntry: (DirectoryEntry) -> Unit = {},
    onShareBrowserSelection: () -> Unit = {},
    onRecycleBrowserSelection: ((List<DirectoryEntry>) -> Unit)? = null,
    onDismissFileShareError: () -> Unit = {},
    onMoveBrowserEntry: ((DirectoryEntry) -> Unit)? = null,
    onMoveBrowserSelection: (() -> Unit)? = null,
    onMoveHere: () -> Unit = {},
    onDismissFileMoveError: () -> Unit = {},
    onCopyBrowserEntry: ((DirectoryEntry) -> Unit)? = null,
    onCopyBrowserSelection: (() -> Unit)? = null,
    onCopyHere: () -> Unit = {},
    onDismissFileCopyError: () -> Unit = {},
    onResolveBrowserConflict: (ConflictAction, Boolean) -> Unit = { _, _ -> },
    onRenameBrowserEntry: ((DirectoryEntry, String) -> Unit)? = null,
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
    onCompress: (String, ArchiveFormat) -> Unit = { _, _ -> },
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
    val copyDestination = homeState.destination as? HomeDestination.CopyTarget
    val copyAction = copyDestination?.let {
        FilesSaveAction(
            enabled = it.targetBrowser != null &&
                browserState.canCreateDirectory &&
                browserState.currentPath != it.sourceBrowser.path &&
                !browserState.copyingFile,
            onSave = onCopyHere,
            label = if (browserState.copyingFile) "正在复制" else "复制到这里",
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
                onForward = onBrowserForward,
                onToggleCurrentBookmark = onToggleCurrentBookmark,
                onOpenBookmark = onOpenBookmark,
                onStartDeepSearch = onStartDeepSearch,
                onCancelDeepSearch = onCancelDeepSearch,
                onClearDeepSearch = onClearDeepSearch,
                onOpenDeepSearchResultLocation = onOpenDeepSearchResultLocation,
                onRetry = onRetryBrowser,
                onLoadMore = onLoadMore,
                onSearchQueryChange = onSearchQueryChange,
                onDisplayModeChange = onDisplayModeChange,
                onSortChange = onSortChange,
                onCreateDirectory = onCreateDirectory,
                onCreateFile = if (saveMode) null else onCreateFile,
                onDismissCreateError = onDismissCreateDirectoryError,
                onDismissCreateFileError = onDismissCreateFileError,
                onToggleSelection = onToggleSelection,
                onSelectAllVisible = onSelectAllVisible,
                onInvertVisibleSelection = onInvertVisibleSelection,
                onSelectSameType = onSelectSameType,
                onOpenEntry = onOpenBrowserEntry,
                onOpenWithEntry = if (saveMode) null else onOpenWithBrowserEntry,
                onSelectEntry = onToggleSelection,
                onClearSelection = onClearBrowserSelection,
                onDismissFileInfo = onDismissFileInfo,
                onShowFileInfo = onShowFileInfo,
                onCalculateChecksum = onCalculateChecksum,
                onChecksumAlgorithmChange = onChecksumAlgorithmChange,
                onDismissFileOpenError = onDismissFileOpenError,
                onDismissPreview = onDismissPreview,
                onShareEntry = onShareBrowserEntry,
                onShareSelection = if (saveMode) null else onShareBrowserSelection,
                onRecycleSelection = if (saveMode) null else onRecycleBrowserSelection,
                onDismissFileShareError = onDismissFileShareError,
                onMoveEntry = if (saveMode) null else onMoveBrowserEntry,
                onMoveSelection = if (saveMode) null else onMoveBrowserSelection,
                onDismissFileMoveError = onDismissFileMoveError,
                onCopyEntry = if (saveMode) null else onCopyBrowserEntry,
                onCopySelection = if (saveMode) null else onCopyBrowserSelection,
                onDismissFileCopyError = onDismissFileCopyError,
                onResolveConflict = onResolveBrowserConflict,
                onRenameEntry = if (saveMode) null else onRenameBrowserEntry,
                onPreviewBatchRename = if (saveMode) null else onPreviewBatchRename,
                onExecuteBatchRename = if (saveMode) null else onExecuteBatchRename,
                onDismissBatchRename = onDismissBatchRename,
                onClearFinishedTasks = onClearFinishedTasks,
                onPauseTask = onPauseTask,
                onResumeTask = onResumeTask,
                onCancelTask = onCancelTask,
                onRecycleEntry = if (saveMode) null else onRecycleEntry,
                onDeleteEntryPermanently = if (saveMode) null else onDeleteEntryPermanently,
                onRestoreTrashItem = onRestoreTrashItem,
                onRestoreTrashItemWithAction = onRestoreTrashItemWithAction,
                onDismissRestoreConflict = onDismissRestoreConflict,
                onDeleteTrashItemPermanently = onDeleteTrashItemPermanently,
                onRestoreAllTrashItems = onRestoreAllTrashItems,
                onClearTrash = onClearTrash,
                onDismissTrashError = onDismissTrashError,
                onDismissFileRenameError = onDismissFileRenameError,
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
                    onResolveConflict = onResolveBrowserConflict,
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
            is HomeDestination.CopyTarget -> if (
                homeState.selectedTab == HomeTab.BROWSE && copyDestination?.targetBrowser != null
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
                    onDismissFileCopyError = onDismissFileCopyError,
                    onResolveConflict = onResolveBrowserConflict,
                    fileActionsEnabled = false,
                    saveAction = copyAction,
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
                    saveAction = copyAction,
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
                    is HomeDestination.CopyTarget -> 0
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
    recentState.fileInfo?.let { FileInfoDialog(entry = it, onDismiss = onDismissRecentFileInfo) }
}

private fun LocationHomeUiState.visibleLocationCount(): Int =
    appGroups.sumOf { it.children.size } + commonLocations.size + customLocations.size
