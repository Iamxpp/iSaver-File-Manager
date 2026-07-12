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
    ) : HomeDestination {
        init {
            require(source != HomeTab.BROWSE || path.value == "/" && title == "浏览") {
                "Browse must use the canonical root browser destination"
            }
        }
    }
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
