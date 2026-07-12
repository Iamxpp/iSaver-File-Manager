package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.locations.StorageLocation
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ISaverHomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun defaultGrantedHomeShowsViewsWithPersistentThreeTabBar() {
        compose.setContent {
            ISaverHomeScreen(
                homeState = ISaverHomeUiState(),
                locationState = LocationHomeUiState(loading = false),
                browserState = BrowserUiState(currentPath = RootPath.parse("/").getOrThrow()),
                displayMode = DisplayMode.LIST,
                onSelectTab = {},
                onOpenLocation = { _, _ -> },
                onAddCustomLocation = { _, _ -> },
                onEditCustomLocation = { _, _, _ -> },
                onRemoveCustomLocation = {},
                onRetryLocations = {},
                onEnterDirectory = {},
                onBrowserBack = {},
                onRetryBrowser = {},
                onLoadMore = {},
            )
        }

        compose.onNode(hasText("视图") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("最近项目").assertIsDisplayed()
        compose.onNodeWithText("浏览").assertIsDisplayed()
        compose.onNodeWithText("应用位置").assertIsDisplayed()
    }

    @Test
    fun bottomTabsSwitchBetweenReliableRecentEmptyViewsAndRootBrowse() {
        var state by mutableStateOf(ISaverHomeUiState())
        val root = RootPath.parse("/").getOrThrow()
        compose.setContent {
            ISaverHomeScreen(
                homeState = state,
                locationState = LocationHomeUiState(loading = false),
                browserState = BrowserUiState(currentPath = root, rootTitle = "浏览", title = "/", loading = false),
                displayMode = DisplayMode.LIST,
                onSelectTab = { tab ->
                    state = state.copy(
                        selectedTab = tab,
                        destination = if (tab == HomeTab.BROWSE) {
                            HomeDestination.Browser(root, "浏览", HomeTab.BROWSE)
                        } else {
                            HomeDestination.Tab(tab)
                        },
                    )
                },
                onOpenLocation = { _, _ -> }, onAddCustomLocation = { _, _ -> },
                onEditCustomLocation = { _, _, _ -> }, onRemoveCustomLocation = {}, onRetryLocations = {},
                onEnterDirectory = {}, onBrowserBack = {}, onRetryBrowser = {}, onLoadMore = {},
            )
        }

        compose.onNode(hasText("最近项目") and hasClickAction()).performClick()
        compose.onNodeWithText("暂无最近项目").assertIsDisplayed()
        compose.onNode(hasText("视图") and hasClickAction()).performClick()
        compose.onNodeWithText("应用位置").assertIsDisplayed()
        compose.onNode(hasText("浏览") and hasClickAction()).performClick()
        compose.onNode(hasText("浏览") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("/").assertIsDisplayed()
        compose.onNodeWithText("最近项目").assertIsDisplayed()
    }

    @Test
    fun customViewClickForwardsUnchangedPathAndRemarkTitle() {
        val path = RootPath.parse("/data/local/tmp/work//").getOrThrow()
        val custom = StorageLocation.Direct(
            id = LocationId.of("custom.work"),
            displayName = "工作资料",
            path = path,
            source = StorageLocation.Source.CUSTOM,
        )
        var opened: Pair<RootPath, String>? = null
        compose.setContent {
            ISaverHomeScreen(
                homeState = ISaverHomeUiState(),
                locationState = LocationHomeUiState(
                    loading = false,
                    customLocations = listOf(CustomLocationState(custom, LocationAvailability.Available(true, true))),
                ),
                browserState = BrowserUiState(currentPath = RootPath.parse("/").getOrThrow()),
                displayMode = DisplayMode.LIST,
                onSelectTab = {}, onOpenLocation = { selectedPath, title -> opened = selectedPath to title },
                onAddCustomLocation = { _, _ -> }, onEditCustomLocation = { _, _, _ -> },
                onRemoveCustomLocation = {}, onRetryLocations = {}, onEnterDirectory = {}, onBrowserBack = {},
                onRetryBrowser = {}, onLoadMore = {},
            )
        }

        compose.onNodeWithContentDescription("列表项：工作资料").performClick()
        compose.runOnIdle { assertEquals(path to "工作资料", opened) }
    }
}
