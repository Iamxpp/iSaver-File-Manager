package com.iamxpp.isaver.ui.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FilesComponentsTest {
    @Test
    fun primaryActionUsesContextLabel() {
        compose.setContent {
            FilesTopBar(
                title = "目标",
                onOverflow = {},
                saveAction = FilesSaveAction(
                    label = "解压到此处",
                    enabled = true,
                    onSave = {},
                ),
            )
        }

        compose.onNodeWithText("解压到此处").assertIsDisplayed()
        compose.onNodeWithText("存储").assertDoesNotExist()
    }

    @Test
    fun disabledPrimaryActionExposesItsReasonToAccessibility() {
        compose.setContent {
            FilesTopBar(
                title = "目标",
                onOverflow = {},
                saveAction = FilesSaveAction(
                    label = "移动到这里",
                    enabled = false,
                    disabledReason = "虚拟视图文件夹不能作为目标。",
                    onSave = {},
                ),
            )
        }

        compose.onNodeWithTag("files-top-bar-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription(
            "移动到这里，不可用：虚拟视图文件夹不能作为目标。",
        ).assertIsDisplayed()
    }

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pageHeaderExposesCompactTitleActionsAndSearch() {
        compose.setContent {
            FilesPageHeader(
                title = "浏览",
                query = "",
                onQueryChange = {},
                onBack = {},
                onOverflow = {},
                topBarTestTag = "page-top-bar",
                searchTestTag = "page-search",
            )
        }

        compose.onNodeWithText("浏览").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多操作").assertIsDisplayed()
        compose.onNodeWithTag("page-top-bar").assertIsDisplayed()
        compose.onNodeWithTag("page-search").assertIsDisplayed()
    }

    @Test
    fun pageHeaderKeepsTopBarBelowStatusBarInset() {
        compose.setContent {
            FilesPageHeader(
                title = "视图",
                query = "",
                onQueryChange = {},
                onOverflow = {},
                topBarTestTag = "inset-top-bar",
                statusBarInsets = WindowInsets(top = 24.dp),
            )
        }

        val barBounds = compose.onNodeWithTag("inset-top-bar")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue("barBounds=$barBounds", barBounds.top > 0f)
    }

    @Test
    fun compactTopBarCentersTitleBesideOverflowWithoutInventingBackAction() {
        var overflowClicks = 0
        compose.setContent {
            FilesTopBar(
                title = "视图",
                onOverflow = { overflowClicks += 1 },
            )
        }

        val barBounds = compose.onNodeWithTag("files-top-bar").fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithTag("files-top-bar-title")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val overflowBounds = compose.onNodeWithTag("files-top-bar-overflow")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        assertEquals(barBounds.center.x, titleBounds.center.x, 1f)
        assertTrue(titleBounds.top < overflowBounds.bottom && overflowBounds.top < titleBounds.bottom)
        val overflowConfig = compose.onNodeWithTag("files-top-bar-overflow")
            .fetchSemanticsNode()
            .config
        assertTrue(overflowConfig.contains(SemanticsProperties.Role))
        assertEquals(Role.Button, overflowConfig[SemanticsProperties.Role])
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.runOnIdle { assertEquals(1, overflowClicks) }
    }

    @Test
    fun saveActionReplacesOverflowAndForwardsClick() {
        var saved = false
        compose.setContent {
            FilesTopBar(
                title = "视图",
                onOverflow = {},
                saveAction = FilesSaveAction(
                    enabled = true,
                    onSave = { saved = true },
                ),
            )
        }

        compose.onNodeWithTag("files-top-bar-save").assertIsEnabled().performClick()
        compose.onNodeWithTag("files-top-bar-overflow").assertDoesNotExist()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test
    fun bottomBarExposesThreeTabsAndSelectedState() {
        compose.setContent {
            FilesBottomBar(
                selectedTab = HomeTab.VIEWS,
                onSelect = {},
            )
        }

        compose.onNodeWithText("最近项目").assertIsDisplayed()
        compose.onNodeWithText("视图").assertIsSelected()
        compose.onNodeWithText("浏览").assertIsDisplayed()
    }

    @Test
    fun searchFieldExposesSearchSemanticsAndForwardsInput() {
        var changedTo = ""
        compose.setContent {
            FilesSearchField(
                query = "",
                onQueryChange = { changedTo = it },
            )
        }

        compose.onNodeWithContentDescription("搜索文件")
            .performTextInput("微信")
        assertEquals("微信", changedTo)
    }

    @Test
    fun listAndGridItemsExposeDisplayNameMetadataAndClickSemantics() {
        val directory = directoryEntry()
        var listClicked = false
        var gridClicked = false
        compose.setContent {
            FileListRow(
                entry = directory,
                displayName = "工作",
                metadata = "2026/7/13 - 2项",
                onClick = { listClicked = true },
            )
            FileGridCell(
                entry = directory,
                displayName = "工作网格",
                metadata = "2项",
                onClick = { gridClicked = true },
            )
        }

        compose.onNodeWithContentDescription("列表项：工作").performClick()
        compose.onNodeWithText("2026/7/13 - 2项").assertIsDisplayed()
        compose.onNodeWithContentDescription("网格项：工作网格").performClick()
        compose.onNodeWithText("2项").assertIsDisplayed()
        assertTrue(listClicked)
        assertTrue(gridClicked)
    }

    @Test
    fun listRowAppliesCallerModifierToExactlyOneClickableNode() {
        var clicks = 0
        compose.setContent {
            FileListRow(
                entry = directoryEntry(),
                displayName = "工作",
                metadata = "2项",
                onClick = { clicks += 1 },
                modifier = androidx.compose.ui.Modifier.testTag("files-list-row"),
            )
        }

        compose.onAllNodesWithTag("files-list-row").assertCountEquals(1)
        compose.onNodeWithTag("files-list-row").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun filesGridExposesFixedThreeColumnCollectionAndMultipleCells() {
        val entries = (1..4).map { index -> directoryEntry("目录$index") }
        compose.setContent {
            FilesGrid(
                items = entries,
                key = { it.name },
                modifier = androidx.compose.ui.Modifier.testTag("files-grid"),
            ) { entry ->
                FileGridCell(
                    entry = entry,
                    displayName = entry.name,
                    metadata = "2项",
                    onClick = {},
                )
            }
        }

        compose.onNodeWithTag("files-grid").assertIsDisplayed()
        val bounds = entries.map { entry ->
            compose.onNodeWithContentDescription("网格项：${entry.name}")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        }
        assertEquals(bounds[0].top, bounds[1].top, 1f)
        assertEquals(bounds[0].top, bounds[2].top, 1f)
        assertTrue(bounds[0].left < bounds[1].left)
        assertTrue(bounds[1].left < bounds[2].left)
        assertTrue(bounds[3].top > bounds[0].top)
        assertEquals(bounds[0].left, bounds[3].left, 1f)
    }

    @Test
    fun filesGridPublishesExactlyOneCollectionInUnmergedTree() {
        val entries = (1..4).map { index -> directoryEntry("目录$index") }
        compose.setContent {
            FilesGrid(
                items = entries,
                key = { it.name },
            ) { entry ->
                FileGridCell(
                    entry = entry,
                    displayName = entry.name,
                    metadata = "2项",
                    onClick = {},
                )
            }
        }

        val collectionNodes = compose.onAllNodes(
            matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val dimensions = collectionNodes.map { node ->
            node.config[SemanticsProperties.CollectionInfo].let { info ->
                "${info.rowCount}x${info.columnCount}"
            }
        }
        assertEquals("collectionInfos=$dimensions", 1, collectionNodes.size)
    }

    @Test
    fun overflowMenuExposesSelectedPresentationAndCapabilityStates() {
        var selectedField: SortField? = null
        compose.setContent {
            FilesOverflowMenu(
                expanded = true,
                displayMode = DisplayMode.GRID,
                sortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
                onDismissRequest = {},
                onDisplayModeChange = {},
                onSortFieldChange = { selectedField = it },
                onSortDirectionToggle = {},
                onCreateFolder = {},
                onCompress = {},
                onConnectServer = {},
                canCreateFolder = true,
                canCompress = false,
                canConnectServer = false,
            )
        }

        compose.onNodeWithText("图标").assertIsSelected()
        compose.onNodeWithText("名称").assertIsSelected()
        compose.onNodeWithText("新建文件夹").assertIsEnabled()
        compose.onNodeWithText("压缩文件").assertIsNotEnabled()
        compose.onAllNodesWithText("连接服务器").assertCountEquals(0)
        val commandConfig = compose.onNodeWithText("新建文件夹")
            .fetchSemanticsNode()
            .config
        assertFalse(commandConfig.contains(SemanticsProperties.Selected))
        val unselectedModeConfig = compose.onNodeWithText("列表")
            .fetchSemanticsNode()
            .config
        assertTrue(unselectedModeConfig.contains(SemanticsProperties.Selected))
        assertEquals(false, unselectedModeConfig[SemanticsProperties.Selected])
        compose.onNodeWithText("日期").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(SortField.MODIFIED_AT, selectedField)
        }
    }

    private fun directoryEntry(name: String = "work") = DirectoryEntry(
        path = RootPath.parse("/data/local/tmp/$name").getOrThrow(),
        name = name,
        type = EntryType.DIRECTORY,
        sizeBytes = null,
        modifiedAtEpochSeconds = 1_720_800_000,
        readable = true,
        writable = true,
        symbolicLink = false,
    )
}
