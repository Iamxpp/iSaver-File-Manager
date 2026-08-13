package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.iamxpp.isaver.ui.virtualviews.VirtualViewUiState
import com.iamxpp.isaver.ui.virtualviews.VirtualReferencePickerDialog
import com.iamxpp.isaver.virtualviews.VirtualViewNode
import com.iamxpp.isaver.ui.dualpane.DualPaneBrowserCallbacks
import com.iamxpp.isaver.ui.dualpane.DualPaneScreen
import com.iamxpp.isaver.ui.dualpane.DualPaneState
import com.iamxpp.isaver.ui.dualpane.PaneId

@Composable
fun ISaverHomeScreen(
    homeState: ISaverHomeUiState,
    locationState: LocationHomeUiState,
    browserState: BrowserUiState,
    secondaryBrowserState: BrowserUiState? = null,
    dualPaneState: DualPaneState? = null,
    primaryDualPaneCallbacks: DualPaneBrowserCallbacks? = null,
    secondaryDualPaneCallbacks: DualPaneBrowserCallbacks? = null,
    onActivatePane: (PaneId) -> Unit = {},
    onCloseDualPane: () -> Unit = {},
    onSyncDualPane: () -> Unit = {},
    onSwapDualPane: () -> Unit = {},
    onTogglePaneLock: () -> Unit = {},
    onCopyToOtherPane: () -> Unit = {},
    onMoveToOtherPane: () -> Unit = {},
    onOpenDualPane: () -> Unit = {},
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
    virtualViewState: VirtualViewUiState? = null,
    onOpenVirtualFolder: (VirtualViewNode.VirtualFolder) -> Unit = {},
    onOpenVirtualReference: (VirtualViewNode.RealReference) -> Unit = {},
    onRetryVirtualReference: (VirtualViewNode.RealReference) -> Unit = {},
    onAddVirtualReferenceAgain: (VirtualViewNode.RealReference) -> Unit = {},
    onRebindVirtualReference: (VirtualViewNode.RealReference) -> Unit = {},
    onConfirmRebindVirtualReference: (DirectoryEntry) -> Unit = {},
    onNavigateVirtual: (String?) -> Unit = {},
    onCreateVirtualFolder: ((String) -> Unit)? = null,
    onRenameVirtualNode: (String, String) -> Unit = { _, _ -> },
    onMoveVirtualNode: (String, String?) -> Unit = { _, _ -> },
    onDeleteVirtualFolder: (String, Boolean) -> Unit = { _, _ -> },
    onDismissVirtualDelete: () -> Unit = {},
    onRemoveVirtualReference: (String) -> Unit = {},
    onAddCurrentToVirtualView: () -> Unit = {},
    onAddEntryToVirtualView: (DirectoryEntry) -> Unit = {},
    onOpenVirtualPickerFolder: (String?) -> Unit = {},
    onCreateVirtualPickerFolder: (String) -> Unit = {},
    onConfirmAddVirtualReference: (String) -> Unit = {},
    onDismissAddVirtualReference: () -> Unit = {},
    onClearVirtualMessage: () -> Unit = {},
    onEnterDirectory: (DirectoryEntry) -> Unit,
    onBrowserBack: () -> Unit,
    onBrowserForward: () -> Unit = {},
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
    onEditPreview: ((DirectoryEntry) -> Unit)? = null,
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
    var trashVisible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val saveMode = transferState != TransferUiState.Idle
    val saveAction = if (saveMode) {
        val choosing = transferState as? TransferUiState.Choosing
        val browserDestination = homeState.destination as? HomeDestination.Browser
        val targetReady = browserDestination != null &&
            browserDestination.path == browserState.currentPath &&
            choosing?.targetDirectory == browserState.currentPath
        FilesSaveAction(
            enabled = choosing?.canSave == true && targetReady,
            onSave = onSave,
            disabledReason = when {
                browserDestination == null -> VIRTUAL_SAVE_TARGET_REASON
                !targetReady -> "正在校验目标文件夹。"
                else -> choosing.targetMessage
            },
        )
    } else {
        null
    }
    val extractionDestination = homeState.destination as? HomeDestination.ExtractionTarget
    val extractionAction = extractionDestination?.let {
        val targetReady = canUseRealTarget(it.targetBrowser, browserState)
        FilesSaveAction(
            enabled = targetReady,
            onSave = onExtractHere,
            label = "解压到此处",
            disabledReason = targetDisabledReason(it.targetBrowser, browserState),
        )
    }
    val moveDestination = homeState.destination as? HomeDestination.MoveTarget
    val moveAction = moveDestination?.let {
        val targetReady = canUseRealTarget(it.targetBrowser, browserState)
        FilesSaveAction(
            enabled = targetReady &&
                browserState.currentPath != it.sourceBrowser.path &&
                !browserState.movingFile,
            onSave = onMoveHere,
            label = if (browserState.movingFile) "正在移动" else "移动到这里",
            disabledReason = when {
                !targetReady -> targetDisabledReason(it.targetBrowser, browserState)
                browserState.currentPath == it.sourceBrowser.path -> "不能移动到来源文件夹。"
                else -> "当前目录不可写。"
            },
        )
    }
    val copyDestination = homeState.destination as? HomeDestination.CopyTarget
    val copyAction = copyDestination?.let {
        val targetReady = canUseRealTarget(it.targetBrowser, browserState)
        FilesSaveAction(
            enabled = targetReady &&
                browserState.currentPath != it.sourceBrowser.path &&
                !browserState.copyingFile,
            onSave = onCopyHere,
            label = if (browserState.copyingFile) "正在复制" else "复制到这里",
            disabledReason = when {
                !targetReady -> targetDisabledReason(it.targetBrowser, browserState)
                browserState.currentPath == it.sourceBrowser.path -> "不能复制到来源文件夹。"
                else -> "当前目录不可写。"
            },
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
                    virtualViewState = virtualViewState,
                    onOpenVirtualFolder = onOpenVirtualFolder,
                    onOpenVirtualReference = onOpenVirtualReference,
                    onRetryVirtualReference = onRetryVirtualReference,
                    onAddVirtualReferenceAgain = onAddVirtualReferenceAgain,
                    onRebindVirtualReference = onRebindVirtualReference,
                    onNavigateVirtual = onNavigateVirtual,
                    onCreateVirtualFolder = onCreateVirtualFolder,
                    onRenameVirtualNode = onRenameVirtualNode,
                    onMoveVirtualNode = onMoveVirtualNode,
                    onDeleteVirtualFolder = onDeleteVirtualFolder,
                    onDismissVirtualDelete = onDismissVirtualDelete,
                    onRemoveVirtualReference = onRemoveVirtualReference,
                    onOpenTrash = { trashVisible = true },
                    modifier = Modifier.weight(1f),
                )
                HomeTab.BROWSE -> Unit
            }
            is HomeDestination.Browser -> if (
                dualPaneState?.enabled == true &&
                secondaryBrowserState != null &&
                primaryDualPaneCallbacks != null &&
                secondaryDualPaneCallbacks != null &&
                !saveMode
            ) {
                DualPaneScreen(
                    state = dualPaneState,
                    primaryState = browserState,
                    secondaryState = secondaryBrowserState,
                    primaryCallbacks = primaryDualPaneCallbacks,
                    secondaryCallbacks = secondaryDualPaneCallbacks,
                    onActivate = onActivatePane,
                    onClose = onCloseDualPane,
                    onSync = onSyncDualPane,
                    onSwap = onSwapDualPane,
                    onToggleLock = onTogglePaneLock,
                    onCopyToOther = onCopyToOtherPane,
                    onMoveToOther = onMoveToOtherPane,
                    modifier = Modifier.weight(1f),
                )
            } else BrowserScreen(
                state = browserState,
                onEnterDirectory = onEnterDirectory,
                onBack = onBrowserBack,
                onForward = onBrowserForward,
                onAddCurrentToVirtualView = onAddCurrentToVirtualView,
                onAddEntryToVirtualView = onAddEntryToVirtualView,
                onRebindVirtualReference = if (virtualViewState?.pendingRebind != null) {
                    onConfirmRebindVirtualReference
                } else null,
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
                onEditPreview = if (saveMode) null else onEditPreview,
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
                onOpenDualPane = if (saveMode) null else onOpenDualPane,
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
            is HomeDestination.ExtractionTarget -> if (extractionDestination?.targetBrowser != null) {
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
                    virtualViewState = virtualViewState,
                    onOpenVirtualFolder = onOpenVirtualFolder,
                    onOpenVirtualReference = onOpenVirtualReference,
                    onRetryVirtualReference = onRetryVirtualReference,
                    onAddVirtualReferenceAgain = onAddVirtualReferenceAgain,
                    onRebindVirtualReference = onRebindVirtualReference,
                    onNavigateVirtual = onNavigateVirtual,
                    onCreateVirtualFolder = onCreateVirtualFolder,
                    onRenameVirtualNode = onRenameVirtualNode,
                    onMoveVirtualNode = onMoveVirtualNode,
                    onDeleteVirtualFolder = onDeleteVirtualFolder,
                    onDismissVirtualDelete = onDismissVirtualDelete,
                    onRemoveVirtualReference = onRemoveVirtualReference,
                    onOpenTrash = { trashVisible = true },
                    modifier = Modifier.weight(1f),
                )
            }
            is HomeDestination.MoveTarget -> if (moveDestination?.targetBrowser != null) {
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
                    virtualViewState = virtualViewState,
                    onOpenVirtualFolder = onOpenVirtualFolder,
                    onOpenVirtualReference = onOpenVirtualReference,
                    onRetryVirtualReference = onRetryVirtualReference,
                    onAddVirtualReferenceAgain = onAddVirtualReferenceAgain,
                    onRebindVirtualReference = onRebindVirtualReference,
                    onNavigateVirtual = onNavigateVirtual,
                    onCreateVirtualFolder = onCreateVirtualFolder,
                    onRenameVirtualNode = onRenameVirtualNode,
                    onMoveVirtualNode = onMoveVirtualNode,
                    onDeleteVirtualFolder = onDeleteVirtualFolder,
                    onDismissVirtualDelete = onDismissVirtualDelete,
                    onRemoveVirtualReference = onRemoveVirtualReference,
                    onOpenTrash = { trashVisible = true },
                    modifier = Modifier.weight(1f),
                )
            }
            is HomeDestination.CopyTarget -> if (copyDestination?.targetBrowser != null) {
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
                    virtualViewState = virtualViewState,
                    onOpenVirtualFolder = onOpenVirtualFolder,
                    onOpenVirtualReference = onOpenVirtualReference,
                    onRetryVirtualReference = onRetryVirtualReference,
                    onAddVirtualReferenceAgain = onAddVirtualReferenceAgain,
                    onRebindVirtualReference = onRebindVirtualReference,
                    onNavigateVirtual = onNavigateVirtual,
                    onCreateVirtualFolder = onCreateVirtualFolder,
                    onRenameVirtualNode = onRenameVirtualNode,
                    onMoveVirtualNode = onMoveVirtualNode,
                    onDeleteVirtualFolder = onDeleteVirtualFolder,
                    onDismissVirtualDelete = onDismissVirtualDelete,
                    onRemoveVirtualReference = onRemoveVirtualReference,
                    onOpenTrash = { trashVisible = true },
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
    if (trashVisible) {
        TrashDialog(
            items = browserState.trashItems,
            busy = browserState.deletingEntry,
            onRestore = onRestoreTrashItem,
            onDelete = onDeleteTrashItemPermanently,
            onRestoreAll = onRestoreAllTrashItems,
            onClear = onClearTrash,
            onDismiss = { trashVisible = false },
        )
    }
    virtualViewState?.let { virtualState ->
        if (virtualState.pendingReference != null) {
            VirtualReferencePickerDialog(
                state = virtualState,
                onOpenFolder = onOpenVirtualPickerFolder,
                onCreateFolder = onCreateVirtualPickerFolder,
                onConfirm = onConfirmAddVirtualReference,
                onDismiss = onDismissAddVirtualReference,
            )
        }
        virtualState.message?.let { message ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onClearVirtualMessage,
                text = { androidx.compose.material3.Text(message) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = onClearVirtualMessage) {
                        androidx.compose.material3.Text("关闭")
                    }
                },
            )
        }
    }
    recentState.fileInfo?.let { FileInfoDialog(entry = it, onDismiss = onDismissRecentFileInfo) }
}

private fun LocationHomeUiState.visibleLocationCount(): Int =
    appGroups.sumOf { it.children.size } + commonLocations.size + customLocations.size

private const val VIRTUAL_SAVE_TARGET_REASON =
    "虚拟视图文件夹只用于分组，不能保存文件。请选择一个真实文件夹。"
private const val VIRTUAL_OPERATION_TARGET_REASON =
    "虚拟视图文件夹只用于分组，不能作为文件操作目标。请选择一个真实文件夹。"

internal fun canUseRealTarget(target: HomeDestination.Browser?, browserState: BrowserUiState): Boolean =
    target != null && target.path == browserState.currentPath && browserState.canCreateDirectory

private fun targetDisabledReason(target: HomeDestination.Browser?, browserState: BrowserUiState): String = when {
    target == null -> VIRTUAL_OPERATION_TARGET_REASON
    target.path != browserState.currentPath -> "正在校验目标文件夹。"
    else -> "当前目录不可写。"
}
