package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.locations.ResolvedAppLocation
import com.iamxpp.isaver.locations.StorageLocation
import com.iamxpp.isaver.ui.files.DisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class LocationHomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun displaysViewsTitleAndLocationSections() {
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> },
                onAdd = { _, _ -> },
                onEdit = { _, _, _ -> },
                onRemove = {},
                onRetry = {},
            )
        }

        compose.onNodeWithText("视图").assertIsDisplayed()
        compose.onNodeWithText("应用位置").assertIsDisplayed()
        compose.onNodeWithText("通用位置").assertIsDisplayed()
        compose.onNodeWithText("自定义位置").assertIsDisplayed()
    }

    @Test
    fun appCandidateDisplaysResolvedPathAndOpensWithTypedUnchangedPath() {
        val candidate = direct(
            id = "wechat.internal",
            name = "内部数据目录",
            path = "/data/user/0/com.tencent.mm//files",
            source = StorageLocation.Source.APP_TEMPLATE,
        )
        var opened: Pair<RootPath, String>? = null
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(
                    loading = false,
                    appGroups = listOf(
                        ResolvedAppLocation(
                            templateId = LocationId.of("template.wechat"),
                            displayName = "微信",
                            children = listOf(candidate),
                            unavailableCount = 0,
                        ),
                    ),
                ),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { path, title -> opened = path to title },
                onAdd = { _, _ -> },
                onEdit = { _, _, _ -> },
                onRemove = {},
                onRetry = {},
            )
        }

        compose.onNodeWithText("微信").assertIsDisplayed()
        compose.onNodeWithText("内部数据目录").performClick()
        compose.runOnIdle {
            assertEquals(candidate.path to "内部数据目录", opened)
        }
    }

    @Test
    fun emptyWechatAndLocationGroupsExposeUsefulState() {
        val common = direct("common.downloads", "下载", "/storage/emulated/0/Download", StorageLocation.Source.BUILT_IN)
        val custom = direct("custom.work", "我的工作", "/data/local/tmp/work", StorageLocation.Source.CUSTOM)
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(
                    loading = false,
                    appGroups = listOf(ResolvedAppLocation(LocationId.of("template.wechat"), "微信", emptyList(), 5)),
                    commonLocations = listOf(common),
                    customLocations = listOf(CustomLocationState(custom, LocationAvailability.Unavailable("路径不存在"))),
                ),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        compose.onNodeWithText("微信").assertIsDisplayed()
        compose.onNodeWithText("未找到可用微信目录").assertIsDisplayed()
        compose.onNodeWithText("下载").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("我的工作").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("路径不存在").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("添加位置").assertIsDisplayed()
    }

    @Test
    fun searchFiltersByDisplayedNames() {
        val work = direct("custom.work", "毕业资料", "/work", StorageLocation.Source.CUSTOM)
        val other = direct("custom.other", "旅行照片", "/photos", StorageLocation.Source.CUSTOM)
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(
                    loading = false,
                    customLocations = listOf(
                        CustomLocationState(work, LocationAvailability.Available(true, true)),
                        CustomLocationState(other, LocationAvailability.Available(true, true)),
                    ),
                ),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        compose.onNodeWithContentDescription("搜索文件").performTextInput("毕业")
        compose.onNodeWithText("毕业资料").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("旅行照片").assertDoesNotExist()
    }

    @Test
    fun gridModeUsesGridCellsAndKeepsTypedOpenCallback() {
        val downloads = direct("common.downloads", "下载", "/storage/emulated/0/Download", StorageLocation.Source.BUILT_IN)
        var opened: Pair<RootPath, String>? = null
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(loading = false, commonLocations = listOf(downloads)),
                displayMode = DisplayMode.GRID,
                onOpenLocation = { path, title -> opened = path to title },
                onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        compose.onNodeWithContentDescription("网格项：下载").performClick()
        compose.runOnIdle { assertEquals(downloads.path to downloads.displayName, opened) }
    }

    @Test
    fun gridKeepsAppCommonAndCustomItemsInsideTheirOwnSections() {
        val candidate = direct("wechat.media", "微信媒体", "/wechat", StorageLocation.Source.APP_TEMPLATE)
        val common = direct("common.downloads", "下载", "/download", StorageLocation.Source.BUILT_IN)
        val custom = direct("custom.work", "工作", "/work", StorageLocation.Source.CUSTOM)
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(
                    loading = false,
                    appGroups = listOf(ResolvedAppLocation(LocationId.of("template.wechat"), "微信", listOf(candidate), 0)),
                    commonLocations = listOf(common),
                    customLocations = listOf(CustomLocationState(custom, LocationAvailability.Available(true, true))),
                ),
                displayMode = DisplayMode.GRID,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        compose.onNodeWithTag("section-app").assertIsDisplayed()
        compose.onNodeWithTag("section-common").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("section-custom").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("网格项：微信媒体")
            .assert(hasAnyAncestor(hasTestTag("grid-app")))
        compose.onNodeWithContentDescription("网格项：下载")
            .assert(hasAnyAncestor(hasTestTag("grid-common")))
        compose.onNodeWithContentDescription("网格项：工作")
            .assert(hasAnyAncestor(hasTestTag("grid-custom")))
    }

    @Test
    fun checkingAndUnavailableCustomLocationsAreDisabledInListAndGrid() {
        val unavailable = direct("custom.missing", "失效视图", "/missing", StorageLocation.Source.CUSTOM)
        val checking = direct("custom.checking", "检查中视图", "/checking", StorageLocation.Source.CUSTOM)
        var opens = 0
        var mode by mutableStateOf(DisplayMode.LIST)
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(
                    loading = false,
                    customLocations = listOf(
                        CustomLocationState(unavailable, LocationAvailability.Unavailable("路径不存在")),
                        CustomLocationState(checking, LocationAvailability.Checking),
                    ),
                ),
                displayMode = mode,
                onOpenLocation = { _, _ -> opens += 1 }, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        compose.onNodeWithContentDescription("列表项：失效视图").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("列表项：检查中视图").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { mode = DisplayMode.GRID }
        compose.onNodeWithContentDescription("网格项：失效视图").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("网格项：检查中视图").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, opens) }
    }

    @Test
    fun addDialogTrimsRemarkButPreservesRawAbsolutePath() {
        var added: Pair<String, String>? = null
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(loading = false),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> },
                onAdd = { name, path -> added = name to path },
                onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        compose.onNodeWithText("添加位置").performClick()
        compose.onNodeWithText("备注名称").performTextInput("  工作  ")
        compose.onNodeWithText("绝对路径").performTextInput("/data/local/tmp/work/  ")
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals("工作" to "/data/local/tmp/work/  ", added) }
    }

    @Test
    fun dialogShowsViewModelErrorAndDisablesSubmissionDuringOperation() {
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(loading = false, addError = "该路径已存在", operationInProgress = true),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        compose.onNodeWithText("添加位置").performClick()
        compose.onNodeWithText("该路径已存在").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在保存位置").assertIsDisplayed()
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
    }

    @Test
    fun editAndRemoveUseViewSemanticsWithoutChangingThePath() {
        val custom = direct("custom.work", "工作视图", "/data/local/tmp/work//", StorageLocation.Source.CUSTOM)
        var edited: Triple<LocationId, String, String>? = null
        var removed: LocationId? = null
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(
                    loading = false,
                    customLocations = listOf(CustomLocationState(custom, LocationAvailability.Available(true, true))),
                ),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> },
                onEdit = { id, name, path -> edited = Triple(id, name, path) },
                onRemove = { removed = it }, onRetry = {},
            )
        }

        compose.onNodeWithContentDescription("编辑视图：工作视图").performScrollTo().performClick()
        compose.onNodeWithContentDescription("备注名称").performTextClearance()
        compose.onNodeWithContentDescription("备注名称").performTextInput("新工作")
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals(Triple(custom.id, "新工作", custom.path.value), edited) }

        compose.onNodeWithContentDescription("移除视图：工作视图").performScrollTo().performClick()
        compose.onNodeWithText("删除文件").assertDoesNotExist()
        compose.onNodeWithText("确认移除").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(custom.id, removed) }
    }

    @Test
    fun blankRemarkDoesNotSubmit() {
        var added: Pair<String, String>? = null
        compose.setContent {
            CustomLocationDialog(
                initialName = "",
                initialPath = "/data/local/tmp",
                error = null,
                operationInProgress = false,
                onConfirm = { name, path -> added = name to path },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("备注名称").performTextInput("   ")
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.runOnIdle { assertNull(added) }
    }

    private fun direct(
        id: String,
        name: String,
        path: String,
        source: StorageLocation.Source,
    ) = StorageLocation.Direct(
        id = LocationId.of(id),
        displayName = name,
        path = RootPath.parse(path).getOrThrow(),
        source = source,
    )
}
