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
import com.iamxpp.isaver.bookmarks.Bookmark
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.archive.ArchiveFormat
import com.iamxpp.isaver.fileops.ConflictAction
import com.iamxpp.isaver.fileops.BatchRenameItem
import com.iamxpp.isaver.fileops.BatchRenamePlan
import com.iamxpp.isaver.fileops.BatchRenameRule
import com.iamxpp.isaver.tasks.OperationRecoveryPolicy
import com.iamxpp.isaver.tasks.OperationTask
import com.iamxpp.isaver.tasks.OperationTaskState
import com.iamxpp.isaver.tasks.OperationTaskType
import com.iamxpp.isaver.search.LocalSearchCriteria
import com.iamxpp.isaver.trash.TrashItem
import com.iamxpp.isaver.trash.TrashItemState
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
    @Test fun bookmarkAndForwardMenuActionsAreWired() {
        val bookmark = Bookmark(
            path = RootPath.parse("/data/local/tmp/work").getOrThrow(),
            displayName = "工作目录",
            createdAt = 10L,
        )
        var forwarded = false
        var toggled = false
        var opened: Bookmark? = null
        compose.setContent {
            BrowserScreen(
                state = state().copy(
                    canGoForward = true,
                    currentPathBookmarked = true,
                    bookmarks = listOf(bookmark),
                ),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onForward = { forwarded = true },
                onToggleCurrentBookmark = { toggled = true },
                onOpenBookmark = { opened = it },
            )
        }

        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithText("前进").performClick()
        assertTrue(forwarded)

        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithText("取消收藏当前路径").performClick()
        assertTrue(toggled)

        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithText("书签").performClick()
        compose.onNodeWithText("工作目录").assertIsDisplayed().performClick()
        assertEquals(bookmark, opened)
    }

    @Test fun conflictDialogForwardsSingleAndTaskWideDecisions() {
        val decisions = mutableListOf<Pair<ConflictAction, Boolean>>()
        val prompt = BrowserConflictPrompt(
            operation = BrowserConflictOperation.COPY,
            entryName = "报告.txt",
            completedCount = 1,
            totalCount = 3,
        )
        compose.setContent {
            BrowserScreen(
                state = state().copy(conflictPrompt = prompt),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onResolveConflict = { action, applyToAll -> decisions += action to applyToAll },
            )
        }

        compose.onNodeWithText("目标位置存在同名项目").assertIsDisplayed()
        compose.onNodeWithText("报告.txt").assertIsDisplayed()
        compose.onNodeWithText("已复制 1/3 项").assertIsDisplayed()
        compose.onNodeWithText("全部保留两者").performClick()

        assertEquals(listOf(ConflictAction.KEEP_BOTH to true), decisions)
    }

    @Test fun conflictDialogOffersExplicitReplace() {
        var selected: ConflictAction? = null
        compose.setContent {
            BrowserScreen(
                state = state().copy(conflictPrompt = BrowserConflictPrompt(
                    BrowserConflictOperation.MOVE, "报告.txt", 0, 1,
                )),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onResolveConflict = { action, _ -> selected = action },
            )
        }

        compose.onNodeWithText("替换").performClick()
        compose.runOnIdle { assertEquals(ConflictAction.REPLACE, selected) }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun longPressDirectoryOffersMoveAndCopyActions() {
        val directory = entry("folder", EntryType.DIRECTORY)
        var selected: DirectoryEntry? = null
        var moved: DirectoryEntry? = null
        var copied: DirectoryEntry? = null
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(directory)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onSelectEntry = { selected = it },
                onMoveEntry = { moved = it },
                onCopyEntry = { copied = it },
            )
        }

        compose.onNodeWithContentDescription("列表项：folder").performTouchInput { longClick() }
        compose.onNodeWithText("移动到").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("列表项：folder").performTouchInput { longClick() }
        compose.onNodeWithText("复制到").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(directory, selected)
            assertEquals(directory, moved)
            assertEquals(directory, copied)
        }
    }

    @Test
    fun longPressDirectoryForwardsRenameFromActionSheet() {
        val directory = entry("folder", EntryType.DIRECTORY)
        var renamed: Pair<DirectoryEntry, String>? = null
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(directory)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onRenameEntry = { entry, name -> renamed = entry to name },
            )
        }

        compose.onNodeWithContentDescription("列表项：folder").performTouchInput { longClick() }
        compose.onNodeWithText("重命名").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("新文件名").performTextReplacement("renamed-folder")
        compose.onNodeWithText("确定").performClick()

        compose.runOnIdle { assertEquals(directory to "renamed-folder", renamed) }
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
    fun longPressFileForwardsExplicitOpenWith() {
        val file = entry("report.pdf", EntryType.FILE)
        var opened: DirectoryEntry? = null
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(file)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onOpenWithEntry = { opened = it },
            )
        }

        compose.onNodeWithContentDescription("列表项：report.pdf").performTouchInput { longClick() }
        compose.onNodeWithText("打开方式").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(file, opened) }
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
    fun batchRenameRequiresPreviewBeforeExecution() {
        val first = entry("old-a.txt", EntryType.FILE)
        val second = entry("old-b.txt", EntryType.FILE)
        var previewRule: BatchRenameRule? = null
        var executed = false
        var currentState by mutableStateOf(
            state(
                entries = listOf(first, second),
                allEntries = listOf(first, second),
                selectedEntries = setOf(first, second),
            ),
        )
        compose.setContent {
            BrowserScreen(
                state = currentState,
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onPreviewBatchRename = { rule ->
                    previewRule = rule
                    currentState = currentState.copy(
                        batchRenamePlan = BatchRenamePlan(
                            listOf(
                                BatchRenameItem(first, com.iamxpp.isaver.domain.EntryName.parse("new-a.txt").getOrThrow()),
                                BatchRenameItem(second, com.iamxpp.isaver.domain.EntryName.parse("new-b.txt").getOrThrow()),
                            ),
                        ),
                    )
                },
                onExecuteBatchRename = { executed = true },
            )
        }

        compose.onNodeWithText("批量重命名").performClick()
        compose.onNodeWithText("执行").assertIsNotEnabled()
        compose.onNodeWithContentDescription("查找").performTextInput("old")
        compose.onNodeWithContentDescription("替换为").performTextInput("new")
        compose.onNodeWithText("生成预览").performClick()
        compose.onNodeWithText("old-a.txt  →  new-a.txt").assertIsDisplayed()
        compose.onNodeWithText("old-b.txt  →  new-b.txt").assertIsDisplayed()
        compose.onNodeWithText("执行").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals("old", previewRule?.find)
            assertEquals("new", previewRule?.replacement)
            assertTrue(executed)
        }
    }

    @Test
    fun taskCenterShowsPersistedProgressAndClearsFinishedTasks() {
        var cleared = false
        var paused = false
        var cancelled = false
        val task = OperationTask(
            id = "task-1",
            type = OperationTaskType.COPY,
            state = OperationTaskState.RUNNING,
            totalItems = 3,
            completedItems = 1,
            failedItems = 0,
            totalBytes = 3_000,
            completedBytes = 1_000,
            recoveryPolicy = OperationRecoveryPolicy.NEVER_REPLAY,
            message = null,
            createdAt = 1,
            updatedAt = 2,
        )
        compose.setContent {
            BrowserScreen(
                state = state().copy(operationTasks = listOf(task), controllableTaskId = task.id),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onClearFinishedTasks = { cleared = true },
                onPauseTask = { paused = true },
                onCancelTask = { cancelled = true },
            )
        }

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("任务中心").assertIsDisplayed().performClick()
        compose.onNodeWithText("复制").assertIsDisplayed()
        compose.onNodeWithText("运行中 · 1/3 · 1000 B/2.9 KB").assertIsDisplayed()
        compose.onNodeWithText("暂停").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("清理已完成").performClick()

        compose.runOnIdle {
            assertTrue(cleared)
            assertTrue(paused)
            assertTrue(cancelled)
        }
    }

    @Test
    fun sharedDeleteDefaultsToTrashAndPermanentDeleteNeedsExplicitConfirmation() {
        val file = DirectoryEntry(
            RootPath.parse("/storage/emulated/0/report.txt").getOrThrow(), "report.txt",
            EntryType.FILE, 10, 1, true, true, false,
        )
        var recycled = false
        var permanentlyDeleted = false
        compose.setContent {
            BrowserScreen(
                state = state(entries = listOf(file)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onRecycleEntry = { recycled = true },
                onDeleteEntryPermanently = { permanentlyDeleted = true },
            )
        }

        compose.onNodeWithContentDescription("列表项：report.txt").performTouchInput { longClick() }
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("移入回收站").assertIsDisplayed()
        compose.onNodeWithText("永久删除").performClick()
        compose.onNodeWithText("确认永久删除").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertTrue(permanentlyDeleted)
            assertEquals(false, recycled)
        }
    }

    @Test
    fun trashDialogRestoresActiveItem() {
        var restored: TrashItem? = null
        val item = TrashItem(
            "trash-id", RootPath.parse("/storage/emulated/0/report.txt").getOrThrow(),
            RootPath.parse("/storage/emulated/0").getOrThrow(), "report.txt",
            RootPath.parse("/storage/emulated/0/.iSaver/Trash/files/trash-id").getOrThrow(),
            "trash-id", EntryType.FILE, 10, 1, 2, TrashItemState.ACTIVE, 3,
        )
        compose.setContent {
            BrowserScreen(
                state = state().copy(trashItems = listOf(item)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onRestoreTrashItem = { restored = it },
            )
        }

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("回收站").performClick()
        compose.onNodeWithText("report.txt").assertIsDisplayed()
        compose.onNodeWithText("恢复").performClick()

        compose.runOnIdle { assertEquals(item, restored) }
    }

    @Test
    fun trashDialogForwardsBatchRestoreAndClearActions() {
        var restored: List<TrashItem>? = null
        var cleared = false
        val item = TrashItem(
            "trash-id", RootPath.parse("/storage/emulated/0/report.txt").getOrThrow(),
            RootPath.parse("/storage/emulated/0").getOrThrow(), "report.txt",
            RootPath.parse("/storage/emulated/0/.iSaver/Trash/files/trash-id").getOrThrow(),
            "trash-id", EntryType.FILE, 10, 1, 2, TrashItemState.ACTIVE, 3,
        )
        compose.setContent {
            BrowserScreen(
                state = state().copy(trashItems = listOf(item)),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onRestoreAllTrashItems = { restored = it },
                onClearTrash = { cleared = true },
            )
        }

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("回收站").performClick()
        compose.onNodeWithText("恢复全部").performClick()
        assertEquals(listOf(item), restored)
        compose.onNodeWithText("关闭").performClick()

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("回收站").performClick()
        compose.onNodeWithText("清空回收站").performClick()
        compose.onNodeWithText("确认清空回收站").performClick()
        assertTrue(cleared)
    }

    @Test
    fun selectionBarForwardsBatchDelete() {
        val first = entry("one.txt", EntryType.FILE)
        val second = entry("two.txt", EntryType.FILE)
        var deleted: List<DirectoryEntry>? = null
        compose.setContent {
            BrowserScreen(
                state = state(
                    entries = listOf(first, second),
                    selectedEntries = setOf(first, second),
                ),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onRecycleSelection = { deleted = it },
            )
        }
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("继续").performClick()
        assertEquals(listOf(first, second), deleted)
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

    @Test fun deepSearchDialogStartsAndShowsSourcePaths() {
        var criteria: LocalSearchCriteria? = null
        var currentState by mutableStateOf(state())
        val result = DirectoryEntry(
            RootPath.parse("/documents/archive/report.txt").getOrThrow(),
            "report.txt", EntryType.FILE, 12, 1, true, false, false,
        )
        compose.setContent {
            BrowserScreen(
                state = currentState,
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onStartDeepSearch = {
                    criteria = it
                    currentState = currentState.copy(
                        deepSearchCriteria = it,
                        deepSearchResults = listOf(result),
                        deepSearchScannedDirectories = 3,
                        deepSearchScannedEntries = 12,
                    )
                },
            )
        }

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("深度搜索").performClick()
        compose.onNodeWithText("名称").performTextInput("report.*")
        compose.onNodeWithText("正则表达式").performClick()
        compose.onNodeWithText("扩展名，例如 txt").performTextInput("txt")
        compose.onNodeWithText("文件").performClick()
        compose.onNodeWithText("开始").performClick()

        assertEquals("report.*", criteria?.query)
        assertTrue(criteria?.regularExpression == true)
        assertEquals("txt", criteria?.extension)
        compose.onNodeWithText("已扫描 3 个目录、12 个项目").assertIsDisplayed()
        compose.onNodeWithText("/documents/archive").assertIsDisplayed()
    }

    @Test fun deepSearchIsHiddenWhileChoosingDestination() {
        compose.setContent {
            BrowserScreen(
                state = state(), onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                saveAction = FilesSaveAction(enabled = true, onSave = {}, label = "移动到这里"),
                fileActionsEnabled = false,
            )
        }

        compose.onNodeWithContentDescription("更多操作").assertDoesNotExist()
        compose.onNodeWithText("深度搜索").assertDoesNotExist()
    }

    @Test fun createFileDialogForwardsExactName() {
        var created: String? = null
        compose.setContent {
            BrowserScreen(
                state = state(canCreateDirectory = true),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onCreateFile = { created = it },
            )
        }

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("新建文件").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("文件名称").performTextReplacement("测试 report.txt")
        compose.onNodeWithText("确定").performClick()

        assertEquals("测试 report.txt", created)
    }

    @Test fun createFileMenuIsAbsentWithoutNormalBrowserCallback() {
        compose.setContent {
            BrowserScreen(
                state = state(canCreateDirectory = true),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
            )
        }

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onAllNodesWithText("新建文件").assertCountEquals(0)
        compose.onNodeWithText("新建文件夹").assertIsEnabled()
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
                onCompress = { name, _ -> output = name },
            )
        }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("压缩文件").assertIsEnabled().performClick()
        compose.onNodeWithText("压缩文件名称").assertIsDisplayed()
        compose.onNodeWithText("archive").assertIsDisplayed()
        compose.onNodeWithText("确定").performClick()
        assertEquals("archive.zip", output)
    }

    @Test fun compressDialogReturnsSelectedTarGzFormat() {
        val file = entry("说明.txt", EntryType.FILE)
        var output: String? = null
        var selectedFormat: ArchiveFormat? = null
        compose.setContent {
            BrowserScreen(
                state = state(
                    entries = listOf(file),
                    allEntries = listOf(file),
                    selectedEntries = setOf(file),
                ),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onCompress = { name, format ->
                    output = name
                    selectedFormat = format
                },
            )
        }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("压缩文件").performClick()
        compose.onNodeWithText("TAR.GZ").performClick()
        compose.onNodeWithText("确定").performClick()

        assertEquals("archive.tar.gz", output)
        assertEquals(ArchiveFormat.TAR_GZ, selectedFormat)
    }

    @Test fun compressDialogDoesNotDuplicateTypedExtension() {
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
                onCompress = { name, _ -> output = name },
            )
        }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("压缩文件").performClick()
        compose.onNodeWithText("压缩文件名称").performTextReplacement("backup.ZIP")
        compose.onNodeWithText("确定").performClick()

        assertEquals("backup.zip", output)
    }

    @Test fun releaseBrowserDoesNotExposeFrozenRemoteServerEntry() {
        compose.setContent {
            BrowserScreen(
                state = state(),
                onEnterDirectory = {}, onBack = {}, onRetry = {}, onLoadMore = {},
                onConnectServer = {},
            )
        }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onAllNodesWithText("连接服务器").assertCountEquals(0)
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
