package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.UiDriver
import ktheli.app.harness.uiTest
import ktheli.app.ui.UiTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusMessageTest {
    private fun UiDriver.inHud(text: String) = UiTrace.texts.any { it.path == "hud" && text in it.text }

    @Test
    fun `status messages show in the HUD for a few seconds, then fade out`() = uiTest { ui ->
        val status = ui.core.session.status
        ui.core.session.input.resetKeysToDefaults()
        ui.frame()
        assertTrue(ui.inHud("Keyboard bindings reset to defaults"))
        assertEquals(1.0, status.opacity)

        ui.seconds(2.5)
        assertTrue(ui.inHud("Keyboard bindings reset to defaults"), "still there")
        ui.seconds(1.0)
        assertTrue(status.opacity in 0.1..0.9, "fading: ${status.opacity}")
        ui.seconds(1.0)
        assertFalse(ui.inHud("Keyboard bindings reset to defaults"), "gone")
        assertEquals("Keyboard bindings reset to defaults", status.message, "the Controls dialog still shows the latest")
    }

    @Test
    fun `with the HUD off, a message still shows on its own`() = uiTest { ui ->
        ui.press(Keys.F3) // HUD off
        assertFalse(ui.hasText("Input:"))
        ui.core.session.status.show("Saved ktheli-1.png")
        ui.frame()
        assertTrue(ui.inHud("Saved ktheli-1.png"))
        assertFalse(ui.hasText("Input:"), "just the message, no flight data")
        ui.seconds(4.5)
        assertFalse(ui.hasText("Saved ktheli-1.png"))
    }
}
