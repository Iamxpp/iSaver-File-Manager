package com.iamxpp.isaver.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.HomeTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ISaverHomeViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(restoreState())

    val state: StateFlow<ISaverHomeUiState> = mutableState.asStateFlow()

    fun selectTab(tab: HomeTab) {
        transition(
            mutableState.value.copy(
                selectedTab = tab,
                destination = if (tab == HomeTab.BROWSE) {
                    HomeDestination.Browser(BROWSE_ROOT, BROWSE_TITLE, HomeTab.BROWSE)
                } else {
                    HomeDestination.Tab(tab)
                },
            ),
        )
    }

    fun openLocation(path: RootPath, displayName: String, source: HomeTab = HomeTab.VIEWS) {
        transition(
            mutableState.value.copy(
                selectedTab = source,
                destination = HomeDestination.Browser(path, displayName, source),
            ),
        )
    }

    fun openAppCandidate(path: RootPath, displayName: String) {
        openLocation(path, displayName, HomeTab.VIEWS)
    }

    fun openArchive(source: RootPath, sourceName: String, sourceTab: HomeTab) {
        transition(
            mutableState.value.copy(
                selectedTab = sourceTab,
                destination = HomeDestination.Archive(source, sourceName, sourceTab),
            ),
        )
    }

    fun chooseExtractionTarget() {
        val archive = mutableState.value.destination as? HomeDestination.Archive ?: return
        transition(
            mutableState.value.copy(
                selectedTab = HomeTab.VIEWS,
                destination = HomeDestination.ExtractionTarget(
                    archive.source,
                    archive.sourceName,
                    archive.sourceTab,
                ),
            ),
        )
    }

    fun onBrowserBack(result: BrowserBackResult): HomeBackResult {
        if (result != BrowserBackResult.RETURN_HOME) return HomeBackResult.CONSUMED
        val browser = mutableState.value.destination as? HomeDestination.Browser ?: return HomeBackResult.CONSUMED
        if (browser.source == HomeTab.BROWSE) return HomeBackResult.EXIT_APP
        transition(
            mutableState.value.copy(
                selectedTab = browser.source,
                destination = HomeDestination.Tab(browser.source),
            ),
        )
        return HomeBackResult.CONSUMED
    }

    private fun transition(state: ISaverHomeUiState) {
        mutableState.value = state
        savedStateHandle[KEY_SELECTED_TAB] = state.selectedTab.name
        when (val destination = state.destination) {
            is HomeDestination.Tab -> {
                savedStateHandle[KEY_DESTINATION] = DESTINATION_TAB
                savedStateHandle.remove<String>(KEY_PATH)
                savedStateHandle.remove<String>(KEY_TITLE)
                savedStateHandle.remove<String>(KEY_SOURCE)
            }
            is HomeDestination.Browser -> {
                savedStateHandle[KEY_DESTINATION] = DESTINATION_BROWSER
                savedStateHandle[KEY_PATH] = destination.path.value
                savedStateHandle[KEY_TITLE] = destination.title
                savedStateHandle[KEY_SOURCE] = destination.source.name
            }
            is HomeDestination.Archive -> saveArchiveDestination(
                DESTINATION_ARCHIVE,
                destination.source,
                destination.sourceName,
                destination.sourceTab,
            )
            is HomeDestination.ExtractionTarget -> saveArchiveDestination(
                DESTINATION_EXTRACTION_TARGET,
                destination.source,
                destination.sourceName,
                destination.sourceTab,
            )
        }
    }

    private fun restoreState(): ISaverHomeUiState {
        if (savedStateHandle.keys().isEmpty()) return ISaverHomeUiState()
        return try {
            val selected = HomeTab.valueOf(savedStateHandle.get<String>(KEY_SELECTED_TAB).orEmpty())
            when (savedStateHandle.get<String>(KEY_DESTINATION)) {
                DESTINATION_TAB -> ISaverHomeUiState(selected, HomeDestination.Tab(selected))
                DESTINATION_BROWSER -> {
                    val path = RootPath.parse(savedStateHandle.get<String>(KEY_PATH).orEmpty()).getOrThrow()
                    val title = savedStateHandle.get<String>(KEY_TITLE).orEmpty()
                    val source = HomeTab.valueOf(savedStateHandle.get<String>(KEY_SOURCE).orEmpty())
                    require(selected == source) { "Selected tab must match browser source" }
                    if (source == HomeTab.BROWSE) {
                        require(path == BROWSE_ROOT && title == BROWSE_TITLE) { "Invalid browse root state" }
                    }
                    ISaverHomeUiState(selected, HomeDestination.Browser(path, title, source))
                }
                DESTINATION_ARCHIVE -> {
                    val source = restoredPath()
                    val sourceName = savedStateHandle.get<String>(KEY_TITLE).orEmpty()
                    val sourceTab = restoredSourceTab()
                    require(selected == sourceTab) { "Selected tab must match archive source" }
                    ISaverHomeUiState(selected, HomeDestination.Archive(source, sourceName, sourceTab))
                }
                DESTINATION_EXTRACTION_TARGET -> {
                    val source = restoredPath()
                    val sourceName = savedStateHandle.get<String>(KEY_TITLE).orEmpty()
                    val sourceTab = restoredSourceTab()
                    ISaverHomeUiState(
                        selected,
                        HomeDestination.ExtractionTarget(source, sourceName, sourceTab),
                    )
                }
                else -> throw IllegalArgumentException("Unknown home destination")
            }
        } catch (_: IllegalArgumentException) {
            clearSavedState()
            ISaverHomeUiState()
        } catch (_: ClassCastException) {
            clearSavedState()
            ISaverHomeUiState()
        }
    }

    private fun saveArchiveDestination(
        kind: String,
        source: RootPath,
        sourceName: String,
        sourceTab: HomeTab,
    ) {
        savedStateHandle[KEY_DESTINATION] = kind
        savedStateHandle[KEY_PATH] = source.value
        savedStateHandle[KEY_TITLE] = sourceName
        savedStateHandle[KEY_SOURCE] = sourceTab.name
    }

    private fun restoredPath(): RootPath =
        RootPath.parse(savedStateHandle.get<String>(KEY_PATH).orEmpty()).getOrThrow()

    private fun restoredSourceTab(): HomeTab =
        HomeTab.valueOf(savedStateHandle.get<String>(KEY_SOURCE).orEmpty())

    private fun clearSavedState() {
        SAVED_STATE_KEYS.forEach { key -> savedStateHandle.remove<Any>(key) }
    }

    private companion object {
        val BROWSE_ROOT = RootPath.parse("/").getOrThrow()
        const val BROWSE_TITLE = "浏览"
        const val KEY_SELECTED_TAB = "home.selectedTab"
        const val KEY_DESTINATION = "home.destination"
        const val KEY_PATH = "home.path"
        const val KEY_TITLE = "home.title"
        const val KEY_SOURCE = "home.source"
        const val DESTINATION_TAB = "TAB"
        const val DESTINATION_BROWSER = "BROWSER"
        const val DESTINATION_ARCHIVE = "ARCHIVE"
        const val DESTINATION_EXTRACTION_TARGET = "EXTRACTION_TARGET"
        val SAVED_STATE_KEYS = listOf(KEY_SELECTED_TAB, KEY_DESTINATION, KEY_PATH, KEY_TITLE, KEY_SOURCE)
    }
}
