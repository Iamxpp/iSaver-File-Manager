package com.iamxpp.isaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FilesBottomBar
import com.iamxpp.isaver.ui.files.HomeTab
import com.iamxpp.isaver.ui.files.SortSpec
import com.iamxpp.isaver.ui.theme.ISaverBackground
import com.iamxpp.isaver.ui.theme.ISaverSecondaryText

@Composable
fun ISaverHomeScreen(
    homeState: ISaverHomeUiState,
    locationState: LocationHomeUiState,
    browserState: BrowserUiState,
    displayMode: DisplayMode,
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
    modifier: Modifier = Modifier,
) {
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
                modifier = Modifier.weight(1f),
            )
        }
        FilesBottomBar(
            selectedTab = homeState.selectedTab,
            onSelect = onSelectTab,
        )
    }
}

@Composable
private fun RecentEmptyScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("暂无最近项目", color = ISaverSecondaryText)
    }
}
