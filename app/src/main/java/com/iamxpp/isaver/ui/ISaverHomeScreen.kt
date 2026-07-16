package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.transfer.TransferUiState
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FilesBottomBar
import com.iamxpp.isaver.ui.files.FilesSaveAction
import com.iamxpp.isaver.ui.files.HomeTab
import com.iamxpp.isaver.ui.files.SortSpec
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText

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
    onEnterDirectory: (DirectoryEntry) -> Unit,
    onBrowserBack: () -> Unit,
    onRetryBrowser: () -> Unit,
    onLoadMore: () -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onDisplayModeChange: (DisplayMode) -> Unit = {},
    onSortChange: (SortSpec) -> Unit = {},
    onCreateDirectory: (String) -> Unit = {},
    onToggleSelection: (DirectoryEntry) -> Unit = {},
    onCompress: (String) -> Unit = {},
    onDismissCompressionMessage: () -> Unit = {},
    transferState: TransferUiState = TransferUiState.Idle,
    onSave: () -> Unit = {},
    onStemChange: (String) -> Unit = {},
    onExtensionChange: (String) -> Unit = {},
    onRetryTransfer: () -> Unit = {},
    onAcknowledgeUncertain: () -> Unit = {},
    onContinueQueued: () -> Unit = {},
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

    Column(modifier.fillMaxSize().background(ISaverBackground)) {
        when (homeState.destination) {
            is HomeDestination.Tab -> when (homeState.selectedTab) {
                HomeTab.RECENT -> RecentEmptyScreen(Modifier.weight(1f))
                HomeTab.VIEWS -> LocationHomeScreen(
                    state = locationState,
                    displayMode = displayMode,
                    onOpenLocation = onOpenLocation,
                    onAdd = onAddCustomLocation,
                    onEdit = onEditCustomLocation,
                    onRemove = onRemoveCustomLocation,
                    onRetry = onRetryLocations,
                    onClearAddError = onClearLocationError,
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
                onCompress = onCompress,
                onDismissCompressionMessage = onDismissCompressionMessage,
                saveAction = saveAction,
                modifier = Modifier.weight(1f),
            )
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
}

private fun LocationHomeUiState.visibleLocationCount(): Int =
    appGroups.sumOf { it.children.size } + commonLocations.size + customLocations.size

@Composable
private fun RecentEmptyScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("暂无最近项目", color = ISaverSecondaryText)
    }
}
