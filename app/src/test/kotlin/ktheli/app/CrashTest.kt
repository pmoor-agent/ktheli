package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.UiDriver
import ktheli.app.harness.glTest
import ktheli.app.harness.uiTest
import ktheli.app.session.FlyingFields
import ktheli.app.session.SettingsStore
import ktheli.physics.math.Vec3
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashTest {
    /** Spools up in idle-up 1 on the ground, then tips the model over with full right cyclic. */
    private fun crash(ui: UiDriver) {
        ui.joystick.connected = false
        ui.press(Keys.H) // throttle hold off
        ui.press(Keys.F) // idle up 1
        ui.seconds(9.0)
        repeat(40) { if (ui.core.session.runner.crash == null) ui.hold(Keys.RIGHT, 0.1) } // until it goes over
        assertNotNull(ui.core.session.runner.crash, "tipped over")
        assertTrue(ui.core.session.runner.crashedSeconds < 0.2)
    }

    @Test
    fun `tipping over crashes, shows why, and resets by itself keeping the switches`() = uiTest { ui ->
        crash(ui)
        ui.waitForText("CRASHED")
        ui.waitForText("Main rotor strike")
        assertTrue(ui.hasText("Resetting in"))

        val tick = ui.core.session.runner.sim.tick
        ui.press(Keys.P) // can't pause (or unpause) a crash
        assertFalse(ui.core.session.runner.paused)
        ui.seconds(0.5)
        assertEquals(tick, ui.core.session.runner.sim.tick, "frozen")

        ui.seconds(3.0) // auto reset after 3 s
        assertNull(ui.core.session.runner.crash)
        assertFalse(ui.hasText("CRASHED"))
        assertEquals(1, ui.core.session.controls.flightMode, "still in idle up 1")
        assertFalse(ui.core.session.controls.throttleHold, "throttle hold still off")
        ui.seconds(2.0)
        assertTrue(ui.core.session.runner.sim.tick > 0 && (ui.core.session.runner.sim.aircraft as ktheli.physics.heli.Helicopter).rotorRpm > 100, "spooling up again")
    }

    @Test
    fun `the crash as rendered`() = glTest { ui ->
        crash(ui)
        ui.frame(3)
        ui.snapshot(Paths.get("build/ui-snapshots/11-crashed.png"))
    }

    @Test
    fun `without auto reset the crash stays until reset`() = uiTest { ui ->
        ui.menu("Sim", "After a crash", "Reset automatically")
        assertFalse(ui.core.session.crashSettings.autoReset)
        assertFalse(SettingsStore(ui.configDir.resolve("settings.json")).load().crash.autoReset)
        crash(ui)
        ui.seconds(6.0)
        assertNotNull(ui.core.session.runner.crash)
        ui.waitForText("R to reset")
        ui.press(Keys.R)
        assertNull(ui.core.session.runner.crash)
        assertEquals(1, ui.core.session.controls.flightMode)
    }

    @Test
    fun `a crash plays an impact and silences the model`() = uiTest { ui ->
        val before = ui.core.session.soundParams()
        crash(ui)
        val after = ui.core.session.soundParams()
        assertEquals(before.impacts + 1, after.impacts)
        assertTrue(after.impactLevel > 0.3)
        assertEquals(0.0, after.rotorLevel + after.rotorNoise + after.washLevel + after.motorLevel + after.tailLevel)
        assertTrue(after.volume > 0)
    }

    @Test
    fun `the home field's trees and windsock are obstacles`() {
        val field = FlyingFields.HOME
        val tree = field.trees!!.trees().first()
        val obstacles = field.obstacles
        assertTrue(obstacles.all.size > 1000)
        assertNotNull(obstacles.hit(tree.position + Vec3(0.0, 0.0, -0.6 * tree.height)), "in the crown")
        assertNotNull(obstacles.hit(field.windsock + Vec3(0.0, 0.0, -1.5)), "the windsock pole")
        assertNull(obstacles.hit(Vec3(0.0, 0.0, -2.0)), "open air over the pad")
        assertTrue(FlyingFields.OPEN.obstacles.all.size == 1, "only the windsock")
    }
}
