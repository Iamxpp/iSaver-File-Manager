package com.iamxpp.isaver.ui

import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.HomeTab

sealed interface HomeDestination {
    data class Tab(val tab: HomeTab) : HomeDestination {
        init {
            require(tab != HomeTab.BROWSE) { "Browse must use the root browser destination" }
        }
    }

    data class Browser(
        val path: RootPath,
        val title: String,
        val source: HomeTab,
        val recordAccess: Boolean = true,
    ) : HomeDestination

    data class Archive(
        val source: RootPath,
        val sourceName: String,
        val sourceTab: HomeTab,
    ) : HomeDestination

    data class ExtractionTarget(
        val source: RootPath,
        val sourceName: String,
        val sourceTab: HomeTab,
        val targetBrowser: Browser? = null,
    ) : HomeDestination

    data class MoveTarget(
        val sourceBrowser: Browser,
        val targetBrowser: Browser? = null,
    ) : HomeDestination

    data class CopyTarget(
        val sourceBrowser: Browser,
        val targetBrowser: Browser? = null,
    ) : HomeDestination
}

data class ISaverHomeUiState(
    val selectedTab: HomeTab = HomeTab.VIEWS,
    val destination: HomeDestination = HomeDestination.Tab(HomeTab.VIEWS),
    val recentIsEmpty: Boolean = true,
)

enum class HomeBackResult {
    CONSUMED,
    EXIT_APP,
}
