package ktheli.app

import ktheli.app.session.AircraftView
import ktheli.app.session.AppHost
import ktheli.app.session.AppSession
import ktheli.app.session.HelicopterModels
import ktheli.app.session.SettingsStore
import ktheli.input.Action
import ktheli.input.ProfileStore
import ktheli.physics.heli.Helicopter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session runs on its own: no ImGui context, no window, no [Ui]. */
class AppSessionTest {
    private var exits = 0

    private fun session(): AppSession {
        val dir = Files.createTempDirectory("ktheli-session")
        val host = object : AppHost {
            override val fps = 60
            override fun exit() {
                exits++
            }
            override fun requestScreenshot() {}
        }
        return AppSession(
            joysticks = { emptyList() },
            keyboard = { false },
            keys = GdxKeys,
            host = host,
            store = ProfileStore(dir.resolve("input.json")),
            settingsStore = SettingsStore(dir.resolve("settings.json")),
        )
    }

    @Test
    fun `flight mode switch follows the model without drawing a UI`() {
        val s = session()
        s.selectAircraft(HelicopterModels.TREX_450)
        val modes = assertIs<Helicopter>(s.runner.sim.aircraft).transmitter.setup.modes.size
        assertTrue(modes > 1)
        assertEquals(modes, s.input.flightModeCount)
        s.input.selectFlightMode(modes - 1)

        s.selectAircraft(HelicopterModels.TOY)
        assertEquals(1, s.input.flightModeCount)
        assertEquals(0, s.input.flightMode)
        assertTrue(s.input.throttleHold, "a new model starts armed")
    }

    @Test
    fun `actions run without a UI`() {
        val s = session()
        s.perform(Action.PAUSE)
        assertTrue(s.runner.paused)
        s.perform(Action.CONTROLS_DIALOG) // a window toggle: not the session's business
        s.perform(Action.QUIT)
        assertEquals(1, exits)
    }

    @Test
    fun `input feedback reaches the status line`() {
        val s = session()
        s.input.resetKeysToDefaults()
        assertEquals("Keyboard bindings reset to defaults", s.status.message)
    }

    @Test
    fun `the aircraft view hides the concrete model types`() {
        val s = session()
        s.selectAircraft(HelicopterModels.TREX_450)
        val heli = assertIs<Helicopter>(s.runner.sim.aircraft)
        val view = s.aircraft
        assertEquals(heli.transmitter.setup.modes.map { it.name }, view.flightModes)
        assertEquals(heli.rotorRpm, assertNotNull(view.headspeed).rpm)
        assertEquals(heli.mainRotor.bladeAzimuth, view.rotors.bladeAzimuth)
        assertEquals(0.0, AircraftView.of(heli, crashed = true).rotors.rpm, "frozen at the impact")

        s.selectAircraft(HelicopterModels.TOY)
        assertNull(s.aircraft.headspeed)
        assertTrue(s.aircraft.flightModes.isEmpty())
        assertEquals(AircraftView.TOY_ROTOR_RPM, s.aircraft.rotors.rpm)
    }
}
