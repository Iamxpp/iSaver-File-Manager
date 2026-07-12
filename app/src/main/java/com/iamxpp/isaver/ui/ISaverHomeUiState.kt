package com.iamxpp.isaver.ui

import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.HomeTab

sealed interface HomeDestination {
    data class Tab(val tab: HomeTab) : HomeDestination

    data class Browser(
        val path: RootPath,
        val title: String,
        val source: HomeTab,
    ) : HomeDestination
}

data class ISaverHomeUiState(
    val selectedTab: HomeTab = HomeTab.VIEWS,
    val destination: HomeDestination = HomeDestination.Tab(HomeTab.VIEWS),
    val recentIsEmpty: Boolean = true,
)
