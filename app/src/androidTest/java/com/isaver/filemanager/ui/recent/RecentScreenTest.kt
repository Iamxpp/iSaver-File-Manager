package com.isaver.filemanager.ui.recent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.recent.RecentActivity
import com.isaver.filemanager.recent.RecentItem
import com.isaver.filemanager.recent.RecentItemType
import com.isaver.filemanager.ui.files.DisplayMode
import org.junit.Rule
import org.junit.Test

class RecentScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyDatabaseShowsRecentEmptyState() {
        compose.setContent {
            RecentScreen(
                state = RecentUiState(),
                displayMode = DisplayMode.LIST,
                onOpen = {},
                onRefresh = {},
            )
        }

        compose.onNodeWithText("暂无最近项目").assertIsDisplayed()
    }

    @Test
    fun compressedAndUnavailableItemsShowActivityAndStatus() {
        compose.setContent {
            RecentScreen(
                state = RecentUiState(
                    items = listOf(
                        available("/archive.zip", "archive.zip", RecentActivity.COMPRESSED),
                        unavailable("/gone.pdf", "gone.pdf"),
                    ),
                ),
                displayMode = DisplayMode.LIST,
                onOpen = {},
                onRefresh = {},
            )
        }

        compose.onNodeWithText("archive.zip").assertIsDisplayed()
        compose.onNodeWithText("已压缩").assertIsDisplayed()
        compose.onNodeWithText("项目不可用").assertIsDisplayed()
    }

    @Test
    fun resolvedRowsDoNotShowARefreshingFooter() {
        compose.setContent {
            RecentScreen(
                state = RecentUiState(
                    items = listOf(available("/archive.zip", "archive.zip", RecentActivity.COMPRESSED)),
                    refreshing = true,
                ),
                displayMode = DisplayMode.LIST,
                onOpen = {},
                onRefresh = {},
            )
        }

        compose.onNodeWithText("archive.zip").assertIsDisplayed()
        compose.onAllNodesWithText("正在检查最近项目…").assertCountEquals(0)
    }

    private fun available(path: String, name: String, activity: RecentActivity): RecentUiItem {
        val rootPath = root(path)
        return RecentUiItem(
            item = RecentItem(rootPath, name, null, RecentItemType.ARCHIVE, activity, 10L, true),
            availability = RecentAvailability.Available(
                DirectoryEntry(rootPath, name, EntryType.FILE, 12L, 1L, true, false, false),
            ),
        )
    }

    private fun unavailable(path: String, name: String): RecentUiItem {
        val rootPath = root(path)
        return RecentUiItem(
            item = RecentItem(rootPath, name, null, RecentItemType.FILE, RecentActivity.ACCESSED, 9L, false),
            availability = RecentAvailability.Unavailable("路径不存在"),
        )
    }

    private fun root(value: String) = RootPath.parse(value).getOrThrow()
}
