package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import org.junit.Rule
import org.junit.Test

class FileInfoDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsCompleteMetadataAndUnreadableState() {
        compose.setContent {
            FileInfoDialog(
                entry = DirectoryEntry(
                    path = RootPath.parse("/data/local/tmp/report.pdf").getOrThrow(),
                    name = "report.pdf",
                    type = EntryType.FILE,
                    sizeBytes = 2048L,
                    modifiedAtEpochSeconds = 1L,
                    readable = false,
                    writable = true,
                    symbolicLink = false,
                ),
                onDismiss = {},
            )
        }

        compose.onNodeWithText("文件信息").assertIsDisplayed()
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        compose.onNodeWithText("/data/local/tmp/report.pdf").assertIsDisplayed()
        compose.onNodeWithText("2 KB").assertIsDisplayed()
        compose.onNodeWithText("不可读").assertIsDisplayed()
        compose.onNodeWithText("可写").assertIsDisplayed()
    }
}
