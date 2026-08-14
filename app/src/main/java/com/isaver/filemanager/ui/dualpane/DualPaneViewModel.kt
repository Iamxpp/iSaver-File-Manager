package com.isaver.filemanager.ui.dualpane

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.isaver.filemanager.domain.RootPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DualPaneViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    private val initialPath = RootPath.parse("/").getOrThrow()
    private val controller = DualPaneController(
        DualPaneState(
            enabled = savedStateHandle[KEY_ENABLED] ?: false,
            activePane = savedStateHandle.get<String>(KEY_ACTIVE)?.let(PaneId::valueOf) ?: PaneId.PRIMARY,
            primary = PaneLocation(initialPath, "浏览"),
            secondary = PaneLocation(initialPath, "浏览"),
            lockedPane = savedStateHandle.get<String>(KEY_LOCKED)?.let(PaneId::valueOf),
        ),
    )
    private val mutableState = MutableStateFlow(controller.state)
    val state: StateFlow<DualPaneState> = mutableState.asStateFlow()

    fun setEnabled(enabled: Boolean) = mutate { setEnabled(enabled) }
    fun activate(pane: PaneId) = mutate { activate(pane) }
    fun update(pane: PaneId, path: RootPath, title: String) = mutate { update(pane, PaneLocation(path, title)) }
    fun syncToOther() = mutate(DualPaneController::syncToOther)
    fun swap() = mutate(DualPaneController::swap)
    fun toggleLock() = mutate(DualPaneController::toggleLock)

    private fun mutate(block: DualPaneController.() -> Unit) {
        controller.block()
        mutableState.value = controller.state
        savedStateHandle[KEY_ENABLED] = controller.state.enabled
        savedStateHandle[KEY_ACTIVE] = controller.state.activePane.name
        controller.state.lockedPane?.let { savedStateHandle[KEY_LOCKED] = it.name }
            ?: savedStateHandle.remove<String>(KEY_LOCKED)
    }

    private companion object {
        const val KEY_ENABLED = "dualPane.enabled"
        const val KEY_ACTIVE = "dualPane.active"
        const val KEY_LOCKED = "dualPane.locked"
    }
}
