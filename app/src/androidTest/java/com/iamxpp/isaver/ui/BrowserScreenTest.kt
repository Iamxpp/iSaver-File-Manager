package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowserScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingStateIsVisible() {
        compose.setContent { BrowserScreen(state(loading = true), {}, {}, {}, {}) }
        compose.onNodeWithText("正在读取目录").assertIsDisplayed()
    }

    @Test fun emptyStateIsVisible() {
        compose.setContent { BrowserScreen(state(loading = false), {}, {}, {}, {}) }
        compose.onNodeWithText("此目录为空").assertIsDisplayed()
    }

    @Test fun errorStateRetries() {
        var retried = false
        compose.setContent { BrowserScreen(state(loading = false, errorMessage = "目录不可读"), {}, {}, { retried = true }, {}) }
        compose.onNodeWithText("目录不可读").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        assertTrue(retried)
    }

    @Test fun directoryClickLoadMoreAndBackAreWired() {
        val directory = entry("目录\n名称", EntryType.DIRECTORY)
        var entered = false; var more = false; var backed = false
        compose.setContent {
            BrowserScreen(
                state(entries = listOf(directory), allEntries = listOf(directory), totalCount = 2, hasMore = true, canGoBack = true, loading = false),
                onEnterDirectory = { entered = true }, onBack = { backed = true }, onRetry = {}, onLoadMore = { more = true },
            )
        }
        compose.onNodeWithText("目录\n名称").performClick(); assertTrue(entered)
        compose.onNodeWithText("加载更多").performClick(); assertTrue(more)
        compose.onNodeWithContentDescription("返回上一级").performClick(); assertTrue(backed)
    }

    @Test fun back_is_hidden_at_initial_location() {
        compose.setContent { BrowserScreen(state(loading = false), {}, {}, {}, {}) }
        compose.onNodeWithContentDescription("返回上一级").assertDoesNotExist()
    }

    private fun state(
        entries: List<DirectoryEntry> = emptyList(), allEntries: List<DirectoryEntry> = entries,
        totalCount: Int = entries.size, loading: Boolean = false, errorMessage: String? = null,
        canGoBack: Boolean = false, hasMore: Boolean = false,
    ) = BrowserUiState(
        currentPath = RootPath.parse("/storage/emulated/0").getOrThrow(),
        allEntries = allEntries,
        entries = entries,
        totalCount = totalCount,
        loading = loading,
        errorMessage = errorMessage,
        canGoBack = canGoBack,
        hasMore = hasMore,
    )

    private fun entry(name: String, type: EntryType) = DirectoryEntry(RootPath.parse("/x/$name").getOrThrow(), name, type, 12, 2, true, false, false)
}
