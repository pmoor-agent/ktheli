package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.UiDriver
import ktheli.app.harness.uiTest
import ktheli.input.Action
import ktheli.input.KeyChord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlsDialogTest {
    private val bindings = "Controls/Buttons & keys"

    @Test
    fun `menu opens the controls dialog`() = uiTest { ui ->
        ui.menu("Input", "Controls...")
        assertTrue(ui.core.ui.controlsWindow.open.get())
        ui.waitFor("Controls/Sticks/Start calibration")
    }

    @Test
    fun `rebinding a key through the dialog`() = uiTest { ui ->
        ui.press(Keys.F1)
        ui.click(bindings)
        ui.click("$bindings/Pause / resume/key")
        assertIs<InputController.Mode.CapturingKey>(ui.core.input.mode)
        ui.waitForText("Press a key for \"Pause / resume\"")

        ui.press(Keys.F5)
        assertEquals(KeyChord(Keys.F5), ui.core.input.keyFor(Action.PAUSE))
        ui.waitForText("Pause / resume -> F5")

        ui.press(Keys.P) // the old key no longer pauses
        assertFalse(ui.core.runner.paused)
        ui.press(Keys.F5)
        assertTrue(ui.core.runner.paused)
        ui.waitForText("PAUSED")
    }

    @Test
    fun `binding a key that is in use takes it from the other action`() = uiTest { ui ->
        ui.press(Keys.F1)
        ui.click(bindings)
        ui.click("$bindings/Reset/key")
        ui.press(Keys.P)
        assertEquals(KeyChord(Keys.P), ui.core.input.keyFor(Action.RESET))
        assertNull(ui.core.input.keyFor(Action.PAUSE))

        ui.click("$bindings/Reset keyboard to defaults")
        assertEquals(KeyChord(Keys.P), ui.core.input.keyFor(Action.PAUSE))
        assertEquals(KeyChord(Keys.R), ui.core.input.keyFor(Action.RESET))
    }

    @Test
    fun `clearing a key binding`() = uiTest { ui ->
        ui.press(Keys.F1)
        ui.click(bindings)
        ui.click("$bindings/Screenshot/key-clear")
        assertNull(ui.core.input.keyFor(Action.SCREENSHOT))
        assertFalse(ui.exists("$bindings/Screenshot/key-clear"))
    }

    @Test
    fun `escape cancels a capture`() = uiTest { ui ->
        ui.press(Keys.F1)
        ui.click(bindings)
        ui.click("$bindings/Reset/key")
        ui.press(Keys.ESCAPE)
        assertEquals(InputController.Mode.Fly, ui.core.input.mode)
        assertEquals(KeyChord(Keys.R), ui.core.input.keyFor(Action.RESET))
    }

    @Test
    fun `controller buttons trigger bound actions`() = uiTest { ui ->
        ui.press(Keys.F1)
        ui.click(bindings)
        ui.click("$bindings/Pause / resume/btn")
        ui.pressButton(7)
        assertEquals(7, ui.core.input.buttonFor(Action.PAUSE))
        assertFalse(ui.core.runner.paused, "the capturing press itself must not trigger the action")

        ui.pressButton(7)
        assertTrue(ui.core.runner.paused)
        ui.pressButton(7)
        assertFalse(ui.core.runner.paused)
    }

    @Test
    fun `quit via shortcut and via menu`() {
        UiDriver().use { ui ->
            ui.press(Keys.Q, Keys.CONTROL_LEFT)
            assertTrue(ui.exitRequested)
        }
        UiDriver().use { ui ->
            ui.press(Keys.Q) // without Ctrl it is not bound
            assertFalse(ui.exitRequested)
            ui.menu("Sim", "Quit")
            assertTrue(ui.exitRequested)
        }
    }
}
