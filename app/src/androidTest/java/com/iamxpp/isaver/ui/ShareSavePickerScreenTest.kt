package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.mutableStateOf
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.transfer.OutputNameDraft
import com.iamxpp.isaver.transfer.ShareSummary
import com.iamxpp.isaver.transfer.TransferUiState
import com.iamxpp.isaver.ui.theme.ISaverTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ShareSavePickerScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun choosingShowsFullscreenPickerWithTwoEditableNameFields() {
        var stem = ""
        var extension = ""
        val draft = mutableStateOf(OutputNameDraft("report", "pdf"))
        compose.setContent {
            ISaverTheme {
                ShareSavePickerScreen(
                    transferState = choosing(canSave = true).copy(outputName = draft.value),
                    browserState = browserState(),
                    onCancel = {},
                    onSave = {},
                    onStemChange = {
                        stem = it
                        draft.value = draft.value.copy(stem = it)
                    },
                    onExtensionChange = {
                        extension = it
                        draft.value = draft.value.copy(extension = it)
                    },
                    onEnterDirectory = {},
                    onBack = {},
                    onRetryBrowser = {},
                    onLoadMore = {},
                    onSearchQueryChange = {},
                )
            }
        }

        compose.onNodeWithTag("share-picker").assertIsDisplayed()
        compose.onNodeWithTag("share-picker-title").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithTag("share-picker-save").assertIsEnabled()
        compose.onNodeWithContentDescription("搜索文件").assertIsDisplayed()
        compose.onNodeWithTag("share-picker-stem").performTextReplacement("archive")
        compose.onNodeWithTag("share-picker-extension").performTextReplacement("tar.gz")
        compose.onNodeWithText("2 个项目").assertIsDisplayed()
        compose.onNodeWithContentDescription("列表项：文件夹").assertIsEnabled()
        compose.onNodeWithContentDescription("列表项：report.pdf").assertIsNotEnabled()
        compose.onNodeWithText("最近项目").assertDoesNotExist()

        compose.runOnIdle {
            assertEquals("archive", stem)
            assertEquals("tar.gz", extension)
        }
    }

    @Test
    fun cachingDisablesSaveAndUncertainRequiresAcknowledgement() {
        compose.setContent {
            ISaverTheme {
                ShareSavePickerScreen(
                    transferState = TransferUiState.Caching(
                        share = ShareSummary("report.pdf", 10L, "application/pdf"),
                        outputName = OutputNameDraft("report", "pdf"),
                        bytesCopied = 4L,
                    ),
                    browserState = browserState(),
                    onCancel = {},
                    onSave = {},
                    onStemChange = {},
                    onExtensionChange = {},
                    onEnterDirectory = {},
                    onBack = {},
                    onRetryBrowser = {},
                    onLoadMore = {},
                    onSearchQueryChange = {},
                )
            }
        }

        compose.onNodeWithTag("share-picker-save").assertIsNotEnabled()
        compose.onNodeWithText("正在准备文件 · 4 B").assertIsDisplayed()
    }

    private fun choosing(canSave: Boolean) = TransferUiState.Choosing(
        share = ShareSummary("report.pdf", 10L, "application/pdf"),
        outputName = OutputNameDraft("report", "pdf"),
        cachedBytes = 10L,
        targetDirectory = root("/target"),
        canSave = canSave,
    )

    private fun browserState() = BrowserUiState(
        currentPath = root("/target"),
        title = "Documents",
        entries = listOf(
            entry("/target/folder", "文件夹", EntryType.DIRECTORY),
            entry("/target/report.pdf", "report.pdf", EntryType.FILE),
        ),
        totalCount = 2,
        canGoBack = false,
        canCreateDirectory = true,
    )

    private fun entry(path: String, name: String, type: EntryType) = DirectoryEntry(
        path = root(path),
        name = name,
        type = type,
        sizeBytes = if (type == EntryType.FILE) 10L else null,
        modifiedAtEpochSeconds = 1L,
        readable = true,
        writable = true,
        symbolicLink = false,
    )

    private fun root(value: String) = RootPath.parse(value).getOrThrow()
}
