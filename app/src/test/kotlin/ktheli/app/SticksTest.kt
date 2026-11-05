package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.uiTest
import ktheli.input.Action
import ktheli.input.InputController
import ktheli.input.CalibrationSession
import ktheli.input.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SticksTest {
    private val sticks = "Controls/Sticks"

    @Test
    fun `calibration learns the neutral band so resting drift reads zero`() = uiTest { ui ->
        ui.press(Keys.F1)
        ui.click("$sticks/Start calibration")
        ui.waitForText("Keep all sticks centered and still")

        // Phase 1: sticks at rest, roll stick (axis 2) jittering by +/-0.03.
        repeat(100) { i ->
            ui.joystick.axes[2] = if (i % 2 == 0) 0.03f else -0.03f
            ui.frame()
        }
        val mode = assertIs<InputController.Mode.Calibrating>(ui.core.session.input.mode)
        assertEquals(CalibrationSession.Phase.EXTREMES, mode.session.phase)

        // Phase 2: sweep every axis through its full travel.
        for (axis in ui.joystick.axes.indices) {
            for (v in listOf(-1f, 1f, 0f)) {
                ui.joystick.axes[axis] = v
                ui.frame()
            }
        }
        ui.click("$sticks/Finish")
        ui.waitForText("Calibration saved")

        val cal = ui.core.session.input.activeProfile!!.calibration[2]
        assertEquals(0.03f * 1.5f + 0.01f, cal.neutralHigh, 1e-5f)

        ui.joystick.axes[2] = 0.05f // inside the learned band
        ui.frame()
        assertEquals(0.0, ui.core.session.controls.roll)
        ui.joystick.axes[2] = 1f
        ui.frame()
        assertEquals(1.0, ui.core.session.controls.roll, 1e-6)
    }

    @Test
    fun `assigning an axis by moving the stick`() = uiTest { ui ->
        ui.press(Keys.F1)
        ui.click("$sticks/collective/Assign")
        ui.waitForText("Move COLLECTIVE (pitch stick) UP")
        ui.joystick.axes[4] = -0.8f // this device reports "up" as negative
        ui.frame()
        val binding = ui.core.session.input.activeProfile!!.bindings.getValue(Channel.COLLECTIVE)
        assertEquals(4, binding.axis)
        assertTrue(binding.reversed)
        ui.waitForText("collective -> axis 4 (reversed)")
    }

    @Test
    fun `a cleared collective flies on controller buttons`() = uiTest { ui ->
        ui.press(Keys.F1)
        ui.click("$sticks/collective/Clear")
        assertNull(ui.core.session.input.activeProfile!!.bindings[Channel.COLLECTIVE])
        ui.waitForText("keys/buttons")

        ui.click("Controls/Buttons & keys")
        ui.click("Controls/Buttons & keys/Collective up/btn")
        ui.pressButton(5)
        ui.click("Controls/Buttons & keys/Collective down/btn")
        ui.pressButton(4)
        assertEquals(5, ui.core.session.input.buttonFor(Action.COLLECTIVE_UP))
        assertEquals(4, ui.core.session.input.buttonFor(Action.COLLECTIVE_DOWN))

        val start = ui.core.session.controls.collective
        ui.holdButton(5, 1.0)
        assertEquals(start + 0.6, ui.core.session.controls.collective, 0.03) // integrates at 0.6/s and stays
        ui.seconds(1.0)
        assertEquals(start + 0.6, ui.core.session.controls.collective, 0.03)
        ui.holdButton(4, 0.5)
        assertEquals(start + 0.3, ui.core.session.controls.collective, 0.03)

        ui.press(Keys.K) // collective to center
        assertEquals(0.0, ui.core.session.controls.collective)
    }

    @Test
    fun `channels without a joystick fly on the keyboard`() = uiTest { ui ->
        ui.joystick.connected = false
        ui.frame()
        ui.hold(Keys.W, 0.5)
        assertEquals(0.3, ui.core.session.controls.collective, 0.02)
        ui.hold(Keys.RIGHT, 0.1)
        ui.frame()
        assertEquals(0.0, ui.core.session.controls.roll) // released: cyclic recenters
    }
}
