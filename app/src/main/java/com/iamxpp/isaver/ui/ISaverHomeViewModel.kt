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

    fun openDevice() {
        transition(
            mutableState.value.copy(
                selectedTab = HomeTab.VIEWS,
                destination = HomeDestination.Device,
            ),
        )
    }

    fun closeDevice() {
        transition(
            mutableState.value.copy(
                selectedTab = HomeTab.VIEWS,
                destination = HomeDestination.Tab(HomeTab.VIEWS),
            ),
        )
    }

    fun selectTab(tab: HomeTab) {
        val copy = mutableState.value.destination as? HomeDestination.CopyTarget
        if (copy != null) {
            transition(
                mutableState.value.copy(
                    selectedTab = tab,
                    destination = copy.copy(
                        targetBrowser = if (tab == HomeTab.BROWSE) {
                            HomeDestination.Browser(BROWSE_ROOT, BROWSE_TITLE, HomeTab.BROWSE)
                        } else {
                            null
                        },
                    ),
                ),
            )
            return
        }
        val move = mutableState.value.destination as? HomeDestination.MoveTarget
        if (move != null) {
            transition(
                mutableState.value.copy(
                    selectedTab = tab,
                    destination = move.copy(
                        targetBrowser = if (tab == HomeTab.BROWSE) {
                            HomeDestination.Browser(BROWSE_ROOT, BROWSE_TITLE, HomeTab.BROWSE)
                        } else {
                            null
                        },
                    ),
                ),
            )
            return
        }
        val extraction = mutableState.value.destination as? HomeDestination.ExtractionTarget
        if (extraction != null) {
            transition(
                mutableState.value.copy(
                    selectedTab = tab,
                    destination = extraction.copy(
                        targetBrowser = if (tab == HomeTab.BROWSE) {
                            HomeDestination.Browser(BROWSE_ROOT, BROWSE_TITLE, HomeTab.BROWSE)
                        } else {
                            null
                        },
                    ),
                ),
            )
            return
        }
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

    fun openLocation(
        path: RootPath,
        displayName: String,
        source: HomeTab = HomeTab.VIEWS,
        recordAccess: Boolean = true,
    ) {
        val copy = mutableState.value.destination as? HomeDestination.CopyTarget
        if (copy != null) {
            transition(
                mutableState.value.copy(
                    selectedTab = source,
                    destination = copy.copy(
                        targetBrowser = HomeDestination.Browser(path, displayName, source, recordAccess),
                    ),
                ),
            )
            return
        }
        val move = mutableState.value.destination as? HomeDestination.MoveTarget
        if (move != null) {
            transition(
                mutableState.value.copy(
                    selectedTab = source,
                    destination = move.copy(
                        targetBrowser = HomeDestination.Browser(path, displayName, source, recordAccess),
                    ),
                ),
            )
            return
        }
        val extraction = mutableState.value.destination as? HomeDestination.ExtractionTarget
        if (extraction != null) {
            transition(
                mutableState.value.copy(
                    selectedTab = source,
                    destination = extraction.copy(
                        targetBrowser = HomeDestination.Browser(path, displayName, source, recordAccess),
                    ),
                ),
            )
            return
        }
        transition(
            mutableState.value.copy(
                selectedTab = source,
                destination = HomeDestination.Browser(path, displayName, source, recordAccess),
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

    fun chooseMoveTarget() {
        val source = mutableState.value.destination as? HomeDestination.Browser ?: return
        transition(
            mutableState.value.copy(
                selectedTab = HomeTab.VIEWS,
                destination = HomeDestination.MoveTarget(source),
            ),
        )
    }

    fun chooseCopyTarget() {
        val source = mutableState.value.destination as? HomeDestination.Browser ?: return
        transition(
            mutableState.value.copy(
                selectedTab = HomeTab.VIEWS,
                destination = HomeDestination.CopyTarget(source),
            ),
        )
    }

    fun returnFromCopy() {
        val copy = mutableState.value.destination as? HomeDestination.CopyTarget ?: return
        transition(
            mutableState.value.copy(
                selectedTab = copy.sourceBrowser.source,
                destination = copy.sourceBrowser,
            ),
        )
    }

    fun completeCopy(targetDirectory: RootPath, targetTitle: String) {
        val copy = mutableState.value.destination as? HomeDestination.CopyTarget ?: return
        val target = copy.targetBrowser ?: return
        transition(
            mutableState.value.copy(
                selectedTab = target.source,
                destination = target.copy(path = targetDirectory, title = targetTitle),
            ),
        )
    }

    fun returnFromMove() {
        val move = mutableState.value.destination as? HomeDestination.MoveTarget ?: return
        transition(
            mutableState.value.copy(
                selectedTab = move.sourceBrowser.source,
                destination = move.sourceBrowser,
            ),
        )
    }

    fun completeMove(targetDirectory: RootPath, targetTitle: String) {
        val move = mutableState.value.destination as? HomeDestination.MoveTarget ?: return
        val target = move.targetBrowser ?: return
        transition(
            mutableState.value.copy(
                selectedTab = target.source,
                destination = target.copy(path = targetDirectory, title = targetTitle),
            ),
        )
    }

    fun returnToArchive() {
        val extraction = mutableState.value.destination as? HomeDestination.ExtractionTarget ?: return
        transition(
            mutableState.value.copy(
                selectedTab = extraction.sourceTab,
                destination = HomeDestination.Archive(
                    extraction.source,
                    extraction.sourceName,
                    extraction.sourceTab,
                ),
            ),
        )
    }

    fun closeArchive() {
        val archive = mutableState.value.destination as? HomeDestination.Archive ?: return
        selectTab(archive.sourceTab)
    }

    fun onBrowserBack(result: BrowserBackResult): HomeBackResult {
        if (result != BrowserBackResult.RETURN_HOME) return HomeBackResult.CONSUMED
        val extraction = mutableState.value.destination as? HomeDestination.ExtractionTarget
        if (extraction?.targetBrowser != null) {
            transition(
                mutableState.value.copy(
                    selectedTab = HomeTab.VIEWS,
                    destination = extraction.copy(targetBrowser = null),
                ),
            )
            return HomeBackResult.CONSUMED
        }
        val move = mutableState.value.destination as? HomeDestination.MoveTarget
        if (move?.targetBrowser != null) {
            transition(
                mutableState.value.copy(
                    selectedTab = HomeTab.VIEWS,
                    destination = move.copy(targetBrowser = null),
                ),
            )
            return HomeBackResult.CONSUMED
        }
        val copy = mutableState.value.destination as? HomeDestination.CopyTarget
        if (copy?.targetBrowser != null) {
            transition(
                mutableState.value.copy(
                    selectedTab = HomeTab.VIEWS,
                    destination = copy.copy(targetBrowser = null),
                ),
            )
            return HomeBackResult.CONSUMED
        }
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
            HomeDestination.Device -> {
                savedStateHandle[KEY_DESTINATION] = DESTINATION_DEVICE
                savedStateHandle.remove<String>(KEY_PATH)
                savedStateHandle.remove<String>(KEY_TITLE)
                savedStateHandle.remove<String>(KEY_SOURCE)
            }
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
                savedStateHandle[KEY_RECORD_ACCESS] = destination.recordAccess.toString()
            }
            is HomeDestination.Archive -> saveArchiveDestination(
                DESTINATION_ARCHIVE,
                destination.source,
                destination.sourceName,
                destination.sourceTab,
            )
            is HomeDestination.ExtractionTarget -> {
                saveArchiveDestination(
                    DESTINATION_EXTRACTION_TARGET,
                    destination.source,
                    destination.sourceName,
                    destination.sourceTab,
                )
                destination.targetBrowser?.let { browser ->
                    savedStateHandle[KEY_TARGET_PATH] = browser.path.value
                    savedStateHandle[KEY_TARGET_TITLE] = browser.title
                    savedStateHandle[KEY_TARGET_SOURCE] = browser.source.name
                    savedStateHandle[KEY_TARGET_RECORD_ACCESS] = browser.recordAccess.toString()
                } ?: clearSavedTarget()
            }
            is HomeDestination.MoveTarget -> {
                savedStateHandle[KEY_SELECTED_TAB] = destination.sourceBrowser.source.name
                savedStateHandle[KEY_DESTINATION] = DESTINATION_BROWSER
                savedStateHandle[KEY_PATH] = destination.sourceBrowser.path.value
                savedStateHandle[KEY_TITLE] = destination.sourceBrowser.title
                savedStateHandle[KEY_SOURCE] = destination.sourceBrowser.source.name
                savedStateHandle[KEY_RECORD_ACCESS] = destination.sourceBrowser.recordAccess.toString()
                clearSavedTarget()
            }
            is HomeDestination.CopyTarget -> {
                savedStateHandle[KEY_SELECTED_TAB] = destination.sourceBrowser.source.name
                savedStateHandle[KEY_DESTINATION] = DESTINATION_BROWSER
                savedStateHandle[KEY_PATH] = destination.sourceBrowser.path.value
                savedStateHandle[KEY_TITLE] = destination.sourceBrowser.title
                savedStateHandle[KEY_SOURCE] = destination.sourceBrowser.source.name
                savedStateHandle[KEY_RECORD_ACCESS] = destination.sourceBrowser.recordAccess.toString()
                clearSavedTarget()
            }
        }
    }

    private fun restoreState(): ISaverHomeUiState {
        if (savedStateHandle.keys().isEmpty()) return ISaverHomeUiState()
        return try {
            val selected = HomeTab.valueOf(savedStateHandle.get<String>(KEY_SELECTED_TAB).orEmpty())
            when (savedStateHandle.get<String>(KEY_DESTINATION)) {
                DESTINATION_DEVICE -> ISaverHomeUiState(HomeTab.VIEWS, HomeDestination.Device)
                DESTINATION_TAB -> ISaverHomeUiState(selected, HomeDestination.Tab(selected))
                DESTINATION_BROWSER -> {
                    val path = RootPath.parse(savedStateHandle.get<String>(KEY_PATH).orEmpty()).getOrThrow()
                    val title = savedStateHandle.get<String>(KEY_TITLE).orEmpty()
                    val source = HomeTab.valueOf(savedStateHandle.get<String>(KEY_SOURCE).orEmpty())
                    require(selected == source) { "Selected tab must match browser source" }
                    val recordAccess = savedStateHandle.get<String>(KEY_RECORD_ACCESS)
                        ?.toBooleanStrictOrNull() ?: true
                    ISaverHomeUiState(selected, HomeDestination.Browser(path, title, source, recordAccess))
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
                        HomeDestination.ExtractionTarget(
                            source,
                            sourceName,
                            sourceTab,
                            restoreTargetBrowser(),
                        ),
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

    private fun restoreTargetBrowser(): HomeDestination.Browser? {
        val rawPath = savedStateHandle.get<String>(KEY_TARGET_PATH) ?: return null
        val title = savedStateHandle.get<String>(KEY_TARGET_TITLE).orEmpty()
        val source = HomeTab.valueOf(savedStateHandle.get<String>(KEY_TARGET_SOURCE).orEmpty())
        val recordAccess = savedStateHandle.get<String>(KEY_TARGET_RECORD_ACCESS)
            ?.toBooleanStrictOrNull() ?: true
        return HomeDestination.Browser(RootPath.parse(rawPath).getOrThrow(), title, source, recordAccess)
    }

    private fun clearSavedTarget() {
        savedStateHandle.remove<String>(KEY_TARGET_PATH)
        savedStateHandle.remove<String>(KEY_TARGET_TITLE)
        savedStateHandle.remove<String>(KEY_TARGET_SOURCE)
        savedStateHandle.remove<String>(KEY_TARGET_RECORD_ACCESS)
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
        const val KEY_RECORD_ACCESS = "home.recordAccess"
        const val KEY_TARGET_PATH = "home.targetPath"
        const val KEY_TARGET_TITLE = "home.targetTitle"
        const val KEY_TARGET_SOURCE = "home.targetSource"
        const val KEY_TARGET_RECORD_ACCESS = "home.targetRecordAccess"
        const val DESTINATION_TAB = "TAB"
        const val DESTINATION_DEVICE = "DEVICE"
        const val DESTINATION_BROWSER = "BROWSER"
        const val DESTINATION_ARCHIVE = "ARCHIVE"
        const val DESTINATION_EXTRACTION_TARGET = "EXTRACTION_TARGET"
        val SAVED_STATE_KEYS = listOf(
            KEY_SELECTED_TAB, KEY_DESTINATION, KEY_PATH, KEY_TITLE, KEY_SOURCE, KEY_RECORD_ACCESS,
            KEY_TARGET_PATH, KEY_TARGET_TITLE, KEY_TARGET_SOURCE, KEY_TARGET_RECORD_ACCESS,
        )
    }
}
