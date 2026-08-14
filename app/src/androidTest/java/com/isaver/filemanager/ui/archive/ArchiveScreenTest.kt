package com.isaver.filemanager.ui.archive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.isaver.filemanager.archive.ArchiveEntry
import com.isaver.filemanager.archive.ArchiveFormat
import com.isaver.filemanager.archive.ArchiveListing
import com.isaver.filemanager.archive.ArchiveProgress
import com.isaver.filemanager.archive.ArchiveState
import com.isaver.filemanager.archive.children
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.RootPath
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ArchiveScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsArchiveHierarchyFormatAndExtractionAction() {
        val listing = ArchiveListing(
            ArchiveFormat.TAR_GZ,
            listOf(ArchiveEntry("docs/report.pdf", false, 2048L, 1024L)),
        )
        var entered = false
        var extractionRequested = false
        compose.setContent {
            ArchiveScreen(
                state = state(listing = listing),
                onBack = {},
                onEnter = { entered = true },
                onQueryChange = {},
                onDisplayModeChange = {},
                onChooseExtractionTarget = { extractionRequested = true },
                onRetry = {},
                onCancelExtraction = {},
                onDismissOperation = {},
            )
        }

        compose.onNodeWithContentDescription("页面标题：backup.tar.gz").assertIsDisplayed()
        compose.onNodeWithText("TAR.GZ").assertIsDisplayed()
        compose.onNodeWithContentDescription("列表项：docs").performClick()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("解压").performClick()
        compose.runOnIdle {
            assertTrue(entered)
            assertTrue(extractionRequested)
        }
    }

    @Test
    fun exposesInspectionErrorAndRetry() {
        var retried = false
        compose.setContent {
            ArchiveScreen(
                state = state(errorMessage = "无法读取压缩包"),
                onBack = {}, onEnter = {}, onQueryChange = {}, onDisplayModeChange = {},
                onChooseExtractionTarget = {}, onRetry = { retried = true },
                onCancelExtraction = {}, onDismissOperation = {},
            )
        }

        compose.onNodeWithText("无法读取压缩包").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun runningExtractionShowsProgressAndCancel() {
        compose.setContent {
            ArchiveScreen(
                state = state(
                    operation = ArchiveState.Running(
                        ArchiveProgress.Entry("docs/report.pdf", 50L, 100L),
                    ),
                ),
                onBack = {}, onEnter = {}, onQueryChange = {}, onDisplayModeChange = {},
                onChooseExtractionTarget = {}, onRetry = {}, onCancelExtraction = {},
                onDismissOperation = {},
            )
        }

        compose.onNodeWithText("正在解压").assertIsDisplayed()
        compose.onNodeWithText("docs/report.pdf").assertIsDisplayed()
        compose.onNodeWithText("50 / 100 B").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test
    fun publishingIsAVisibleNonCancellableFinalization() {
        compose.setContent {
            ArchiveScreen(
                state = state(operation = ArchiveState.Publishing("backup")),
                onBack = {}, onEnter = {}, onQueryChange = {}, onDisplayModeChange = {},
                onChooseExtractionTarget = {}, onRetry = {}, onCancelExtraction = {},
                onDismissOperation = {},
            )
        }

        compose.onNodeWithText("正在完成").assertIsDisplayed()
        compose.onNodeWithText("取消").assertDoesNotExist()
    }

    private fun state(
        listing: ArchiveListing? = null,
        errorMessage: String? = null,
        operation: ArchiveState? = null,
    ) = ArchiveUiState(
        source = RootPath.parse("/archives/backup.tar.gz").getOrThrow(),
        sourceName = "backup.tar.gz",
        listing = listing,
        nodes = listing?.children("").orEmpty(),
        errorMessage = errorMessage,
        operation = operation,
    )
}
