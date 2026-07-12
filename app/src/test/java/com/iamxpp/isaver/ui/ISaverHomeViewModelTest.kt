package com.iamxpp.isaver.ui

import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Test

class ISaverHomeViewModelTest {
    @Test
    fun `defaults to views tab`() {
        val viewModel = ISaverHomeViewModel()

        assertEquals(HomeTab.VIEWS, viewModel.state.value.selectedTab)
        assertEquals(HomeDestination.Tab(HomeTab.VIEWS), viewModel.state.value.destination)
        assertEquals(true, viewModel.state.value.recentIsEmpty)
    }

    @Test
    fun `selecting a regular tab replaces the current tab destination`() {
        val viewModel = ISaverHomeViewModel()

        viewModel.selectTab(HomeTab.RECENT)

        assertEquals(HomeTab.RECENT, viewModel.state.value.selectedTab)
        assertEquals(HomeDestination.Tab(HomeTab.RECENT), viewModel.state.value.destination)
    }

    @Test
    fun `selecting browse opens the filesystem root`() {
        val viewModel = ISaverHomeViewModel()

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
        val viewModel = ISaverHomeViewModel()
        val path = RootPath.parse("/data/local/tmp/original").getOrThrow()

        viewModel.openLocation(path, "我的备注")

        assertEquals(HomeTab.VIEWS, viewModel.state.value.selectedTab)
        assertEquals(HomeDestination.Browser(path, "我的备注", HomeTab.VIEWS), viewModel.state.value.destination)
    }

    @Test
    fun `opening an app candidate uses the candidate title without rewriting its path`() {
        val viewModel = ISaverHomeViewModel()
        val path = RootPath.parse("/data/user/0/com.tencent.mm").getOrThrow()

        viewModel.openAppCandidate(path, "微信内部数据")

        assertEquals(HomeDestination.Browser(path, "微信内部数据", HomeTab.VIEWS), viewModel.state.value.destination)
    }

    @Test
    fun `browser return home restores its source tab`() {
        val viewModel = ISaverHomeViewModel()
        viewModel.selectTab(HomeTab.BROWSE)

        viewModel.onBrowserBack(BrowserBackResult.RETURN_HOME)

        assertEquals(HomeTab.BROWSE, viewModel.state.value.selectedTab)
        assertEquals(HomeDestination.Tab(HomeTab.BROWSE), viewModel.state.value.destination)
    }

    @Test
    fun `browser navigated back keeps the browser destination`() {
        val viewModel = ISaverHomeViewModel()
        val path = RootPath.parse("/custom/path").getOrThrow()
        viewModel.openLocation(path, "备注")
        val before = viewModel.state.value

        viewModel.onBrowserBack(BrowserBackResult.NAVIGATED)

        assertEquals(before, viewModel.state.value)
    }

    @Test
    fun `switching tab cancels an open browser destination`() {
        val viewModel = ISaverHomeViewModel()
        viewModel.openLocation(RootPath.parse("/custom/path").getOrThrow(), "备注")

        viewModel.selectTab(HomeTab.RECENT)

        assertEquals(HomeDestination.Tab(HomeTab.RECENT), viewModel.state.value.destination)
    }
}
