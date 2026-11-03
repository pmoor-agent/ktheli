package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.UiDriver
import ktheli.app.harness.uiTest
import ktheli.physics.env.EnvironmentSettings
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentUiTest {
    @Test
    fun `presets change the wind immediately and the HUD shows it`() = uiTest { ui ->
        ui.press(Keys.F4)
        ui.click("Environment/Breezy")
        assertEquals(EnvironmentSettings.BREEZY, ui.core.runner.environment)
        assertEquals(EnvironmentSettings.BREEZY, ui.core.runner.sim.environment.settings)
        ui.waitForText("Wind 6.0 m/s from W (270)")
        ui.seconds(2.0)
        val windAtHeli = ui.core.runner.sim.environment.windAt(ui.core.runner.sim.aircraft.body.position).length()
        assertTrue(windAtHeli > 1.0, "the air is moving: $windAtHeli")
        ui.snapshot(Paths.get("build/ui-snapshots/08-environment.png"))
    }

    @Test
    fun `menu opens the dialog, and telemetry gets a wind tab`() = uiTest { ui ->
        ui.menu("Sim", "Environment...")
        ui.waitFor("Environment/Speed")
        ui.waitFor("Environment/Field elevation")
        ui.press(Keys.F2)
        ui.waitFor("Telemetry/Wind")
    }

    @Test
    fun `environment and aircraft choice persist across restarts`() {
        val dir = Files.createTempDirectory("ktheli-settings")
        try {
            UiDriver(configDir = dir).use { ui ->
                ui.press(Keys.F4)
                ui.click("Environment/High & hot")
                ui.menu("Aircraft", SimRunner.AircraftType.HELI_450_DISC.label)
            }
            UiDriver(configDir = dir).use { ui ->
                assertEquals(EnvironmentSettings.HIGH_AND_HOT, ui.core.runner.environment)
                assertEquals(SimRunner.AircraftType.HELI_450_DISC, ui.core.runner.aircraftType)
                ui.press(Keys.F4)
                ui.waitForText("Air density 0.899") // ISA at 2000 m, 35 C
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
