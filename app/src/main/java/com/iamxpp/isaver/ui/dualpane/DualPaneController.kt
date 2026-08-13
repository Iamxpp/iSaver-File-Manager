package com.iamxpp.isaver.ui.dualpane

import com.iamxpp.isaver.domain.RootPath

enum class PaneId { PRIMARY, SECONDARY }

data class PaneLocation(val path: RootPath, val title: String)

data class DualPaneState(
    val enabled: Boolean = false,
    val activePane: PaneId = PaneId.PRIMARY,
    val primary: PaneLocation,
    val secondary: PaneLocation,
    val lockedPane: PaneId? = null,
)

class DualPaneController(initial: DualPaneState) {
    var state: DualPaneState = initial
        private set

    fun setEnabled(enabled: Boolean) {
        state = state.copy(enabled = enabled, activePane = if (enabled) state.activePane else PaneId.PRIMARY)
    }

    fun activate(pane: PaneId) {
        if (state.enabled) state = state.copy(activePane = pane)
    }

    fun update(pane: PaneId, location: PaneLocation) {
        if (state.lockedPane == pane) return
        state = when (pane) {
            PaneId.PRIMARY -> state.copy(primary = location)
            PaneId.SECONDARY -> state.copy(secondary = location)
        }
    }

    fun syncToOther() {
        val source = location(state.activePane)
        val target = state.activePane.other()
        if (state.lockedPane != target) update(target, source)
    }

    fun swap() {
        if (state.lockedPane != null) return
        state = state.copy(primary = state.secondary, secondary = state.primary)
    }

    fun toggleLock() {
        state = state.copy(lockedPane = if (state.lockedPane == state.activePane) null else state.activePane)
    }

    fun location(pane: PaneId): PaneLocation = when (pane) {
        PaneId.PRIMARY -> state.primary
        PaneId.SECONDARY -> state.secondary
    }
}

fun PaneId.other(): PaneId = if (this == PaneId.PRIMARY) PaneId.SECONDARY else PaneId.PRIMARY
