package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.uiTest
import ktheli.input.Action
import org.lwjgl.glfw.GLFW.GLFW_HAT_DOWN
import org.lwjgl.glfw.GLFW.GLFW_HAT_LEFT
import org.lwjgl.glfw.GLFW.GLFW_HAT_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_HAT_UP
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DpadTest {
    @Test
    fun `hats become four virtual buttons each, diagonals press two`() {
        val buttons = Joystick.withHatButtons(booleanArrayOf(true), intArrayOf(GLFW_HAT_UP or GLFW_HAT_RIGHT, GLFW_HAT_LEFT))
        assertContentEquals(
            booleanArrayOf(true, /* hat 0 */ true, true, false, false, /* hat 1 */ false, false, false, true),
            buttons,
        )
        val one = Joystick(0, "g", "pad", FloatArray(0), Joystick.withHatButtons(BooleanArray(2), intArrayOf(GLFW_HAT_DOWN)), 1)
        assertEquals(listOf("button 0", "button 1", "D-pad up", "D-pad right", "D-pad down", "D-pad left"), (0..5).map(one::buttonLabel))
        val two = Joystick(0, "g", "pad", FloatArray(0), Joystick.withHatButtons(BooleanArray(0), IntArray(2)), 2)
        assertEquals("D-pad 2 left", two.buttonLabel(7))
    }

    @Test
    fun `the monitor shows the D-pad`() = uiTest { ui ->
        ui.press(Keys.F1)
        ui.click("Controls/Monitor")
        for (direction in Joystick.HAT_DIRECTIONS) ui.waitFor("Controls/Monitor/D-pad $direction")
    }

    @Test
    fun `actions can be bound to D-pad directions`() = uiTest { ui ->
        ui.press(Keys.F1)
        ui.click("Controls/Buttons & keys")
        ui.click("Controls/Buttons & keys/Pause / resume/btn")
        ui.pressHat(GLFW_HAT_UP)
        assertEquals(ui.joystick.buttons.size, ui.core.session.input.buttonFor(Action.PAUSE)) // first virtual button
        ui.waitForText("Pause / resume -> D-pad up")
        assertFalse(ui.core.session.runner.paused)

        ui.pressHat(GLFW_HAT_UP)
        assertTrue(ui.core.session.runner.paused)
        ui.pressHat(GLFW_HAT_UP or GLFW_HAT_RIGHT) // a diagonal includes "up"
        assertFalse(ui.core.session.runner.paused)
        ui.pressHat(GLFW_HAT_DOWN)
        assertFalse(ui.core.session.runner.paused)
    }
}
