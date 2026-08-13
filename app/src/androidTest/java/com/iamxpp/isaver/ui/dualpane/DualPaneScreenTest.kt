package com.iamxpp.isaver.ui.dualpane

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.BrowserUiState
import com.iamxpp.isaver.ui.files.DisplayMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DualPaneScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun portraitShowsBothPanesAndCompactToolbar() {
        compose.setContent {
            val portrait = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 393
                screenHeightDp = 851
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides portrait) {
                screen()
            }
        }

        compose.onNodeWithContentDescription("主窗口，已激活").assertIsDisplayed()
        compose.onNodeWithContentDescription("副窗口").assertIsDisplayed()
        listOf("关闭双窗口", "同步到另一窗口", "交换窗口", "锁定当前窗口").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed()
        }
    }

    @Test
    fun landscapeShowsBothPanesAndDualModeHidesGridChoice() {
        compose.setContent {
            val landscape = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 851
                screenHeightDp = 393
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(LocalConfiguration provides landscape) {
                screen()
            }
        }

        compose.onAllNodesWithContentDescription("更多操作")[0].performClick()
        compose.onAllNodesWithText("图标").assertCountEquals(0)
        compose.onAllNodesWithText("列表").assertCountEquals(0)
        compose.onNodeWithContentDescription("主窗口，已激活").assertIsDisplayed()
        compose.onNodeWithContentDescription("副窗口").assertIsDisplayed()
    }

    @Test
    fun fileClickUsesOpenCallbackAndCrossPaneActionsRespectTargetGate() {
        val file = entry("readme.txt", EntryType.FILE)
        var opened: DirectoryEntry? = null
        compose.setContent {
            screen(
                primary = state("/source", entries = listOf(file)),
                secondary = state("/target", writable = false),
                primaryCallbacks = callbacks(openEntry = { opened = it }),
            )
        }

        compose.onNodeWithText("readme.txt").performClick()
        compose.runOnIdle { assertEquals(file, opened) }
        compose.onNodeWithContentDescription("复制到另一窗口").assertIsNotEnabled()
        compose.onNodeWithContentDescription("移动到另一窗口").assertIsNotEnabled()
    }

    @Test
    fun selectedSourceAndWritableTargetEnableCrossPaneActions() {
        val file = entry("report.txt", EntryType.FILE)
        compose.setContent {
            screen(
                primary = state("/source", entries = listOf(file), selected = setOf(file)),
                secondary = state("/target", writable = true),
            )
        }

        compose.onNodeWithContentDescription("复制到另一窗口").assertIsEnabled()
        compose.onNodeWithContentDescription("移动到另一窗口").assertIsEnabled()
    }

    @Test
    fun longPressSelectsSourceAndActivatesItsPane() {
        val file = entry("select.txt", EntryType.FILE)
        var selected: DirectoryEntry? = null
        var active: PaneId? = null
        compose.setContent {
            DualPaneScreen(
                state = DualPaneState(
                    enabled = true,
                    activePane = PaneId.PRIMARY,
                    primary = PaneLocation(RootPath.parse("/primary").getOrThrow(), "主窗"),
                    secondary = PaneLocation(RootPath.parse("/secondary").getOrThrow(), "副窗"),
                ),
                primaryState = state("/primary"),
                secondaryState = state("/secondary", entries = listOf(file), writable = true),
                primaryCallbacks = callbacks(),
                secondaryCallbacks = callbacks().copy(toggleSelection = { selected = it }),
                onActivate = { active = it },
                onClose = {}, onSync = {}, onSwap = {}, onToggleLock = {},
                onCopyToOther = {}, onMoveToOther = {},
            )
        }

        compose.onNodeWithText("select.txt").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(file, selected)
            assertEquals(PaneId.SECONDARY, active)
        }
    }

    @Test
    fun forcedListRendersRowsEvenWhenPanePreferenceIsGrid() {
        val file = entry("grid-pref.txt", EntryType.FILE)
        compose.setContent {
            screen(primary = state("/source", entries = listOf(file)).copy(displayMode = DisplayMode.GRID))
        }

        compose.onNodeWithTag("file-row-/grid-pref.txt").assertIsDisplayed()
    }

    @Composable
    private fun screen(
        primary: BrowserUiState = state("/primary"),
        secondary: BrowserUiState = state("/secondary", writable = true),
        primaryCallbacks: DualPaneBrowserCallbacks = callbacks(),
    ) {
        DualPaneScreen(
            state = DualPaneState(
                enabled = true,
                primary = PaneLocation(primary.currentPath, primary.title),
                secondary = PaneLocation(secondary.currentPath, secondary.title),
            ),
            primaryState = primary,
            secondaryState = secondary,
            primaryCallbacks = primaryCallbacks,
            secondaryCallbacks = callbacks(),
            onActivate = {},
            onClose = {},
            onSync = {},
            onSwap = {},
            onToggleLock = {},
            onCopyToOther = {},
            onMoveToOther = {},
        )
    }

    private fun callbacks(openEntry: (DirectoryEntry) -> Unit = {}) = DualPaneBrowserCallbacks(
        enterDirectory = {},
        back = {},
        forward = {},
        retry = {},
        loadMore = {},
        query = {},
        toggleSelection = {},
        clearSelection = {},
        openEntry = openEntry,
        resolveConflict = { _, _ -> },
        dismissMoveError = {},
        dismissCopyError = {},
        dismissOpenError = {},
        dismissPreview = {},
    )

    private fun state(
        path: String,
        entries: List<DirectoryEntry> = emptyList(),
        selected: Set<DirectoryEntry> = emptySet(),
        writable: Boolean = false,
    ) = BrowserUiState(
        currentPath = RootPath.parse(path).getOrThrow(),
        title = path,
        rootTitle = path,
        entries = entries,
        allEntries = entries,
        totalCount = entries.size,
        canCreateDirectory = writable,
        selectedEntries = selected,
    )

    private fun entry(name: String, type: EntryType) = DirectoryEntry(
        path = RootPath.parse("/$name").getOrThrow(),
        name = name,
        type = type,
        sizeBytes = 12,
        modifiedAtEpochSeconds = 1_700_000_000,
        readable = true,
        writable = true,
        symbolicLink = false,
    )
}
