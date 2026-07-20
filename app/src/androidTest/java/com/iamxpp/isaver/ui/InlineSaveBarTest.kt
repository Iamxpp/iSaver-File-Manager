package com.iamxpp.isaver.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.transfer.OutputNameDraft
import com.iamxpp.isaver.transfer.ShareSummary
import com.iamxpp.isaver.transfer.TransferUiState
import com.iamxpp.isaver.ui.theme.ISaverTheme
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
        compose.onNodeWithTag("inline-save-stem").performTextReplacement("归档")
        compose.onNodeWithTag("inline-save-extension").performTextReplacement("tar.gz")
        compose.runOnIdle { assertEquals(OutputNameDraft("归档", "tar.gz"), draft) }
    }

    @Test
    fun cachingDisablesFieldsAndShowsStatusWithinCompactHeight() {
        compose.setContent {
            ISaverTheme {
                InlineSaveBar(
                    state = TransferUiState.Caching(
                        share = ShareSummary("report.pdf", 10L, "application/pdf"),
                        outputName = OutputNameDraft("report", "pdf"),
                        bytesCopied = 4L,
                    ),
                    itemCount = 0,
                    onStemChange = {},
                    onExtensionChange = {},
                )
            }
        }

        compose.onNodeWithTag("inline-save-stem").performTextReplacement("changed")
        compose.onNodeWithTag("inline-save-extension").performTextReplacement("txt")
        compose.onNodeWithTag("inline-save-stem").assertTextEquals("report")
        compose.onNodeWithTag("inline-save-extension").assertTextEquals("pdf")
        compose.onNodeWithText("正在准备文件 · 4 B").assertIsDisplayed()
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
