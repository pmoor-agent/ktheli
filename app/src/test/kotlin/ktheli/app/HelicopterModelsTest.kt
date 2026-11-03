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
    fun `rotors look sharp up to 300 rpm and blurred from 700, earlier only on slow displays`() {
        val blur = RotorBlur()
        assertEquals(0.0, blur.blur(0.0, 60.0))
        for (fps in listOf(45.0, 60.0, 144.0)) {
            assertEquals(0.0, blur.blur(290.0, fps), "sharp blades below 300 rpm at $fps fps")
            assertTrue(blur.blur(500.0, fps) in 0.4..0.6, "cross-fading in between at $fps fps")
            assertEquals(1.0, blur.blur(720.0, fps), "blurred above 700 rpm at $fps fps")
        }
        assertTrue(blur.blur(295.0, 20.0) > 0.0, "at 20 fps, 295 rpm (~89° per frame) would alias: start fading")
        assertEquals(1.0, blur.blur(2700.0, 20.0))
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
