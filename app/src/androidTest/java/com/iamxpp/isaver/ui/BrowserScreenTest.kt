package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.files.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowserScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun titleBackSearchAndPresentationCallbacksAreWired() {
        var backed = false
        var query by mutableStateOf("")
        var mode: DisplayMode? = null
        var sort: SortSpec? = null
        compose.setContent {
            BrowserScreen(
                state = state(rootTitle = "微信文件", searchQuery = query),
                onEnterDirectory = {}, onBack = { backed = true }, onRetry = {}, onLoadMore = {},
                onSearchQueryChange = { query = it },
                onDisplayModeChange = { mode = it },
                onSortChange = { sort = it },
            )
        }

        compose.onNodeWithText("微信文件").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("搜索文件").performTextInput("报告")
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("图标").performClick()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("日期").performClick()

        assertTrue(backed)
        assertEquals("报告", query)
        assertEquals(DisplayMode.GRID, mode)
        assertEquals(SortSpec(SortField.MODIFIED_AT, SortDirection.ASCENDING), sort)
    }

    @Test fun createFolderCapabilityDialogAndCallbackAreWired() {
        var created: String? = null
        compose.setContent {
            BrowserScreen(
                state = state(canCreateDirectory = true),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onCreateDirectory = { created = it },
            )
        }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("新建文件夹").assertIsEnabled().performClick()
        compose.onNodeWithText("未命名文件夹").performTextInput("测试目录")
        compose.onNodeWithText("确定").performClick()
        assertEquals("测试目录", created)
    }

    @Test fun createFolderIsDisabledForReadOnlyOrBusyDirectory() {
        compose.setContent {
            BrowserScreen(
                state = state(canCreateDirectory = false, creatingDirectory = true),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
            )
        }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("新建文件夹").assertIsNotEnabled()
    }

    @Test fun listModeAndDeferredMenuActionsAreExplained() {
        val file = entry("说明.txt", EntryType.FILE)
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(file), allEntries = listOf(file)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
            )
        }
        compose.onNodeWithContentDescription("列表项：说明.txt").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("压缩文件").performClick()
        compose.onNodeWithText("压缩文件将在后续阶段提供").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("连接服务器").performClick()
        compose.onNodeWithText("连接服务器将在后续阶段提供").assertIsDisplayed()
    }

    @Test fun listGridEntriesProgressAndLocationTargetAreVisible() {
        val directory = entry("资料", EntryType.DIRECTORY)
        val file = entry("报告.pdf", EntryType.FILE)
        compose.setContent {
            BrowserScreen(
                state = state(
                    displayMode = DisplayMode.GRID,
                    entries = listOf(directory, file),
                    allEntries = listOf(directory, file),
                    creatingDirectory = true,
                    locationTarget = directory.path,
                ),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
            )
        }
        compose.onNodeWithText("资料").assertIsDisplayed()
        compose.onNodeWithContentDescription("网格项：报告.pdf").assertIsDisplayed()
        compose.onNodeWithText("正在新建文件夹").assertIsDisplayed()
        compose.onNodeWithContentDescription("新建文件夹定位目标").assertIsDisplayed()
    }

    @Test fun structuredErrorsAreVisibleAndDismissCallbacksAreWired() {
        var dismissedCreate = false
        var dismissedPresentation = false
        var currentState by mutableStateOf(
            state(createDirectoryError = BrowserOperationError(ErrorCode.ALREADY_EXISTS, "文件夹已存在")),
        )
        compose.setContent {
            BrowserScreen(
                state = currentState,
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onDismissCreateError = {
                    dismissedCreate = true
                    currentState = currentState.copy(createDirectoryError = null, presentationError = "无法保存显示设置")
                },
                onDismissPresentationError = { dismissedPresentation = true },
            )
        }
        compose.onNodeWithText("文件夹已存在").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("无法保存显示设置").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        assertTrue(dismissedCreate)
        assertTrue(dismissedPresentation)
    }

    @Test fun loadingEmptyRetryLoadMoreAndInitialBackStatesRemainWired() {
        var retried = false
        var loadedMore = false
        var currentState by mutableStateOf(state(loading = true, canGoBack = false))
        compose.setContent {
            BrowserScreen(
                state = currentState,
                onEnterDirectory = {}, onBack = {},
                onRetry = { retried = true }, onLoadMore = { loadedMore = true },
            )
        }
        compose.onNodeWithText("正在读取目录").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        currentState = state(errorMessage = "目录不可读")
        compose.onNodeWithText("重试").performClick()
        val file = entry("分页.txt", EntryType.FILE)
        currentState = state(entries = listOf(file), allEntries = listOf(file), hasMore = true)
        compose.onNodeWithText("加载更多").performClick()
        currentState = state()
        compose.onNodeWithText("此目录为空").assertIsDisplayed()
        assertTrue(retried)
        assertTrue(loadedMore)
    }

    private fun state(
        rootTitle: String = "根目录",
        entries: List<DirectoryEntry> = emptyList(),
        allEntries: List<DirectoryEntry> = entries,
        displayMode: DisplayMode = DisplayMode.LIST,
        canCreateDirectory: Boolean = false,
        creatingDirectory: Boolean = false,
        createDirectoryError: BrowserOperationError? = null,
        presentationError: String? = null,
        locationTarget: RootPath? = null,
        searchQuery: String = "",
        loading: Boolean = false,
        errorMessage: String? = null,
        canGoBack: Boolean = true,
        hasMore: Boolean = false,
    ) = BrowserUiState(
        currentPath = RootPath.parse("/").getOrThrow(), rootTitle = rootTitle,
        entries = entries, allEntries = allEntries, totalCount = entries.size,
        loading = loading, errorMessage = errorMessage, canGoBack = canGoBack,
        hasMore = hasMore, displayMode = displayMode,
        canCreateDirectory = canCreateDirectory, creatingDirectory = creatingDirectory,
        createDirectoryError = createDirectoryError, presentationError = presentationError,
        locationTarget = locationTarget, searchQuery = searchQuery,
    )

    private fun entry(name: String, type: EntryType) = DirectoryEntry(
        path = RootPath.parse("/$name").getOrThrow(), name = name, type = type,
        sizeBytes = 1024, modifiedAtEpochSeconds = 1_700_000_000,
        readable = true, writable = true, symbolicLink = false,
    )
}
