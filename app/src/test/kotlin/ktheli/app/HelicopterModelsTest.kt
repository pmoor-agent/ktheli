package ktheli.app

import ktheli.app.harness.UiDriver
import ktheli.physics.heli.Helicopter
import ktheli.physics.math.Vec3
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HelicopterModelsTest {
    @Test
    fun `models have unique ids and park on their skids`() {
        assertEquals(HelicopterModels.ALL.size, HelicopterModels.ALL.map { it.id }.toSet().size)
        for (model in HelicopterModels.ALL) {
            val aircraft = model.create(Vec3(6.0, 0.0, 0.0))
            assertEquals(6.0, aircraft.body.position.x)
            assertTrue(aircraft.body.position.z in -0.2..0.0, "${model.id} rests on the ground")
        }
    }

    @Test
    fun `the T-Rex visual takes its hubs and size from its physics airframe`() {
        val model = HelicopterModels.TREX_450
        val visual = assertIs<HelicopterVisual.Obj>(model.visual)
        val airframe = model.spec!!.airframe
        assertEquals(Vec3(0.0, 0.0, -airframe.mainRotor.hubHeight), visual.mainHub)
        assertEquals(airframe.tailRotor.position, visual.tailHub)
        assertEquals(airframe.mainRotor.rotor.radius, visual.rotorRadius)
        assertEquals(0.028, airframe.tailRotor.position.y, 1e-12) // on the right of the boom, as modelled
        assertIs<Helicopter>(model.create(Vec3.ZERO))
    }

    @Test
    fun `settings from before models still resolve`() {
        assertEquals(HelicopterModels.TREX_450, HelicopterModels.byId("HELI_450"))
        assertEquals(HelicopterModels.TREX_450_DISC, HelicopterModels.byId("HELI_450_DISC"))
        assertEquals(HelicopterModels.TOY, HelicopterModels.byId("TOY"))
        assertEquals(HelicopterModels.DEFAULT, AppSettings(aircraft = "no-such-model").model)

        val dir = Files.createTempDirectory("ktheli-legacy")
        try {
            Files.writeString(dir.resolve("settings.json"), """{"aircraft":"HELI_450_DISC"}""")
            UiDriver(configDir = dir).use { ui -> assertEquals(HelicopterModels.TREX_450_DISC, ui.core.runner.model) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rotors look sharp when slow and blurred when fast, depending on frame rate`() {
        val blur = RotorBlur()
        assertEquals(0.0, blur.blur(0.0, 60.0))
        assertEquals(0.0, blur.blur(100.0, 60.0)) // 10° per frame: distinct blades
        assertEquals(1.0, blur.blur(2700.0, 60.0)) // 270° per frame: only a blur
        assertTrue(blur.blur(300.0, 60.0) in 0.1..0.9) // in between: cross-fading
        assertTrue(blur.blur(300.0, 144.0) < blur.blur(300.0, 60.0)) // a faster display shows blades longer
        assertEquals(1.0, blur.bladeOpacity(0.0))
        assertEquals(0.0, blur.discOpacity(0.0))
        assertEquals(blur.bladeOpacityBlurred, blur.bladeOpacity(1.0), 1e-12)
    }

    @Test
    fun `the aircraft menu lists every model by name`() = ktheli.app.harness.uiTest { ui ->
        ui.click("Aircraft")
        for (model in HelicopterModels.ALL) ui.waitFor("Aircraft/${model.name}")
    }
}
