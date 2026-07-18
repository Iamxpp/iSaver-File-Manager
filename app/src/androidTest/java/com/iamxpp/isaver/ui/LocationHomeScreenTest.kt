package com.iamxpp.isaver.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.locations.ResolvedAppLocation
import com.iamxpp.isaver.locations.StorageLocation
import com.iamxpp.isaver.ui.files.DisplayMode
import com.iamxpp.isaver.ui.files.FilesSaveAction
import com.iamxpp.isaver.ui.files.SortDirection
import com.iamxpp.isaver.ui.files.SortField
import com.iamxpp.isaver.ui.files.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun compactHeaderAlignsTitleAndOverflowThenPlacesSearchWithoutInlineAddButton() {
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(loading = false),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> },
                onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        val topBar = compose.onNodeWithTag("views-top-bar").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("files-top-bar-title").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val overflow = compose.onNodeWithTag("files-top-bar-overflow").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("views-search").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val firstSection = compose.onNodeWithText("应用位置").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot

        assertEquals(topBar.center.x, title.center.x, 1f)
        assertTrue(title.top < overflow.bottom && overflow.top < title.bottom)
        assertEquals(topBar.bottom, search.top, 1f)
        assertTrue(firstSection.top - search.bottom < search.height)
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithText("添加位置").assertDoesNotExist()
    }

    @Test
    fun saveModeReplacesViewsOverflowWithDisabledSave() {
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(loading = false),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> },
                onAdd = { _, _ -> },
                onEdit = { _, _, _ -> },
                onRemove = {},
                onRetry = {},
                saveAction = FilesSaveAction(enabled = false, onSave = {}),
            )
        }

        compose.onNodeWithTag("files-top-bar-save").assertIsNotEnabled()
        compose.onNodeWithTag("files-top-bar-overflow").assertDoesNotExist()
    }

    @Test
    fun gridUsesTheSameCompactHeader() {
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(loading = false),
                displayMode = DisplayMode.GRID,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> },
                onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        compose.onNodeWithTag("views-top-bar").assertIsDisplayed()
        compose.onNodeWithTag("files-top-bar-title").assertIsDisplayed()
        compose.onNodeWithTag("files-top-bar-overflow").assertIsDisplayed()
        compose.onNodeWithTag("views-search").assertIsDisplayed()
        compose.onNodeWithText("添加位置").assertDoesNotExist()
    }

    @Test
    fun viewsOverflowAddsLocationAndForwardsPresentationActions() {
        var mode: DisplayMode? = null
        var sort: SortSpec? = null
        val currentSort = SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING)
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(loading = false),
                displayMode = DisplayMode.LIST,
                sortSpec = currentSort,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> },
                onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
                onDisplayModeChange = { mode = it },
                onSortChange = { sort = it },
            )
        }

        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithTag("views-add-location-menu").assertIsDisplayed()
        compose.onNodeWithText("图标").performClick()
        compose.runOnIdle { assertEquals(DisplayMode.GRID, mode) }

        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithText("日期").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(currentSort.copy(field = SortField.MODIFIED_AT), sort) }

        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithTag("views-add-location-menu").performClick()
        compose.onNodeWithContentDescription("备注名称").assertIsDisplayed()
    }

    @Test
    fun viewsOverflowMenuIsAnchoredToTheRightActionSlot() {
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(loading = false),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> },
                onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        val topBar = compose.onNodeWithTag("views-top-bar")
            .fetchSemanticsNode()
            .boundsOnScreen()
        val overflow = compose.onNodeWithTag("files-top-bar-overflow")
            .fetchSemanticsNode()
            .boundsOnScreen()
        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        val addCommand = compose.onNodeWithTag("views-add-location-menu")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsOnScreen()

        assertTrue("command=$addCommand topBar=$topBar", addCommand.center.x > topBar.center.x)
        assertEquals(overflow.right, addCommand.right, 1f)
        assertTrue("command=$addCommand overflow=$overflow", addCommand.top - overflow.bottom < addCommand.height)
    }

    @Test
    fun viewsNameSortUsesCandidateDisplayNamesAndCustomRemarksInBothDirections() {
        val alphaCandidate = direct("app.alpha", "Alpha候选", "/app/alpha", StorageLocation.Source.APP_TEMPLATE)
        val zuluCandidate = direct("app.zulu", "Zulu候选", "/app/zulu", StorageLocation.Source.APP_TEMPLATE)
        val alphaCustom = direct("custom.alpha", "Alpha备注", "/custom/alpha", StorageLocation.Source.CUSTOM)
        val zuluCustom = direct("custom.zulu", "Zulu备注", "/custom/zulu", StorageLocation.Source.CUSTOM)
        var sortSpec by mutableStateOf(SortSpec(SortField.DISPLAY_NAME, SortDirection.ASCENDING))
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(
                    loading = false,
                    appGroups = listOf(
                        ResolvedAppLocation(
                            templateId = LocationId.of("template.app"),
                            displayName = "应用",
                            children = listOf(zuluCandidate, alphaCandidate),
                            unavailableCount = 0,
                        ),
                    ),
                    customLocations = listOf(
                        CustomLocationState(zuluCustom, LocationAvailability.Available(true, true)),
                        CustomLocationState(alphaCustom, LocationAvailability.Available(true, true)),
                    ),
                ),
                displayMode = DisplayMode.LIST,
                sortSpec = sortSpec,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> },
                onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
                onSortChange = { sortSpec = it },
            )
        }

        assertListItemBefore("Alpha候选", "Zulu候选")
        assertListItemBefore("Alpha备注", "Zulu备注")
        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithText("升序").performScrollTo().performClick()
        assertListItemBefore("Zulu候选", "Alpha候选")
        assertListItemBefore("Zulu备注", "Alpha备注")
    }

    @Test
    fun viewsTypeSortUsesLogicalSectionTypesInBothDirections() {
        val candidate = direct("app.one", "应用目录", "/app", StorageLocation.Source.APP_TEMPLATE)
        val common = direct("common.one", "通用目录", "/common", StorageLocation.Source.BUILT_IN)
        val custom = direct("custom.one", "自定义目录", "/custom", StorageLocation.Source.CUSTOM)
        var sortSpec by mutableStateOf(SortSpec(SortField.TYPE, SortDirection.ASCENDING))
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(
                    loading = false,
                    appGroups = listOf(
                        ResolvedAppLocation(LocationId.of("template.app"), "应用", listOf(candidate), 0),
                    ),
                    commonLocations = listOf(common),
                    customLocations = listOf(
                        CustomLocationState(custom, LocationAvailability.Available(true, true)),
                    ),
                ),
                displayMode = DisplayMode.LIST,
                sortSpec = sortSpec,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> },
                onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
                onSortChange = { sortSpec = it },
            )
        }

        assertTextBefore("应用位置", "通用位置")
        assertTextBefore("通用位置", "自定义位置")
        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithText("升序").performScrollTo().performClick()
        assertTextBefore("自定义位置", "通用位置")
        assertTextBefore("通用位置", "应用位置")
    }

    @Test
    fun viewsUnknownDateAndSizeUseNameFallbackInBothDirections() {
        val alpha = direct("common.alpha", "Alpha位置", "/common/alpha", StorageLocation.Source.BUILT_IN)
        val zulu = direct("common.zulu", "Zulu位置", "/common/zulu", StorageLocation.Source.BUILT_IN)
        var sortSpec by mutableStateOf(SortSpec(SortField.MODIFIED_AT, SortDirection.ASCENDING))
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(
                    loading = false,
                    commonLocations = listOf(zulu, alpha),
                ),
                displayMode = DisplayMode.LIST,
                sortSpec = sortSpec,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> },
                onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        listOf(SortField.MODIFIED_AT, SortField.SIZE).forEach { field ->
            compose.runOnIdle { sortSpec = SortSpec(field, SortDirection.ASCENDING) }
            assertListItemBefore("Alpha位置", "Zulu位置")
            compose.runOnIdle { sortSpec = SortSpec(field, SortDirection.DESCENDING) }
            assertListItemBefore("Zulu位置", "Alpha位置")
        }
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
        compose.onNodeWithText("添加位置").assertDoesNotExist()
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
    fun largeGridUsesOneLazyCollectionAndScrollsToLastCustomLocation() {
        val custom = (1..60).map { index ->
            val location = direct("custom.$index", "自定义$index", "/custom/$index", StorageLocation.Source.CUSTOM)
            CustomLocationState(location, LocationAvailability.Available(true, true))
        }
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(loading = false, customLocations = custom),
                displayMode = DisplayMode.GRID,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        val collections = compose.onAllNodes(
            matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals(1, collections.size)
        compose.onNodeWithTag("location-home-grid")
            .performScrollToNode(hasContentDescription("网格项：自定义60"))
        compose.onNodeWithContentDescription("网格项：自定义60").assertIsDisplayed()
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

        openAddLocationDialog()
        compose.onNodeWithText("备注名称").performTextInput("  工作  ")
        compose.onNodeWithText("绝对路径").performTextInput("/data/local/tmp/work/  ")
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals("工作" to "/data/local/tmp/work/  ", added) }
    }

    @Test
    fun successfulSaveClosesDialogAndReopenStartsClean() {
        var state by mutableStateOf(LocationHomeUiState(loading = false))
        compose.setContent {
            LocationHomeScreen(
                state = state,
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> },
                onAdd = { _, _ -> state = state.copy(saveSuccessVersion = state.saveSuccessVersion + 1) },
                onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
            )
        }

        openAddLocationDialog()
        compose.onNodeWithContentDescription("备注名称").performTextInput("草稿")
        compose.onNodeWithContentDescription("绝对路径").performTextInput("/data/local/tmp/draft")
        compose.onNodeWithText("确定").performClick()

        compose.onNodeWithContentDescription("备注名称").assertDoesNotExist()
        openAddLocationDialog()
        compose.onNodeWithText("草稿").assertDoesNotExist()
        compose.onNodeWithText("/data/local/tmp/draft").assertDoesNotExist()
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

        openAddLocationDialog()
        compose.onNodeWithText("该路径已存在").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在保存位置").assertIsDisplayed()
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
    }

    @Test
    fun openingAndDismissingDialogClearStaleError() {
        var state by mutableStateOf(LocationHomeUiState(loading = false, addError = "旧错误"))
        var clearCalls = 0
        compose.setContent {
            LocationHomeScreen(
                state = state,
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onRemove = {}, onRetry = {},
                onClearAddError = { clearCalls += 1; state = state.copy(addError = null) },
            )
        }

        openAddLocationDialog()
        compose.onNodeWithText("旧错误").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()

        compose.runOnIdle { assertEquals(2, clearCalls) }
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
    fun protectedReadonlyLocationShowsWarningAndSupportsSingleRevalidation() {
        val custom = direct("custom.system", "系统目录", "/system/etc", StorageLocation.Source.CUSTOM)
        var revalidated: LocationId? = null
        compose.setContent {
            LocationHomeScreen(
                state = LocationHomeUiState(
                    loading = false,
                    customLocations = listOf(CustomLocationState(custom, LocationAvailability.Available(true, false))),
                ),
                displayMode = DisplayMode.LIST,
                onOpenLocation = { _, _ -> }, onAdd = { _, _ -> }, onEdit = { _, _, _ -> },
                onRemove = {}, onRetry = {}, onRevalidate = { revalidated = it },
            )
        }

        compose.onNodeWithText("系统保护区域 · 只读").assertIsDisplayed()
        compose.onNodeWithContentDescription("重新校验视图：系统目录").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(custom.id, revalidated) }
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

    private fun openAddLocationDialog() {
        compose.onNodeWithTag("files-top-bar-overflow").performClick()
        compose.onNodeWithTag("views-add-location-menu").performClick()
    }

    private fun assertListItemBefore(first: String, second: String) {
        val firstTop = compose.onNodeWithContentDescription("列表项：$first")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val secondTop = compose.onNodeWithContentDescription("列表项：$second")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertTrue("Expected $first before $second, tops=$firstTop/$secondTop", firstTop < secondTop)
    }

    private fun assertTextBefore(first: String, second: String) {
        val firstTop = compose.onNodeWithText(first)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val secondTop = compose.onNodeWithText(second)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertTrue("Expected $first before $second, tops=$firstTop/$secondTop", firstTop < secondTop)
    }
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
