package com.iamxpp.isaver.ui

import androidx.lifecycle.SavedStateHandle
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ISaverHomeViewModelTest {
    @Test
    fun `device page opens from views and returns to views`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())

        viewModel.openDevice()

        assertEquals(HomeDestination.Device, viewModel.state.value.destination)
        assertEquals(HomeTab.VIEWS, viewModel.state.value.selectedTab)

        viewModel.closeDevice()

        assertEquals(HomeDestination.Tab(HomeTab.VIEWS), viewModel.state.value.destination)
    }
    @Test
    fun `copy target reuses tabs and returns to its source browser`() {
        val source = HomeDestination.Browser(
            RootPath.parse("/data/local/tmp/source").getOrThrow(),
            "来源",
            HomeTab.BROWSE,
        )
        val target = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        viewModel.openLocation(source.path, source.title, source.source)

        viewModel.chooseCopyTarget()
        assertEquals(HomeTab.VIEWS, viewModel.state.value.selectedTab)
        assertEquals(HomeDestination.CopyTarget(source), viewModel.state.value.destination)

        viewModel.selectTab(HomeTab.BROWSE)
        viewModel.openLocation(target, "目标", HomeTab.BROWSE)
        val choosing = viewModel.state.value.destination as HomeDestination.CopyTarget
        assertEquals(HomeDestination.Browser(target, "目标", HomeTab.BROWSE), choosing.targetBrowser)

        val nestedTarget = RootPath.parse("${target.value}/nested").getOrThrow()
        viewModel.completeCopy(nestedTarget, "目标")
        assertEquals(
            HomeDestination.Browser(nestedTarget, "目标", HomeTab.BROWSE),
            viewModel.state.value.destination,
        )

        viewModel.openLocation(source.path, source.title, source.source)
        viewModel.chooseCopyTarget()
        viewModel.returnFromCopy()

        assertEquals(source, viewModel.state.value.destination)
        assertEquals(HomeTab.BROWSE, viewModel.state.value.selectedTab)
    }

    @Test
    fun `real target stays disabled until browser finishes loading the selected path`() {
        val selected = HomeDestination.Browser(
            RootPath.parse("/data/local/tmp/selected").getOrThrow(),
            "目标",
            HomeTab.VIEWS,
        )
        val staleBrowser = BrowserUiState(
            currentPath = RootPath.parse("/data/local/tmp/previous").getOrThrow(),
            canCreateDirectory = true,
        )
        val verifiedBrowser = staleBrowser.copy(currentPath = selected.path)

        assertEquals(false, canUseRealTarget(selected, staleBrowser))
        assertEquals(true, canUseRealTarget(selected, verifiedBrowser))
        assertEquals(false, canUseRealTarget(null, verifiedBrowser))
    }

    @Test
    fun `real target remains valid while browsing its verified descendants`() {
        val selected = HomeDestination.Browser(
            RootPath.parse("/storage/emulated/0/Download").getOrThrow(),
            "下载",
            HomeTab.VIEWS,
        )

        assertEquals(
            true,
            canUseRealTarget(
                selected,
                BrowserUiState(
                    currentPath = RootPath.parse("/storage/emulated/0/Download/nested").getOrThrow(),
                    canCreateDirectory = true,
                ),
            ),
        )
        assertEquals(
            false,
            canUseRealTarget(
                selected,
                BrowserUiState(
                    currentPath = RootPath.parse("/storage/emulated/0/Download-old").getOrThrow(),
                    canCreateDirectory = true,
                ),
            ),
        )
        assertEquals(
            false,
            canUseRealTarget(
                selected,
                BrowserUiState(
                    currentPath = RootPath.parse("/storage/emulated/0").getOrThrow(),
                    canCreateDirectory = true,
                ),
            ),
        )
    }

    @Test
    fun `move target reuses tabs and returns to its source browser`() {
        val source = HomeDestination.Browser(
            RootPath.parse("/data/local/tmp/source").getOrThrow(),
            "来源",
            HomeTab.BROWSE,
        )
        val target = RootPath.parse("/data/local/tmp/target").getOrThrow()
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        viewModel.openLocation(source.path, source.title, source.source)

        viewModel.chooseMoveTarget()
        assertEquals(HomeTab.VIEWS, viewModel.state.value.selectedTab)
        assertEquals(HomeDestination.MoveTarget(source), viewModel.state.value.destination)

        viewModel.selectTab(HomeTab.BROWSE)
        viewModel.openLocation(target, "目标", HomeTab.BROWSE)
        val choosing = viewModel.state.value.destination as HomeDestination.MoveTarget
        assertEquals(HomeDestination.Browser(target, "目标", HomeTab.BROWSE), choosing.targetBrowser)

        val nestedTarget = RootPath.parse("${target.value}/nested").getOrThrow()
        viewModel.completeMove(nestedTarget, "目标")
        assertEquals(
            HomeDestination.Browser(nestedTarget, "目标", HomeTab.BROWSE),
            viewModel.state.value.destination,
        )

        viewModel.openLocation(source.path, source.title, source.source)
        viewModel.chooseMoveTarget()

        viewModel.returnFromMove()
        assertEquals(source, viewModel.state.value.destination)
        assertEquals(HomeTab.BROWSE, viewModel.state.value.selectedTab)
    }

    @Test
    fun `process recreation safely cancels move target mode at the source browser`() {
        val handle = SavedStateHandle()
        val source = RootPath.parse("/data/local/tmp/source").getOrThrow()
        val viewModel = ISaverHomeViewModel(handle)
        viewModel.openLocation(source, "来源", HomeTab.VIEWS)
        viewModel.chooseMoveTarget()

        val restored = ISaverHomeViewModel(handle)

        assertEquals(HomeDestination.Browser(source, "来源", HomeTab.VIEWS), restored.state.value.destination)
        assertEquals(HomeTab.VIEWS, restored.state.value.selectedTab)
    }

    @Test
    fun `archive destination restores only root path name and source tab`() {
        val handle = SavedStateHandle()
        val source = RootPath.parse("/archives/backup.tar.gz").getOrThrow()
        ISaverHomeViewModel(handle).openArchive(source, "backup.tar.gz", HomeTab.RECENT)

        val restored = ISaverHomeViewModel(handle)

        assertEquals(
            HomeDestination.Archive(source, "backup.tar.gz", HomeTab.RECENT),
            restored.state.value.destination,
        )
        assertTrue(handle.keys().all { handle.get<Any>(it) is String })
    }

    @Test
    fun `extraction target destination survives recreation without cache paths`() {
        val handle = SavedStateHandle()
        val source = RootPath.parse("/archives/a.zip").getOrThrow()
        val viewModel = ISaverHomeViewModel(handle)
        viewModel.openArchive(source, "a.zip", HomeTab.BROWSE)

        viewModel.chooseExtractionTarget()
        val restored = ISaverHomeViewModel(handle)

        assertEquals(HomeTab.VIEWS, restored.state.value.selectedTab)
        assertEquals(
            HomeDestination.ExtractionTarget(source, "a.zip", HomeTab.BROWSE),
            restored.state.value.destination,
        )
        assertTrue(handle.keys().mapNotNull { handle.get<String>(it) }.none { "cache" in it })
    }

    @Test
    fun `extraction target keeps archive identity while tabs and target browser change`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        val source = RootPath.parse("/archives/a.zip").getOrThrow()
        val target = RootPath.parse("/data/local/tmp/output").getOrThrow()
        viewModel.openArchive(source, "a.zip", HomeTab.RECENT)
        viewModel.chooseExtractionTarget()

        viewModel.selectTab(HomeTab.BROWSE)
        assertTrue((viewModel.state.value.destination as HomeDestination.ExtractionTarget).targetBrowser != null)
        viewModel.selectTab(HomeTab.VIEWS)
        viewModel.openLocation(target, "输出", HomeTab.VIEWS)

        val choosing = viewModel.state.value.destination as HomeDestination.ExtractionTarget
        assertEquals(source, choosing.source)
        assertEquals(HomeDestination.Browser(target, "输出", HomeTab.VIEWS), choosing.targetBrowser)
        viewModel.returnToArchive()
        assertEquals(HomeDestination.Archive(source, "a.zip", HomeTab.RECENT), viewModel.state.value.destination)
    }

    @Test
    fun `extraction target recent and views tabs keep no hidden browser target`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        val source = RootPath.parse("/archives/a.zip").getOrThrow()
        viewModel.openArchive(source, "a.zip", HomeTab.BROWSE)
        viewModel.chooseExtractionTarget()
        viewModel.selectTab(HomeTab.BROWSE)

        viewModel.selectTab(HomeTab.RECENT)
        val recent = viewModel.state.value.destination as HomeDestination.ExtractionTarget
        assertEquals(HomeTab.RECENT, viewModel.state.value.selectedTab)
        assertEquals(null, recent.targetBrowser)

        viewModel.selectTab(HomeTab.VIEWS)
        val views = viewModel.state.value.destination as HomeDestination.ExtractionTarget
        assertEquals(HomeTab.VIEWS, viewModel.state.value.selectedTab)
        assertEquals(null, views.targetBrowser)
        assertEquals(source, views.source)
    }

    @Test
    fun `restores a custom browser destination from saved primitive state`() {
        val handle = SavedStateHandle()
        val path = RootPath.parse("/data/local/tmp/custom").getOrThrow()
        ISaverHomeViewModel(handle).openLocation(path, "自定义备注")

        val restored = ISaverHomeViewModel(handle)

        assertEquals(HomeTab.VIEWS, restored.state.value.selectedTab)
        assertEquals(
            HomeDestination.Browser(path, "自定义备注", HomeTab.VIEWS),
            restored.state.value.destination,
        )
        handle.keys().forEach { key ->
            assert(handle.get<Any>(key) is String)
        }
    }

    @Test
    fun `invalid restored root path falls back to views and clears corrupt state`() {
        val handle = SavedStateHandle(
            mapOf(
                "home.selectedTab" to "VIEWS",
                "home.destination" to "BROWSER",
                "home.path" to "relative/path",
                "home.title" to "损坏",
                "home.source" to "VIEWS",
                "unrelated" to "keep",
            ),
        )

        val restored = ISaverHomeViewModel(handle)

        assertEquals(HomeTab.VIEWS, restored.state.value.selectedTab)
        assertEquals(HomeDestination.Tab(HomeTab.VIEWS), restored.state.value.destination)
        assertEquals(setOf("unrelated"), handle.keys())
        assertEquals("keep", handle.get<String>("unrelated"))
    }

    @Test
    fun `unknown restored enum falls back to views and clears corrupt state`() {
        val handle = SavedStateHandle(
            mapOf(
                "home.selectedTab" to "UNKNOWN",
                "home.destination" to "TAB",
            ),
        )

        val restored = ISaverHomeViewModel(handle)

        assertEquals(ISaverHomeUiState(), restored.state.value)
        assertTrue(handle.keys().isEmpty())
    }

    @Test
    fun `wrong saved primitive types fall back and clear only home state`() {
        val handle = SavedStateHandle(
            mapOf(
                "home.selectedTab" to 7,
                "home.destination" to true,
                "home.path" to false,
                "home.title" to 99,
                "home.source" to 3,
                "unrelated" to "keep",
            ),
        )

        val restored = ISaverHomeViewModel(handle)

        assertEquals(ISaverHomeUiState(), restored.state.value)
        assertEquals(setOf("unrelated"), handle.keys())
        assertEquals("keep", handle.get<String>("unrelated"))
    }

    @Test
    fun `restores the canonical browse root after recreation`() {
        val handle = SavedStateHandle()
        ISaverHomeViewModel(handle).selectTab(HomeTab.BROWSE)

        val restored = ISaverHomeViewModel(handle)

        assertEquals(HomeTab.BROWSE, restored.state.value.selectedTab)
        assertEquals(
            HomeDestination.Browser(RootPath.parse("/").getOrThrow(), "浏览", HomeTab.BROWSE),
            restored.state.value.destination,
        )
    }

    @Test
    fun `tab destination rejects browse because browse always owns the root browser`() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeDestination.Tab(HomeTab.BROWSE)
        }
    }

    @Test
    fun `browse source preserves and restores an extracted output directory`() {
        val handle = SavedStateHandle()
        val output = RootPath.parse("/data/local/tmp/isaver-test/ui-flow/sample").getOrThrow()
        ISaverHomeViewModel(handle).openLocation(
            output,
            "sample",
            HomeTab.BROWSE,
            recordAccess = false,
        )

        val restored = ISaverHomeViewModel(handle)

        assertEquals(HomeTab.BROWSE, restored.state.value.selectedTab)
        assertEquals(
            HomeDestination.Browser(output, "sample", HomeTab.BROWSE, recordAccess = false),
            restored.state.value.destination,
        )
    }

    @Test
    fun `defaults to views tab`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())

        assertEquals(HomeTab.VIEWS, viewModel.state.value.selectedTab)
        assertEquals(HomeDestination.Tab(HomeTab.VIEWS), viewModel.state.value.destination)
        assertEquals(true, viewModel.state.value.recentIsEmpty)
    }

    @Test
    fun `selecting a regular tab replaces the current tab destination`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())

        viewModel.selectTab(HomeTab.RECENT)

        assertEquals(HomeTab.RECENT, viewModel.state.value.selectedTab)
        assertEquals(HomeDestination.Tab(HomeTab.RECENT), viewModel.state.value.destination)
    }

    @Test
    fun `selecting views returns from another tab to the views destination`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        viewModel.selectTab(HomeTab.RECENT)

        viewModel.selectTab(HomeTab.VIEWS)

        assertEquals(HomeTab.VIEWS, viewModel.state.value.selectedTab)
        assertEquals(HomeDestination.Tab(HomeTab.VIEWS), viewModel.state.value.destination)
    }

    @Test
    fun `selecting browse opens the filesystem root`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())

        viewModel.selectTab(HomeTab.BROWSE)

        assertEquals(HomeTab.BROWSE, viewModel.state.value.selectedTab)
        assertEquals(
            HomeDestination.Browser(
                path = RootPath.parse("/").getOrThrow(),
                title = "浏览",
                source = HomeTab.BROWSE,
            ),
            viewModel.state.value.destination,
        )
    }

    @Test
    fun `opening a view location preserves its path and uses its remark as title`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        val path = RootPath.parse("/data/local/tmp/original").getOrThrow()

        viewModel.openLocation(path, "我的备注")

        assertEquals(HomeTab.VIEWS, viewModel.state.value.selectedTab)
        assertEquals(HomeDestination.Browser(path, "我的备注", HomeTab.VIEWS), viewModel.state.value.destination)
    }

    @Test
    fun `changing a location remark updates only the browser title`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        val path = RootPath.parse("/same/path").getOrThrow()
        viewModel.openLocation(path, "旧备注")

        viewModel.openLocation(path, "新备注")

        assertEquals(HomeDestination.Browser(path, "新备注", HomeTab.VIEWS), viewModel.state.value.destination)
    }

    @Test
    fun `opening an app candidate uses the candidate title without rewriting its path`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        val path = RootPath.parse("/data/user/0/example.app").getOrThrow()

        viewModel.openAppCandidate(path, "应用内部数据")

        assertEquals(HomeDestination.Browser(path, "应用内部数据", HomeTab.VIEWS), viewModel.state.value.destination)
    }

    @Test
    fun `browse root back requests app exit and preserves canonical root browser`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        viewModel.selectTab(HomeTab.BROWSE)
        val before = viewModel.state.value

        val result = viewModel.onBrowserBack(BrowserBackResult.RETURN_HOME)

        assertEquals(HomeBackResult.EXIT_APP, result)
        assertEquals(before, viewModel.state.value)
    }

    @Test
    fun `view browser root back returns to views`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        viewModel.openLocation(RootPath.parse("/custom/path").getOrThrow(), "备注")

        val result = viewModel.onBrowserBack(BrowserBackResult.RETURN_HOME)

        assertEquals(HomeBackResult.CONSUMED, result)
        assertEquals(HomeDestination.Tab(HomeTab.VIEWS), viewModel.state.value.destination)
    }

    @Test
    fun `browser back on a tab destination is a no-op`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        val before = viewModel.state.value

        val result = viewModel.onBrowserBack(BrowserBackResult.RETURN_HOME)

        assertEquals(HomeBackResult.CONSUMED, result)
        assertEquals(before, viewModel.state.value)
    }

    @Test
    fun `browser navigated back keeps the browser destination`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        val path = RootPath.parse("/custom/path").getOrThrow()
        viewModel.openLocation(path, "备注")
        val before = viewModel.state.value

        val result = viewModel.onBrowserBack(BrowserBackResult.NAVIGATED)

        assertEquals(HomeBackResult.CONSUMED, result)
        assertEquals(before, viewModel.state.value)
    }

    @Test
    fun `switching tab cancels an open browser destination`() {
        val viewModel = ISaverHomeViewModel(SavedStateHandle())
        viewModel.openLocation(RootPath.parse("/custom/path").getOrThrow(), "备注")

        viewModel.selectTab(HomeTab.RECENT)

        assertEquals(HomeDestination.Tab(HomeTab.RECENT), viewModel.state.value.destination)
    }
}
