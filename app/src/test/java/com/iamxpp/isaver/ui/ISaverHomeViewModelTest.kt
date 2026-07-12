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
    fun `browse source rejects any destination other than canonical root browser`() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeDestination.Browser(
                RootPath.parse("/not-root").getOrThrow(),
                "错误标题",
                HomeTab.BROWSE,
            )
        }
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
        val path = RootPath.parse("/data/user/0/com.tencent.mm").getOrThrow()

        viewModel.openAppCandidate(path, "微信内部数据")

        assertEquals(HomeDestination.Browser(path, "微信内部数据", HomeTab.VIEWS), viewModel.state.value.destination)
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
