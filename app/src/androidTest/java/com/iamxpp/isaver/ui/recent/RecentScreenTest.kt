package com.iamxpp.isaver.ui.recent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.recent.RecentActivity
import com.iamxpp.isaver.recent.RecentItem
import com.iamxpp.isaver.recent.RecentItemType
import com.iamxpp.isaver.ui.files.DisplayMode
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
