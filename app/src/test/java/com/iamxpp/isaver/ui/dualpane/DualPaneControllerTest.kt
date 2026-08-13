package com.iamxpp.isaver.ui.dualpane

import com.iamxpp.isaver.domain.RootPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DualPaneControllerTest {
    private val first = PaneLocation(RootPath.parse("/first").getOrThrow(), "第一窗")
    private val second = PaneLocation(RootPath.parse("/second").getOrThrow(), "第二窗")

    @Test
    fun `dual pane sync swap lock and disable preserve locations`() {
        val controller = DualPaneController(DualPaneState(primary = first, secondary = second))
        controller.setEnabled(true)
        controller.activate(PaneId.SECONDARY)
        controller.syncToOther()
        assertEquals(second, controller.state.primary)

        controller.toggleLock()
        controller.update(PaneId.SECONDARY, first)
        assertEquals(second, controller.state.secondary)
        controller.swap()
        assertEquals(second, controller.state.primary)

        controller.setEnabled(false)
        assertFalse(controller.state.enabled)
        assertEquals(PaneId.PRIMARY, controller.state.activePane)
        assertEquals(second, controller.state.secondary)
    }
}
