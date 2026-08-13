package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.locations.StorageLocation
import com.iamxpp.isaver.transfer.OutputNameDraft
import com.iamxpp.isaver.transfer.ShareSummary
import com.iamxpp.isaver.transfer.TransferUiState
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.HomeTab
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.files.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        compose.onNodeWithText("通用位置").assertIsDisplayed()
        compose.onNodeWithText("应用位置").assertDoesNotExist()
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
        compose.onNodeWithText("通用位置").assertIsDisplayed()
        compose.onNodeWithText("应用位置").assertDoesNotExist()
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

    @Test
    fun viewsOverflowForwardsSharedPresentationPreferences() {
        val currentSort = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING)
        var mode: DisplayMode? = null
        var sort: SortSpec? = null
        compose.setContent {
            ISaverHomeScreen(
                homeState = ISaverHomeUiState(),
                locationState = LocationHomeUiState(loading = false),
                browserState = BrowserUiState(currentPath = RootPath.parse("/").getOrThrow()),
                displayMode = DisplayMode.LIST,
                sortSpec = currentSort,
                onSelectTab = {}, onOpenLocation = { _, _ -> }, onAddCustomLocation = { _, _ -> },
                onEditCustomLocation = { _, _, _ -> }, onRemoveCustomLocation = {}, onRetryLocations = {},
                onEnterDirectory = {}, onBrowserBack = {}, onRetryBrowser = {}, onLoadMore = {},
                onDisplayModeChange = { mode = it },
                onSortChange = { sort = it },
            )
        }

        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithText("图标").performClick()
        compose.runOnIdle { assertEquals(DisplayMode.GRID, mode) }
        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithText("大小").performClick()
        compose.runOnIdle { assertEquals(currentSort.copy(field = SortField.SIZE), sort) }
    }

    @Test
    fun saveModeKeepsViewsLocationsTabsAndVisibleDefaultName() {
        val path = RootPath.parse("/data/local/tmp/work").getOrThrow()
        val custom = StorageLocation.Direct(
            id = LocationId.of("custom.work"),
            displayName = "工作资料",
            path = path,
            source = StorageLocation.Source.CUSTOM,
        )
        compose.setContent {
            ISaverHomeScreen(
                homeState = ISaverHomeUiState(),
                locationState = LocationHomeUiState(
                    loading = false,
                    customLocations = listOf(
                        CustomLocationState(custom, LocationAvailability.Available(true, true)),
                    ),
                ),
                browserState = BrowserUiState(currentPath = RootPath.parse("/").getOrThrow()),
                displayMode = DisplayMode.LIST,
                transferState = choosing(canSave = false, targetDirectory = null),
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

        compose.onNodeWithText("最近项目").assertIsDisplayed()
        compose.onNode(hasText("视图") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("浏览").assertIsDisplayed()
        compose.onNodeWithContentDescription("列表项：工作资料").assertIsDisplayed()
        compose.onNodeWithTag("inline-save-bar").assertIsDisplayed()
        compose.onNodeWithTag("inline-save-stem").assertTextEquals("测试 报告")
        compose.onNodeWithTag("inline-save-extension").assertTextEquals("pdf")
        compose.onNodeWithTag("files-top-bar-save").assertIsNotEnabled()
        compose.onNodeWithTag("files-top-bar-overflow").assertDoesNotExist()
    }

    @Test
    fun realDirectoryEnablesSaveAndPlacesBarImmediatelyAboveTabs() {
        val path = RootPath.parse("/data/local/tmp/work").getOrThrow()
        compose.setContent {
            ISaverHomeScreen(
                homeState = ISaverHomeUiState(
                    selectedTab = HomeTab.VIEWS,
                    destination = HomeDestination.Browser(path, "工作资料", HomeTab.VIEWS),
                ),
                locationState = LocationHomeUiState(loading = false),
                browserState = BrowserUiState(
                    currentPath = path,
                    rootTitle = "工作资料",
                    title = "work",
                    totalCount = 3,
                ),
                displayMode = DisplayMode.LIST,
                transferState = choosing(canSave = true, targetDirectory = path),
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

        compose.onNodeWithTag("files-top-bar-save").assertIsEnabled()
        val saveBar = compose.onNodeWithTag("inline-save-bar").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("files-bottom-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(saveBar.bottom, tabs.top, 1f)
        assertTrue(saveBar.height < tabs.height * 1.5f)
    }

    @Test
    fun saveStaysDisabledWhileDestinationBrowserStillShowsPreviousDirectory() {
        val selected = RootPath.parse("/data/local/tmp/selected").getOrThrow()
        val previous = RootPath.parse("/data/local/tmp/previous").getOrThrow()
        compose.setContent {
            ISaverHomeScreen(
                homeState = ISaverHomeUiState(
                    selectedTab = HomeTab.VIEWS,
                    destination = HomeDestination.Browser(selected, "目标", HomeTab.VIEWS),
                ),
                locationState = LocationHomeUiState(loading = false),
                browserState = BrowserUiState(
                    currentPath = previous,
                    rootTitle = "旧目标",
                    canCreateDirectory = true,
                ),
                displayMode = DisplayMode.LIST,
                transferState = choosing(canSave = true, targetDirectory = previous),
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

        compose.onNodeWithTag("files-top-bar-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("存储，不可用：正在校验目标文件夹。").assertIsDisplayed()
    }

    @Test
    fun copyTargetOpenedFromViewsRendersRealBrowserAndEnablesTargetAction() {
        val source = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val target = RootPath.parse("/data/local/tmp/target").getOrThrow()
        compose.setContent {
            ISaverHomeScreen(
                homeState = ISaverHomeUiState(
                    selectedTab = HomeTab.VIEWS,
                    destination = HomeDestination.CopyTarget(
                        sourceBrowser = HomeDestination.Browser(source, "来源", HomeTab.BROWSE),
                        targetBrowser = HomeDestination.Browser(target, "目标", HomeTab.VIEWS),
                    ),
                ),
                locationState = LocationHomeUiState(loading = false),
                browserState = BrowserUiState(
                    currentPath = target,
                    rootTitle = "目标",
                    title = "target",
                    canCreateDirectory = true,
                ),
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

        compose.onNodeWithText("target").assertIsDisplayed()
        compose.onNodeWithText("虚拟视图位置").assertDoesNotExist()
        compose.onNodeWithText("复制到这里").assertIsEnabled()
    }

    private fun choosing(
        canSave: Boolean,
        targetDirectory: RootPath?,
    ) = TransferUiState.Choosing(
        share = ShareSummary("测试 报告.pdf", 37L, "application/pdf"),
        outputName = OutputNameDraft("测试 报告", "pdf"),
        cachedBytes = 37L,
        targetDirectory = targetDirectory,
        canSave = canSave,
    )
}
