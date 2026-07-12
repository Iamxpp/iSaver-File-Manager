package com.iamxpp.isaver.ui

import androidx.lifecycle.ViewModel
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.HomeTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ISaverHomeViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ISaverHomeUiState())

    val state: StateFlow<ISaverHomeUiState> = mutableState.asStateFlow()

    fun selectTab(tab: HomeTab) {
        mutableState.value = mutableState.value.copy(
            selectedTab = tab,
            destination = if (tab == HomeTab.BROWSE) {
                HomeDestination.Browser(BROWSE_ROOT, BROWSE_TITLE, HomeTab.BROWSE)
            } else {
                HomeDestination.Tab(tab)
            },
        )
    }

    fun openLocation(path: RootPath, displayName: String, source: HomeTab = HomeTab.VIEWS) {
        mutableState.value = mutableState.value.copy(
            selectedTab = source,
            destination = HomeDestination.Browser(path, displayName, source),
        )
    }

    fun openAppCandidate(path: RootPath, displayName: String) {
        openLocation(path, displayName, HomeTab.VIEWS)
    }

    fun onBrowserBack(result: BrowserBackResult) {
        if (result != BrowserBackResult.RETURN_HOME) return
        val browser = mutableState.value.destination as? HomeDestination.Browser ?: return
        mutableState.value = mutableState.value.copy(
            selectedTab = browser.source,
            destination = HomeDestination.Tab(browser.source),
        )
    }

    private companion object {
        val BROWSE_ROOT = RootPath.parse("/").getOrThrow()
        const val BROWSE_TITLE = "浏览"
    }
}
