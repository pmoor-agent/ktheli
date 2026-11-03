package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.UiDriver
import ktheli.app.harness.uiTest
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.atan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinocularsTest {
    @Test
    fun `the inset sits in the chosen corner of the 3D view, 4 by 3`() {
        val s = BinocularSettings(enabled = true, size = 0.3)
        val br = Binoculars.layout(1600f, 880f, 20f, s)
        assertEquals(264f, br.height, 0.01f)
        assertEquals(br.height * 4f / 3f, br.width, 0.01f)
        assertEquals(1600f - Binoculars.MARGIN, br.x + br.width, 0.01f)
        assertEquals(20f + 880f - Binoculars.MARGIN, br.y + br.height, 0.01f)
        val tr = Binoculars.layout(1600f, 880f, 20f, s.copy(corner = Corner.TOP_RIGHT))
        assertEquals(20f + Binoculars.MARGIN, tr.y, 0.01f)
        val bl = Binoculars.layout(1600f, 880f, 20f, s.copy(corner = Corner.BOTTOM_LEFT))
        assertEquals(Binoculars.MARGIN, bl.x, 0.01f)
        val huge = Binoculars.layout(300f, 200f, 0f, s.copy(size = 2.0)) // never larger than the view
        assertTrue(huge.x >= 0f && huge.y >= 0f && huge.x + huge.width <= 300f && huge.y + huge.height <= 200f)
    }

    @Test
    fun `the field of view frames the model at any distance`() {
        for (distance in listOf(10.0, 40.0, 150.0)) {
            val fov = Binoculars.fov(distance, 0.36, 0.6, 60.0)
            assertEquals(0.36 / 0.6, kotlin.math.tan(Math.toRadians(fov) / 2) * distance, 1e-9) // model fills 60 %
        }
        assertEquals(60.0, Binoculars.fov(0.5, 0.36, 0.6, 60.0)) // very close: never wider than the main view
        val m = Binoculars.magnification(60.0, Math.toDegrees(2 * atan(0.6 / 40.0)))
        assertEquals(38.5, m, 0.1)
        assertEquals(1.0, Binoculars.magnification(50.0, 50.0), 1e-12)
    }

    @Test
    fun `binoculars toggle from key, menu and dialog, and show a frame`() = uiTest { ui ->
        assertFalse(ui.core.view.camera.binoculars.enabled)
        ui.press(Keys.B)
        assertTrue(ui.core.view.camera.binoculars.enabled)
        ui.waitForText("BINOCULARS")
        ui.menu("View", "Binoculars")
        assertFalse(ui.core.view.camera.binoculars.enabled)
        ui.press(Keys.F5)
        ui.click("Camera/Show binoculars")
        assertTrue(ui.core.view.camera.binoculars.enabled)
        ui.click("Camera/Top right")
        assertEquals(Corner.TOP_RIGHT, ui.core.view.camera.binoculars.corner)
        ui.waitFor("Camera/Framing")
        ui.snapshot(Paths.get("build/ui-snapshots/09-camera-binoculars.png"))
    }

    @Test
    fun `binocular settings persist`() {
        val dir = Files.createTempDirectory("ktheli-binoculars")
        try {
            UiDriver(configDir = dir).use { ui -> ui.press(Keys.B) }
            UiDriver(configDir = dir).use { ui -> assertTrue(ui.core.view.camera.binoculars.enabled) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
