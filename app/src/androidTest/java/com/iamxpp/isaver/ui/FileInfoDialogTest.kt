package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iamxpp.isaver.data.root.RootFileMetadata
import com.iamxpp.isaver.fileops.ChecksumAlgorithm
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

    @Test
    fun computesAndDisplaysSha256() {
        var requested = false
        compose.setContent {
            FileInfoDialog(
                entry = DirectoryEntry(
                    RootPath.parse("/data/local/tmp/value.txt").getOrThrow(), "value.txt",
                    EntryType.FILE, 6, 1, true, false, false,
                ),
                checksumValue = "a".repeat(64),
                onCalculateChecksum = { requested = true },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("a".repeat(64)).assertIsDisplayed()
        compose.onNodeWithText("计算 SHA-256").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(requested) }
    }

    @Test
    fun showsExactMetadataAndLoadingOrErrorState() {
        val entry = DirectoryEntry(
            RootPath.parse("/data/local/tmp/value.txt").getOrThrow(), "value.txt",
            EntryType.FILE, 6, 1, true, false, false,
        )
        compose.setContent {
            FileInfoDialog(
                entry = entry,
                metadata = RootFileMetadata(0x1A0, 1000, 1001, 12, 34),
                metadataLoading = true,
                metadataError = "文件已变化，请刷新核对",
                onDismiss = {},
            )
        }

        compose.onNodeWithText("0640").assertIsDisplayed()
        compose.onNodeWithText("1000 / 1001").assertIsDisplayed()
        compose.onNodeWithText("12 / 34").assertIsDisplayed()
        compose.onNodeWithText("正在读取").assertIsDisplayed()
        compose.onNodeWithText("文件已变化，请刷新核对").assertIsDisplayed()
    }

    @Test
    fun supportsSelectingNonSha256ChecksumAlgorithm() {
        var selected: ChecksumAlgorithm? = null
        compose.setContent {
            FileInfoDialog(
                entry = DirectoryEntry(
                    RootPath.parse("/data/local/tmp/value.txt").getOrThrow(), "value.txt",
                    EntryType.FILE, 6, 1, true, false, false,
                ),
                checksumAlgorithm = ChecksumAlgorithm.SHA512,
                onChecksumAlgorithmChange = { selected = it },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("SHA-512 ✓").assertIsDisplayed()
        compose.onNodeWithText("SHA-1").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(ChecksumAlgorithm.SHA1, selected) }
    }
}
