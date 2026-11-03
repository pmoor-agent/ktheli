package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.uiTest
import ktheli.physics.heli.Helicopter
import ktheli.physics.model.ToyHeli
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlightTest {
    private fun heli(ui: ktheli.app.harness.UiDriver) = assertIs<Helicopter>(ui.core.runner.sim.aircraft)

    @Test
    fun `starts armed safe with throttle hold on`() = uiTest { ui ->
        ui.joystick.connected = false
        ui.waitForText("THROTTLE HOLD - H to release")
        ui.seconds(3.0)
        assertEquals(0.0, heli(ui).rotorRpm)
    }

    @Test
    fun `spool up in idle-up and lift off from the keyboard`() = uiTest { ui ->
        ui.joystick.connected = false
        ui.press(Keys.H) // release throttle hold
        ui.press(Keys.F) // idle up 1
        assertTrue(ui.core.controls.flightMode == 1 && !ui.core.controls.throttleHold)
        ui.waitForText("Idle up 1")
        ui.seconds(9.0) // collective centered: 0° pitch while the governor spools up
        assertEquals(2700.0, heli(ui).rotorRpm, 80.0)

        ui.hold(Keys.W, 0.7) // collective up to ~0.42: ~4.6°, a gentle climb
        ui.seconds(1.5)
        val altitude = -ui.core.runner.sim.aircraft.body.position.z
        assertTrue(altitude > 0.5, "airborne: $altitude m")
        ui.press(Keys.F2)
        ui.click("Telemetry/Rotor")
        ui.snapshot(Paths.get("build/ui-snapshots/07-liftoff-telemetry.png"))
    }

    @Test
    fun `throttle hold cuts the motor`() = uiTest { ui ->
        ui.joystick.connected = false
        ui.press(Keys.H)
        ui.press(Keys.F)
        ui.seconds(9.0)
        ui.press(Keys.H) // hold on again
        ui.seconds(3.0)
        assertTrue(heli(ui).rotorRpm < 2500.0, "coasting down: ${heli(ui).rotorRpm}")
    }

    @Test
    fun `aircraft can be switched from the menu`() = uiTest { ui ->
        ui.menu("Aircraft", SimRunner.AircraftType.TOY.label)
        assertIs<ToyHeli>(ui.core.runner.sim.aircraft)
        ui.menu("Aircraft", SimRunner.AircraftType.HELI_450.label)
        assertIs<Helicopter>(ui.core.runner.sim.aircraft)
        assertTrue(ui.core.input.throttleHold, "switching aircraft re-arms throttle hold")
    }

    @Test
    fun `flight mode menu and telemetry groups follow the helicopter`() = uiTest { ui ->
        ui.menu("Sim", "Flight mode", "Idle up 2")
        assertEquals(2, ui.core.controls.flightMode)
        ui.press(Keys.F) // cycles back to normal
        assertEquals(0, ui.core.controls.flightMode)
        ui.press(Keys.F2)
        for (tab in listOf("Flight", "Sticks", "Rotor", "Power", "Electrics", "Pitch", "Yaw", "Thrust", "Disc")) {
            ui.waitFor("Telemetry/$tab")
        }
    }
}
