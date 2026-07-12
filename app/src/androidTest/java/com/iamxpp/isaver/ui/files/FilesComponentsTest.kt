package com.iamxpp.isaver.ui.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FilesComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun largeTitleHeaderExposesTitleAndActions() {
        compose.setContent {
            FilesLargeTitleHeader(
                title = "浏览",
                onBack = {},
                onOverflow = {},
            )
        }

        compose.onNodeWithText("浏览").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多操作").assertIsDisplayed()
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

        val collectionInfo = compose.onNodeWithTag("files-grid")
            .fetchSemanticsNode()
            .config[SemanticsProperties.CollectionInfo]
        assertEquals(2, collectionInfo.rowCount)
        assertEquals(3, collectionInfo.columnCount)
        entries.forEach { entry ->
            compose.onNodeWithContentDescription("网格项：${entry.name}").assertIsDisplayed()
        }
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
        compose.onNodeWithText("连接服务器").assertIsNotEnabled()
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
