package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.remote.RemoteProtocol
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FilesSaveAction
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.files.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowserScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longPressSelectsReadableDirectoryForCompression() {
        val directory = entry("folder", EntryType.DIRECTORY)
        var selected: DirectoryEntry? = null
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(directory)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onSelectEntry = { selected = it },
            )
        }

        compose.onNodeWithContentDescription("列表项：folder").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(directory, selected) }
    }

    @Test
    fun longPressFileOpensActionSheetAndForwardsShare() {
        val file = entry("report.pdf", EntryType.FILE)
        var selected: DirectoryEntry? = null
        var shared: DirectoryEntry? = null
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(file)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onSelectEntry = { selected = it },
                onShareEntry = { shared = it },
            )
        }

        compose.onNodeWithContentDescription("列表项：report.pdf").performTouchInput { longClick() }
        compose.onNodeWithText("文件操作").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertEquals(file, selected)
            assertEquals(file, shared)
        }
    }

    @Test
    fun multipleSelectionForwardsShareFromSelectionBar() {
        val first = entry("first.txt", EntryType.FILE)
        val second = entry("second.pdf", EntryType.FILE)
        var shared = false
        compose.setContent {
            BrowserScreen(
                state = state(
                    entries = listOf(first, second),
                    allEntries = listOf(first, second),
                    selectedEntries = setOf(first, second),
                ),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onShareSelection = { shared = true },
            )
        }

        compose.onNodeWithText("已选择 2 项").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed().performClick()

        compose.runOnIdle { assertTrue(shared) }
    }

    @Test
    fun multipleSelectionForwardsMoveAndCopyFromSelectionBar() {
        val first = entry("first.txt", EntryType.FILE)
        val second = entry("second.pdf", EntryType.FILE)
        var moved = false
        var copied = false
        compose.setContent {
            BrowserScreen(
                state = state(
                    entries = listOf(first, second),
                    allEntries = listOf(first, second),
                    selectedEntries = setOf(first, second),
                ),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onMoveSelection = { moved = true },
                onCopySelection = { copied = true },
            )
        }

        compose.onNodeWithText("移动到").performClick()
        compose.onNodeWithText("复制到").performClick()

        compose.runOnIdle {
            assertTrue(moved)
            assertTrue(copied)
        }
    }

    @Test
    fun longPressFileForwardsMoveFromActionSheet() {
        val file = entry("report.pdf", EntryType.FILE)
        var moved: DirectoryEntry? = null
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(file)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onMoveEntry = { moved = it },
            )
        }

        compose.onNodeWithContentDescription("列表项：report.pdf").performTouchInput { longClick() }
        compose.onNodeWithText("移动到").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(file, moved) }
    }

    @Test
    fun longPressFileForwardsCopyFromActionSheet() {
        val file = entry("report.pdf", EntryType.FILE)
        var copied: DirectoryEntry? = null
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(file)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onCopyEntry = { copied = it },
            )
        }

        compose.onNodeWithContentDescription("列表项：report.pdf").performTouchInput { longClick() }
        compose.onNodeWithText("复制到").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(file, copied) }
    }

    @Test
    fun longPressFileForwardsRenameFromActionSheet() {
        val file = entry("report.pdf", EntryType.FILE)
        var renamed: Pair<DirectoryEntry, String>? = null
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(file)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onRenameEntry = { entry, name -> renamed = entry to name },
            )
        }

        compose.onNodeWithContentDescription("列表项：report.pdf").performTouchInput { longClick() }
        compose.onNodeWithText("重命名").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("新文件名").performTextReplacement("renamed.pdf")
        compose.onNodeWithText("确定").performClick()

        compose.runOnIdle { assertEquals(file to "renamed.pdf", renamed) }
    }

    @Test
    fun saveModeReplacesBrowserOverflowAndForwardsClick() {
        var saved = false
        compose.setContent {
            BrowserScreen(
                state = state(title = "Download"),
                onEnterDirectory = {},
                onBack = {},
                onRetry = {},
                onLoadMore = {},
                saveAction = FilesSaveAction(enabled = true, onSave = { saved = true }),
            )
        }

        compose.onNodeWithTag("files-top-bar-save").assertIsEnabled().performClick()
        compose.onNodeWithTag("files-top-bar-overflow").assertDoesNotExist()
        assertTrue(saved)
    }

    @Test fun rootLevelUsesCompactHeaderWithSearchImmediatelyBelow() {
        compose.setContent {
            BrowserScreen(
                state = state(title = "/", canGoBack = false),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
            )
        }

        val topBar = compose.onNodeWithTag("browser-top-bar")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val title = compose.onNodeWithTag("files-top-bar-title")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val overflow = compose.onNodeWithTag("files-top-bar-overflow")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val search = compose.onNodeWithTag("browser-search")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val searchField = compose.onNodeWithContentDescription("搜索文件")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        assertEquals(topBar.center.x, title.center.x, 1f)
        assertTrue(title.top < overflow.bottom && overflow.top < title.bottom)
        assertEquals(topBar.bottom, search.top, 1f)
        assertEquals(topBar.bottom, searchField.top, 1f)
        assertEquals(search.top, searchField.top, 1f)
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
    }

    @Test fun nestedLevelAlignsBackLongTitleAndOverflowOnOneLine() {
        compose.setContent {
            BrowserScreen(
                state = state(
                    title = "Android/data/example.app/files/Download/非常长的目录名称",
                    canGoBack = true,
                ),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
            )
        }

        val topBar = compose.onNodeWithTag("browser-top-bar")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val back = compose.onNodeWithContentDescription("返回")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val title = compose.onNodeWithTag("files-top-bar-title")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val overflow = compose.onNodeWithTag("files-top-bar-overflow")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val search = compose.onNodeWithTag("browser-search")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        assertEquals(topBar.center.x, title.center.x, 1f)
        assertTrue(title.top < back.bottom && back.top < title.bottom)
        assertTrue(title.top < overflow.bottom && overflow.top < title.bottom)
        assertTrue(title.height < overflow.height)
        assertTrue(title.left >= back.right)
        assertTrue(title.right <= overflow.left)
        assertEquals(topBar.bottom, search.top, 1f)
    }

    @Test fun overflowMenuIsAnchoredToTheRightActionSlot() {
        compose.setContent {
            BrowserScreen(
                state = state(title = "cache", canGoBack = true),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
            )
        }

        val topBar = compose.onNodeWithTag("browser-top-bar")
            .fetchSemanticsNode()
            .boundsOnScreen()
        val overflow = compose.onNodeWithTag("files-top-bar-overflow")
            .fetchSemanticsNode()
            .boundsOnScreen()
        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        val firstCommand = compose.onNodeWithText("新建文件夹")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsOnScreen()

        assertTrue("command=$firstCommand topBar=$topBar", firstCommand.center.x > topBar.center.x)
        assertEquals(overflow.right, firstCommand.right, 1f)
        assertTrue("command=$firstCommand overflow=$overflow", firstCommand.top - overflow.bottom < firstCommand.height)
    }

    @Test fun titleBackSearchAndPresentationCallbacksAreWired() {
        var backed = false
        var query by mutableStateOf("")
        var mode: DisplayMode? = null
        var sort: SortSpec? = null
        compose.setContent {
            BrowserScreen(
                state = state(rootTitle = "应用文件", searchQuery = query),
                onEnterDirectory = {}, onBack = { backed = true }, onRetry = {}, onLoadMore = {},
                onSearchQueryChange = { query = it },
                onDisplayModeChange = { mode = it },
                onSortChange = { sort = it },
            )
        }

        compose.onNodeWithText("应用文件").assertIsDisplayed()
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

    @Test fun visibleTitleUsesNavigationTitleInsteadOfLocationRemark() {
        compose.setContent {
            BrowserScreen(
                state = state(rootTitle = "浏览", title = "/"),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
            )
        }
        compose.onNodeWithText("/").assertIsDisplayed()
        compose.onNodeWithText("浏览").assertDoesNotExist()
    }

    @Test fun everySortFieldDirectionAndFileMetadataAreWired() {
        var currentSort by mutableStateOf(SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING))
        val selected = mutableListOf<SortSpec>()
        val file = entry("报告.pdf", EntryType.FILE)
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(file), allEntries = listOf(file), sortSpec = currentSort),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onSortChange = { currentSort = it; selected += it },
            )
        }
        listOf(
            "名称" to SortField.DISPLAY_NAME,
            "种类" to SortField.TYPE,
            "日期" to SortField.MODIFIED_AT,
            "大小" to SortField.SIZE,
        ).forEach { (label, field) ->
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText(label).performClick()
            assertEquals(field, selected.last().field)
        }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("升序").performClick()
        assertEquals(SortDirection.DESCENDING, selected.last().direction)
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("降序").performClick()
        assertEquals(SortDirection.ASCENDING, selected.last().direction)
        compose.onNodeWithText("1 KB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2023", substring = true).assertIsDisplayed()
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
        compose.onNodeWithText("压缩文件").assertIsNotEnabled()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onAllNodesWithText("连接服务器").assertCountEquals(0)
    }

    @Test fun selectedFileEnablesZipDialogAndReturnsExplicitName() {
        val file = entry("说明.txt", EntryType.FILE)
        var output: String? = null
        compose.setContent {
            BrowserScreen(
                state = state(
                    entries = listOf(file),
                    allEntries = listOf(file),
                    selectedEntries = setOf(file),
                ),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onCompress = { output = it },
            )
        }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("压缩文件").assertIsEnabled().performClick()
        compose.onNodeWithText("压缩文件名称").assertIsDisplayed()
        compose.onNodeWithText("archive.zip").assertIsDisplayed()
        compose.onNodeWithText("确定").performClick()
        assertEquals("archive.zip", output)
    }

    @Test fun serverMenuOpensSecureSftpDraftDialog() {
        var draft: com.iamxpp.isaver.remote.RemoteConnectionDraft? = null
        compose.setContent {
            BrowserScreen(
                state = state(),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onConnectServer = { draft = it },
            )
        }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("连接服务器").performClick()
        compose.onNodeWithText("SFTP").assertIsDisplayed()
        compose.onNodeWithContentDescription("服务器地址").performTextInput("example.test")
        compose.onNodeWithContentDescription("用户名").performTextInput("user")
        compose.onNodeWithContentDescription("密码").performTextInput("secret")
        compose.onNodeWithContentDescription("安全指纹").performTextInput("SHA256:key")
        compose.onNodeWithText("连接").performClick()
        assertEquals(RemoteProtocol.SFTP, draft?.protocol)
        assertEquals("example.test", draft?.host)
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
        title: String = rootTitle,
        sortSpec: SortSpec = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING),
        loading: Boolean = false,
        errorMessage: String? = null,
        canGoBack: Boolean = true,
        hasMore: Boolean = false,
        selectedEntries: Set<DirectoryEntry> = emptySet(),
    ) = BrowserUiState(
        currentPath = RootPath.parse("/").getOrThrow(), rootTitle = rootTitle, title = title,
        entries = entries, allEntries = allEntries, totalCount = entries.size,
        loading = loading, errorMessage = errorMessage, canGoBack = canGoBack,
        hasMore = hasMore, displayMode = displayMode, sortSpec = sortSpec,
        canCreateDirectory = canCreateDirectory, creatingDirectory = creatingDirectory,
        createDirectoryError = createDirectoryError, presentationError = presentationError,
        locationTarget = locationTarget, searchQuery = searchQuery,
        selectedEntries = selectedEntries,
    )

    private fun entry(name: String, type: EntryType) = DirectoryEntry(
        path = RootPath.parse("/$name").getOrThrow(), name = name, type = type,
        sizeBytes = 1024, modifiedAtEpochSeconds = 1_700_000_000,
        readable = true, writable = true, symbolicLink = false,
    )
}

private fun SemanticsNode.boundsOnScreen(): Rect {
    val topLeft = positionOnScreen
    return Rect(
        left = topLeft.x,
        top = topLeft.y,
        right = topLeft.x + size.width,
        bottom = topLeft.y + size.height,
    )
}
