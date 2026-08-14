package com.isaver.filemanager.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.transfer.OutputNameDraft
import com.isaver.filemanager.transfer.ShareSummary
import com.isaver.filemanager.transfer.TransferPhase
import com.isaver.filemanager.transfer.TransferUiState
import com.isaver.filemanager.ui.theme.ISaverTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InlineSaveBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun choosingShowsCompactVisibleEditableDefaults() {
        var draft by mutableStateOf(OutputNameDraft("测试 报告", "pdf"))
        compose.setContent {
            ISaverTheme {
                InlineSaveBar(
                    state = choosing(draft),
                    itemCount = 3,
                    onStemChange = { draft = draft.copy(stem = it) },
                    onExtensionChange = { draft = draft.copy(extension = it) },
                )
            }
        }

        compose.onNodeWithTag("inline-save-stem").assertTextEquals("测试 报告")
        compose.onNodeWithTag("inline-save-extension").assertTextEquals("pdf")
        compose.onNodeWithText("3 个项目").assertIsDisplayed()
        assertCompactHeight()

        compose.onNodeWithTag("inline-save-stem").performClick()
        compose.onNodeWithTag("expanded-name-field").performTextReplacement("归档")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("inline-save-extension").performClick()
        compose.onNodeWithTag("expanded-name-field").performTextReplacement("tar.gz")
        compose.onNodeWithText("完成").performClick()

        compose.runOnIdle { assertEquals(OutputNameDraft("归档", "tar.gz"), draft) }
    }

    @Test
    fun savingDisablesFieldsAndShowsStatusWithinCompactHeight() {
        compose.setContent {
            ISaverTheme {
                InlineSaveBar(
                    state = TransferUiState.Saving(
                        share = ShareSummary("report.pdf", 10L, "application/pdf"),
                        outputName = OutputNameDraft("report", "pdf"),
                        cachedBytes = 10L,
                        targetDirectory = root("/target"),
                        phase = TransferPhase.ResolvingName,
                    ),
                    itemCount = 0,
                    onStemChange = {},
                    onExtensionChange = {},
                )
            }
        }

        compose.onNodeWithTag("inline-save-stem").performClick()
        compose.onAllNodesWithText("编辑文件名").assertCountEquals(0)
        compose.onNodeWithTag("inline-save-extension").performClick()
        compose.onAllNodesWithText("编辑扩展名").assertCountEquals(0)
        compose.onNodeWithTag("inline-save-stem").assertTextEquals("report")
        compose.onNodeWithTag("inline-save-extension").assertTextEquals("pdf")
        compose.onNodeWithText("正在准备存储").assertIsDisplayed()
        assertCompactHeight()
    }

    @Test
    fun clickingCompactNameOpensExpandedEditorForLongNames() {
        var draft by mutableStateOf(OutputNameDraft("很长的文件名".repeat(12), "pdf"))
        compose.setContent {
            ISaverTheme {
                InlineSaveBar(
                    state = choosing(draft),
                    itemCount = 1,
                    onStemChange = { draft = draft.copy(stem = it) },
                    onExtensionChange = { draft = draft.copy(extension = it) },
                )
            }
        }

        compose.onNodeWithTag("inline-save-stem").performClick()
        compose.onNodeWithText("编辑文件名").assertIsDisplayed()
        compose.onNodeWithTag("expanded-name-field").performTextReplacement("完整文件名")
        compose.onNodeWithText("完成").performClick()

        compose.runOnIdle { assertEquals(OutputNameDraft("完整文件名", "pdf"), draft) }
        compose.onNodeWithTag("inline-save-stem").assertTextEquals("完整文件名")
    }

    @Test
    fun retryableFailureAllowsExpandedEditorForRenameBeforeRetry() {
        var draft by mutableStateOf(OutputNameDraft("失败后的长文件名".repeat(8), "docx"))
        compose.setContent {
            ISaverTheme {
                InlineSaveBar(
                    state = TransferUiState.Failure(
                        share = ShareSummary("失败后的长文件名.docx", 37L, "application/octet-stream"),
                        outputName = draft,
                        targetDirectory = root("/target"),
                        code = ErrorCode.COMMAND_FAILED,
                        message = "无法准备目标目录",
                        retryable = true,
                    ),
                    itemCount = 1,
                    onStemChange = { draft = draft.copy(stem = it) },
                    onExtensionChange = { draft = draft.copy(extension = it) },
                )
            }
        }

        compose.onNodeWithTag("inline-save-stem").performClick()
        compose.onNodeWithText("编辑文件名").assertIsDisplayed()
        compose.onNodeWithTag("expanded-name-field").performTextReplacement("重命名后再保存")
        compose.onNodeWithText("完成").performClick()

        compose.runOnIdle { assertEquals(OutputNameDraft("重命名后再保存", "docx"), draft) }
    }

    @Test
    fun uncertainStateKeepsCompactAcknowledgementAction() {
        var acknowledged = false
        compose.setContent {
            ISaverTheme {
                InlineSaveBar(
                    state = TransferUiState.Uncertain(
                        share = ShareSummary("report.pdf", 10L, "application/pdf"),
                        outputName = OutputNameDraft("report", "pdf"),
                        targetDirectory = root("/target"),
                        message = "请核对目标文件",
                    ),
                    itemCount = 2,
                    onStemChange = {},
                    onExtensionChange = {},
                    onAcknowledgeUncertain = { acknowledged = true },
                )
            }
        }

        compose.onNodeWithText("已核对").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(acknowledged) }
        assertCompactHeight()
    }

    private fun assertCompactHeight() {
        val height = compose.onNodeWithTag("inline-save-bar")
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .resources
            .displayMetrics
            .density
        assertTrue("height=$height", height <= 112f * density + 1f)
    }

    private fun choosing(draft: OutputNameDraft) = TransferUiState.Choosing(
        share = ShareSummary("测试 报告.pdf", 37L, "application/pdf"),
        outputName = draft,
        cachedBytes = 37L,
        targetDirectory = root("/target"),
        canSave = true,
    )

    private fun root(value: String) = RootPath.parse(value).getOrThrow()
}
