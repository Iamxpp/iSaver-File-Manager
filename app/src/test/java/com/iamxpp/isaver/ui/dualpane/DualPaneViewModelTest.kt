package com.iamxpp.isaver.ui.dualpane

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualPaneViewModelTest {
    @Test
    fun `mode active pane and lock survive recreation without persisting paths`() {
        val handle = SavedStateHandle()
        val viewModel = DualPaneViewModel(handle)
        viewModel.setEnabled(true)
        viewModel.activate(PaneId.SECONDARY)
        viewModel.toggleLock()

        val restored = DualPaneViewModel(handle).state.value
        assertTrue(restored.enabled)
        assertEquals(PaneId.SECONDARY, restored.activePane)
        assertEquals(PaneId.SECONDARY, restored.lockedPane)
        assertEquals("/", restored.primary.path.value)
        assertEquals("/", restored.secondary.path.value)
    }
}
